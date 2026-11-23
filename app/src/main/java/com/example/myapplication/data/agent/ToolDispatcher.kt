package com.example.myapplication.data.agent

import androidx.room.withTransaction
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.AiToolExecutionEntity
import com.example.myapplication.domain.agent.AgentHashing
import com.example.myapplication.domain.agent.ToolError
import com.example.myapplication.domain.agent.ToolErrorCode
import com.example.myapplication.domain.agent.ToolExecutionContext
import com.example.myapplication.domain.agent.ToolRegistry
import com.example.myapplication.domain.agent.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 工具派发与幂等（W4-04 / 详细设计 §12；04 接口 §6/§8）。
 *
 * 规则：
 *  - `(run_id, tool_call_id)` 唯一：同 id 同 argumentsHash → **复用已存结果**，不重复执行；
 *    同 id 不同 hash → INVALID_PROVIDER_RESPONSE（不执行新操作）；
 *  - 只读工具允许模型用**新 callId** 重试；写提议类工具重试只返回原 proposal（execution 已存在）；
 *  - 参数不合法返回结构化 ToolError；同一路径相同错误最多 2 次，之后 [PathState] 置终止；
 *  - 用户 stop 后网络返回/工具结果不得再落库（isCancelled 检查在写入前后各一次）。
 */
class ToolDispatcher(
    private val database: AppDatabase,
    private val registry: ToolRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
) : AgentToolDispatchPort {
    data class DispatchOutcome(
        val result: ToolResult,
        val replayed: Boolean = false,
        val cancelled: Boolean = false,
        val terminateToolPath: Boolean = false,
    )

    /** 单次 run 的工具路径状态：调用计数 + 相同错误连续次数。 */
    class PathState {
        var totalCalls: Int = 0
            private set
        private val invalidCounts = mutableMapOf<String, Int>()

        fun onToolCall() {
            totalCalls += 1
        }

        /** 返回 true 表示相同错误达到上限，应终止该路径。 */
        internal fun onInvalid(signature: String): Boolean {
            val next = (invalidCounts[signature] ?: 0) + 1
            invalidCounts[signature] = next
            return next >= MAX_IDENTICAL_INVALID
        }

        internal fun onSuccess() {
            invalidCounts.clear()
        }
    }

    override suspend fun dispatch(
        context: ToolExecutionContext,
        call: com.example.myapplication.domain.ai.ToolCall,
        state: PathState,
        isCancelled: () -> Boolean,
    ): DispatchOutcome {
        val toolCall = com.example.myapplication.domain.agent.ToolCall(
            callId = call.callId,
            toolName = call.name,
            argumentsJson = call.argumentsJson,
        )
        val dao = database.aiRunDao()
        val now = clock()
        val argumentsHash = AgentHashing.sha256(toolCall.argumentsJson)
        val previous = database.withTransaction {
            if (isCancelled() || dao.findRun(context.userId, context.runId)?.status !in RunCoordinator.ACTIVE_STATUSES) {
                return@withTransaction cancelledOutcome(toolCall)
            }
            val existing = dao.findToolExecution(context.runId, toolCall.callId)
            if (existing != null) {
                if (existing.argumentsHash != argumentsHash) {
                    return@withTransaction DispatchOutcome(
                        result = ToolResult.failure(toolCall.callId, toolCall.toolName,
                            ToolError(ToolErrorCode.INVALID_ARGUMENT,
                                "INVALID_PROVIDER_RESPONSE: callId reused with different arguments", retryable = false)),
                        replayed = true,
                    )
                }
                return@withTransaction DispatchOutcome(result = replay(existing), replayed = true)
            }
            dao.insertToolExecution(
                AiToolExecutionEntity(
                    runId = context.runId,
                    toolCallId = toolCall.callId,
                    toolName = toolCall.toolName,
                    argumentsHash = argumentsHash,
                    status = STATUS_PENDING,
                    updatedAt = now,
                ),
            )
            null
        }
        if (previous != null) return previous
        if (isCancelled()) return cancelledOutcome(toolCall)

        val result = registry.dispatch(context, toolCall)
        // 用户 stop 后网络/工具返回不得再写结果或事件。
        if (isCancelled()) return cancelledOutcome(toolCall)

        val sideEffectKey = sideEffectKeyOf(toolCall, result)
        // Compute bookkeeping outside the write transaction; recheck terminal ownership inside it.
        val completedAt = clock()
        val stored = database.withTransaction {
            if (isCancelled() || dao.findRun(context.userId, context.runId)?.status !in RunCoordinator.ACTIVE_STATUSES) {
                return@withTransaction false
            }
            dao.updateToolExecution(
                runId = context.runId,
                toolCallId = toolCall.callId,
                status = if (result.ok) STATUS_SUCCEEDED else STATUS_FAILED,
                resultJson = result.toJson().toString(),
                sideEffectKey = sideEffectKey,
                now = completedAt,
            ) > 0
        }
        if (!stored) return cancelledOutcome(toolCall)
        state.onToolCall()
        val terminate = if (result.error?.code == ToolErrorCode.INVALID_ARGUMENT) {
            state.onInvalid(invalidSignature(toolCall, result.error))
        } else {
            if (result.ok) state.onSuccess()
            false
        }
        return DispatchOutcome(result = result, terminateToolPath = terminate)
    }

    private fun replay(existing: AiToolExecutionEntity): ToolResult {
        val root = runCatching { Json.parseToJsonElement(existing.resultJson ?: "{}").jsonObject }.getOrNull()
        val ok = (root?.get("ok") as? JsonPrimitive)?.booleanOrNull ?: (existing.status == STATUS_SUCCEEDED)
        return if (ok) {
            ToolResult.success(
                callId = existing.toolCallId,
                toolName = existing.toolName,
                payload = root?.get("payload") as? JsonObject ?: JsonObject(emptyMap()),
                warnings = listOf("REPLAYED"),
            )
        } else {
            ToolResult.failure(
                callId = existing.toolCallId,
                toolName = existing.toolName,
                error = ToolError(
                    ToolErrorCode.INVALID_ARGUMENT,
                    "replayed failed tool result (tool was not re-executed)",
                    retryable = false,
                ),
            )
        }
    }

    private fun sideEffectKeyOf(
        call: com.example.myapplication.domain.agent.ToolCall,
        result: ToolResult,
    ): String? {
        val spec = registry.specOf(call.toolName) ?: return null
        if (spec.readOnly) return null
        val proposalId = (result.payload["proposalId"] as? JsonPrimitive)?.contentOrNull
        return proposalId ?: call.callId
    }

    private fun invalidSignature(
        call: com.example.myapplication.domain.agent.ToolCall,
        error: ToolError,
    ): String = "${call.toolName}:${error.field ?: error.message}"

    private fun cancelledOutcome(
        call: com.example.myapplication.domain.agent.ToolCall,
    ): DispatchOutcome = DispatchOutcome(
        result = ToolResult.failure(
            callId = call.callId,
            toolName = call.toolName,
            error = ToolError(ToolErrorCode.INVALID_ARGUMENT, "run cancelled: tool result not executed", retryable = false),
        ),
        cancelled = true,
    )

    companion object {
        const val MAX_IDENTICAL_INVALID = 2
        const val STATUS_PENDING = "PENDING"
        const val STATUS_SUCCEEDED = "SUCCEEDED"
        const val STATUS_FAILED = "FAILED"
    }
}
