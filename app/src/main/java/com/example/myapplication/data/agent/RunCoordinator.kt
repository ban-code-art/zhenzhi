package com.example.myapplication.data.agent

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.example.myapplication.data.agent.evidence.AgentSourcesCodec
import com.example.myapplication.data.agent.evidence.EvidenceRegistry
import com.example.myapplication.data.encyclopedia.EncyclopediaVerifiedExtract
import com.example.myapplication.data.ai.adapters.ChatAdapter
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.AiRunEntity
import com.example.myapplication.data.local.entity.AiRunEventEntity
import com.example.myapplication.domain.ai.ChatMessage
import com.example.myapplication.domain.ai.RunProfileSnapshot
import com.example.myapplication.domain.agent.ToolSpec
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.ScopeRequest
import com.example.myapplication.domain.reader.ScopeSnapshot
import com.example.myapplication.domain.ai.RunProfileSnapshotCodec
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 运行编排与生命周期（W4-04 / 详细设计 §2、§12、§20；04 接口 §8）。
 *
 * - 应用 scope 持有 run，UI 只订阅 runId；closeUI 不停止；
 * - 每个会话同一时刻只允许一个活跃 run：DB `active_slot` 唯一约束兜底，不靠 UI disabled；
 * - 同 (user, requestKey) 幂等返回 EXISTING；同会话已有活跃 run 返回 BUSY(activeRunId)；
 * - 事件持久 seq 递增，重订阅按 afterSeq 恢复；stop 幂等；进程启动把全部非终态 run
 *   （[ACTIVE_STATUSES]：PREPARING/GENERATING/TOOL_RUNNING/RUNNING）标 INTERRUPTED，
 *   且不自动重放写工具；
 * - 状态机（详细设计 §2）：GENERATING → TOOL_RUNNING → GENERATING 由工具事件落库副作用驱动，
 *   终态只由 [finish]/[stop] 写入。
 */
class RunCoordinator(
    private val database: AppDatabase,
    private val conversations: ConversationRepository,
    private val runtimeFactory: RuntimeFactory,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun interface RuntimeFactory {
        suspend fun create(userId: Long, request: RunScopeRequest): PreparedRuntime
    }

    data class RunScopeRequest(
        val conversationId: String,
        val scopeRequest: ScopeRequest,
        val readingContext: ReadingContext?,
    )

    data class PreparedRuntime(
        val runtime: AgentRuntime,
        val snapshot: RunProfileSnapshot,
        val adapter: ChatAdapter,
        val secretResolver: suspend () -> SecretResolution,
        val tools: List<ToolSpec>,
        val contextWindowTokens: Int?,
        val readingContext: ReadingContext?,
        val history: List<ChatMessage>,
        val scope: ScopeSnapshot,
        /**
         * 回答级证据注册表（W4-02）：生产由容器工厂按 run 新建；为 null 时
         * 运行循环不注册证据、也不写 sources_json（保持旧测试/受限运行的行为）。
         */
        val evidenceRegistry: EvidenceRegistry? = null,
        val encyclopediaEvidence: List<EncyclopediaVerifiedExtract> = emptyList(),
    )

    sealed interface StartOutcome {
        data class Started(val runId: String) : StartOutcome
        data class Existing(val runId: String) : StartOutcome
        data class Busy(val activeRunId: String) : StartOutcome
        data class Rejected(val code: String) : StartOutcome
    }

    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    suspend fun start(
        ctx: AccessContext,
        conversationId: String,
        userMessage: String,
        scopeRequest: ScopeRequest,
        readingContext: ReadingContext? = null,
        requestKey: String,
        limits: RuntimeLimits = RuntimeLimits(),
    ): StartOutcome {
        if (ctx.userId <= 0) return StartOutcome.Rejected("NOT_SIGNED_IN")
        if (userMessage.isBlank()) return StartOutcome.Rejected("EMPTY_MESSAGE")
        if (requestKey.isBlank()) return StartOutcome.Rejected("INVALID_REQUEST_KEY")
        val conversation = conversations.findConversation(ctx.userId, conversationId)
            ?: return StartOutcome.Rejected("CONVERSATION_NOT_FOUND")

        val dao = database.aiRunDao()
        dao.findRunByRequestKey(ctx.userId, requestKey)?.let { return StartOutcome.Existing(it.id) }
        dao.findActiveRun(conversationId)?.let { return StartOutcome.Busy(it.id) }

        val prepared = try {
            runtimeFactory.create(ctx.userId, RunScopeRequest(conversationId, scopeRequest, readingContext))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return StartOutcome.Rejected(error.message?.substringBefore(':') ?: "RUNTIME_UNAVAILABLE")
        }

        val now = clock()
        val runId = "run-${UUID.randomUUID()}"
        val run = AiRunEntity(
            id = runId,
            userId = ctx.userId,
            conversationId = conversationId,
            requestKey = requestKey,
            configRevision = prepared.snapshot.configRevision,
            profileSnapshotJson = RunProfileSnapshotCodec.encode(prepared.snapshot),
            scopeSnapshotJson = encodeScope(prepared.scope),
            status = STATUS_PREPARING,
            activeSlot = 1,
            rounds = 0,
            tokenUsage = 0,
            deadlineAt = now + limits.deadlineMs,
            lastEventSeq = 0,
            createdAt = now,
            updatedAt = now,
        )
        // withTransaction may commit before resuming this caller. Explicit prepare cancellation
        // must also release a run admitted in that interval, without stopping an Existing/Busy run.
        var ownedRunId: String? = null
        try {
            val admitted = try {
                database.withTransaction {
                    if (conversations.findConversation(ctx.userId, conversationId) == null) {
                        return@withTransaction StartOutcome.Rejected("CONVERSATION_NOT_FOUND")
                    }
                    dao.findRunByRequestKey(ctx.userId, requestKey)?.let {
                        return@withTransaction StartOutcome.Existing(it.id)
                    }
                    dao.findActiveRun(conversationId)?.let {
                        return@withTransaction StartOutcome.Busy(it.id)
                    }
                    dao.insertRun(run)
                    ownedRunId = runId
                    val message = conversations.appendMessage(
                        ctx.userId, conversationId, ChatMessage.user(userMessage),
                        ConversationRepository.STATUS_COMPLETE,
                    )
                    appendEvent(runId, EVENT_RUN_STARTED, null, """{"requestKey":${JsonPrimitive(requestKey)}}""")
                    messageUpdated(runId, message.id)
                    StartOutcome.Started(runId)
                }
            } catch (conflict: SQLiteConstraintException) {
                dao.findRunByRequestKey(ctx.userId, requestKey)?.let { return StartOutcome.Existing(it.id) }
                dao.findActiveRun(conversationId)?.let { return StartOutcome.Busy(it.id) }
                return StartOutcome.Rejected("RUN_CONFLICT")
            }
            if (admitted !is StartOutcome.Started) return admitted
            val job = scope.launch(start = CoroutineStart.LAZY) {
                execute(ctx, runId, prepared, limits, userMessage, conversationId)
            }
            jobs[runId] = job
            job.invokeOnCompletion { jobs.remove(runId, job) }
            job.start()
            return StartOutcome.Started(runId)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) {
                ownedRunId?.let { stop(ctx, it) }
            }
            throw cancelled
        }
    }

    private suspend fun execute(
        ctx: AccessContext,
        runId: String,
        prepared: PreparedRuntime,
        limits: RuntimeLimits,
        userMessage: String,
        conversationId: String,
    ) {
        val generating = database.withTransaction {
            val run = database.aiRunDao().findRun(ctx.userId, runId) ?: return@withTransaction false
            if (run.status != STATUS_PREPARING) return@withTransaction false
            updateRunStatus(runId, ctx.userId, STATUS_GENERATING, activeSlot = 1, rounds = 0, tokenUsage = 0)
            appendEvent(runId, EVENT_GENERATING, null, "{}")
            true
        }
        if (!generating) return
        val sink = DatabaseSink(ctx.userId, runId, conversationId)
        val request = AgentRunRequest(
            runId = runId,
            userId = ctx.userId,
            sessionEpoch = ctx.sessionEpoch,
            requestId = ctx.requestId,
            snapshot = prepared.snapshot,
            adapter = prepared.adapter,
            secretResolver = prepared.secretResolver,
            tools = prepared.tools,
            history = prepared.history,
            userMessage = ChatMessage.user(userMessage),
            readingContext = prepared.readingContext,
            evidenceText = emptyList(),
            contextWindowTokens = prepared.contextWindowTokens,
            limits = limits,
            evidenceRegistry = prepared.evidenceRegistry,
            encyclopediaEvidence = prepared.encyclopediaEvidence,
        )
        val result = try {
            prepared.runtime.run(request, sink)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) {
                finish(cancelledRun = true, runId = runId, userId = ctx.userId, result = null, sink = sink)
            }
            throw cancelled
        } catch (error: Exception) {
            AgentRunResult(AgentRuntime.STATUS_ERROR, "RUNTIME_FAILURE", 0, 0, 0, null, "RUNTIME_FAILURE")
        }
        finish(cancelledRun = false, runId = runId, userId = ctx.userId, result = result, sink = sink)
    }

    private suspend fun finish(
        cancelledRun: Boolean,
        runId: String,
        userId: Long,
        result: AgentRunResult?,
        sink: DatabaseSink?,
    ) = database.withTransaction {
        val dao = database.aiRunDao()
        val current = dao.findRun(userId, runId) ?: return@withTransaction
        if (current.status !in ACTIVE_STATUSES) return@withTransaction
        val status = if (cancelledRun) STATUS_CANCELLED else when (result?.status) {
            AgentRuntime.STATUS_COMPLETED -> STATUS_COMPLETED
            AgentRuntime.STATUS_CANCELLED -> STATUS_CANCELLED
            else -> STATUS_ERROR
        }
        // 先落来源再改 run 状态：UI 一旦看到终态就能读到 sources_json（不允许“已完成但没来源”的中间态）。
        val sources = result?.sources.orEmpty()
        if (sources.isNotEmpty() && sink != null) {
            sink.persistSources(AgentSourcesCodec.encode(sources))
        }
        updateRunStatus(
            runId = runId,
            userId = userId,
            status = status,
            activeSlot = null,
            rounds = result?.rounds ?: current.rounds,
            tokenUsage = result?.tokenUsage ?: current.tokenUsage,
        )
        if (status == STATUS_COMPLETED) {
            appendEvent(runId, EVENT_COMPLETED, null, """{"stopReason":"${result?.stopReason ?: "STOP"}"}""")
        } else if (status == STATUS_CANCELLED) {
            appendEvent(runId, EVENT_CANCELLED, null, "{}")
        } else {
            appendEvent(runId, EVENT_ERROR, null, """{"code":"${result?.errorCode ?: "UNKNOWN"}"}""")
        }
    }

    /** 用户停止：取消网络与后续工具，幂等；已终态返回 false。 */
    suspend fun stop(ctx: AccessContext, runId: String): Boolean {
        val stopped = database.withTransaction {
            val run = database.aiRunDao().findRun(ctx.userId, runId) ?: return@withTransaction false
            if (run.status !in ACTIVE_STATUSES) return@withTransaction false
            updateRunStatus(runId, ctx.userId, STATUS_CANCELLED, activeSlot = null, rounds = run.rounds, tokenUsage = run.tokenUsage)
            appendEvent(runId, EVENT_CANCELLED, null, """{"reason":"USER_STOP"}""")
            true
        }
        if (stopped) jobs.remove(runId)?.cancel()
        return stopped
    }

    /** UI 关闭只解除订阅，不停止 run（设计 §2）。 */
    fun closeUI(@Suppress("UNUSED_PARAMETER") runId: String) = Unit

    suspend fun findRun(userId: Long, runId: String): AiRunEntity? = database.aiRunDao().findRun(userId, runId)

    /** Latest run of an owned conversation, including terminal runs for UI reopening. */
    suspend fun latestRun(userId: Long, conversationId: String): AiRunEntity? = database.withTransaction {
        if (conversations.findConversation(userId, conversationId) == null) return@withTransaction null
        val id = database.openHelper.readableDatabase.query(
            "SELECT id FROM ai_runs WHERE user_id = ? AND conversation_id = ? ORDER BY created_at DESC, rowid DESC LIMIT 1",
            arrayOf<Any>(userId, conversationId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        id?.let { database.aiRunDao().findRun(userId, it) }
    }

    suspend fun eventsSince(userId: Long, runId: String, afterSeq: Long = 0): List<AiRunEventEntity> {
        val run = database.aiRunDao().findRun(userId, runId) ?: return emptyList()
        return database.aiRunDao().findRunEvents(run.id).filter { it.seq > afterSeq }
    }

    /** 订阅持久事件（按 seq 恢复）；run 终态且无新事件时结束。 */
    fun observeEvents(userId: Long, runId: String, afterSeq: Long = 0): Flow<AiRunEventEntity> = flow {
        var cursor = afterSeq
        while (true) {
            val run = database.aiRunDao().findRun(userId, runId) ?: return@flow
            val events = database.aiRunDao().findRunEvents(runId).filter { it.seq > cursor }
            for (event in events) {
                cursor = event.seq
                emit(event)
            }
            if (events.isEmpty() && run.status !in ACTIVE_STATUSES) return@flow
            delay(EVENT_POLL_MS)
        }
    }

    /**
     * 启动恢复（G-05；详细设计 §2）：本进程尚无活跃 run，把所有遗留的非终态 run
     * （[ACTIVE_STATUSES] 全量：PREPARING / GENERATING / TOOL_RUNNING / RUNNING）标 INTERRUPTED
     * 并释放 active_slot。
     *
     * 之前只覆盖 `status = 'RUNNING'`，但 CHAT 运行实际写入的是 PREPARING / GENERATING
     * （TOOL_RUNNING 修复后同样可达），真被杀/重启后会遗留 active_slot=1 却不被恢复，
     * 该会话后续 start() 永远返回 Busy。这里改为按 [ACTIVE_STATUSES] 全量覆盖。
     *
     * 边界（必须保持）：
     *  - 只改 `status / active_slot / updated_at`，不触碰消息、工具执行记录与来源快照：
     *    绝不重放写工具、不重新计费；
     *  - 顺序沿用既有事件语义：先在一个事务内 UPDATE 全部命中行，再逐条追加 RUN_INTERRUPTED；
     *  - [appendEvent] 内部自带 withTransaction，Room/SQLite 事务按引用计数嵌套提交，
     *    既有 RUNNING 路径已验证同一写法，不会半提交，也不需要绕过 DAO 手写事件行。
     */
    suspend fun recoverOnStartup(): Int = database.withTransaction {
        val now = clock()
        val statuses = ACTIVE_STATUSES.toTypedArray()
        val placeholders = statuses.joinToString(", ") { "?" }
        val interruptedIds = mutableListOf<String>()
        database.openHelper.writableDatabase
            .query("SELECT id FROM ai_runs WHERE status IN ($placeholders)", statuses)
            .use { cursor ->
                while (cursor.moveToNext()) interruptedIds += cursor.getString(0)
            }
        if (interruptedIds.isEmpty()) return@withTransaction 0
        database.openHelper.writableDatabase.execSQL(
            "UPDATE ai_runs SET status = 'INTERRUPTED', active_slot = NULL, updated_at = ? " +
                "WHERE status IN ($placeholders)",
            arrayOf<Any?>(now, *statuses),
        )
        for (runId in interruptedIds) {
            appendEvent(runId, EVENT_INTERRUPTED, null, """{"reason":"PROCESS_RESTART"}""")
        }
        interruptedIds.size
    }

    private suspend fun updateRunStatus(
        runId: String,
        userId: Long,
        status: String,
        activeSlot: Int?,
        rounds: Int,
        tokenUsage: Long,
    ) {
        val dao = database.aiRunDao()
        val current = dao.findRun(userId, runId) ?: return
        dao.updateRunState(
            userId = userId,
            runId = runId,
            status = status,
            activeSlot = activeSlot,
            rounds = rounds,
            tokenUsage = tokenUsage,
            lastEventSeq = current.lastEventSeq,
            now = clock(),
        )
    }

    /**
     * 工具阶段状态迁移（详细设计 §2：GENERATING ↔ TOOL_RUNNING）。
     *
     * 只允许从 [from] 迁移：run 已被 [stop]/[finish]/启动恢复置成终态时，迟到的事件
     * 不得把 run 复活、也不得重新占用 active_slot（唯一约束兜底，终态由上述路径独占写入）。
     * rounds / token_usage / last_event_seq 保持当前值；active_slot 保持原值
     * （生产活跃 run 恒为 1，这里不新占槽位、也不释放槽位）。
     */
    private suspend fun moveToolPhase(
        userId: Long,
        runId: String,
        from: Set<String>,
        to: String,
    ) {
        val dao = database.aiRunDao()
        val current = dao.findRun(userId, runId) ?: return
        if (current.status !in from || current.status == to) return
        dao.updateRunState(
            userId = userId,
            runId = runId,
            status = to,
            activeSlot = current.activeSlot,
            rounds = current.rounds,
            tokenUsage = current.tokenUsage,
            lastEventSeq = current.lastEventSeq,
            now = clock(),
        )
    }

    private suspend fun messageUpdated(runId: String, messageId: String) {
        appendEvent(runId, EVENT_MESSAGE_UPDATED, null, buildJsonObject { put("messageId", messageId) }.toString())
    }

    private suspend fun appendEvent(runId: String, type: String, toolCallId: String?, payloadJson: String) {
        database.withTransaction {
            val dao = database.aiRunDao()
            val seq = dao.maxEventSeq(runId) + 1
            dao.insertRunEvent(
                AiRunEventEntity(
                    runId = runId,
                    seq = seq,
                    type = type,
                    toolCallId = toolCallId,
                    payloadJson = payloadJson,
                    createdAt = clock(),
                ),
            )
            database.openHelper.writableDatabase.execSQL(
                "UPDATE ai_runs SET last_event_seq = ?, updated_at = ? WHERE id = ?",
                arrayOf(seq, clock(), runId),
            )
        }
    }

    private fun encodeScope(scope: ScopeSnapshot): String = buildJsonObject {
        put("userId", scope.userId)
        put("selection", scope.selection.name)
        putJsonArray("documents") {
            scope.documents.forEach { document ->
                add(
                    buildJsonObject {
                        put("documentId", document.documentId)
                        put("knowledgeDocumentId", document.knowledgeDocumentId)
                        put("generation", document.generation)
                        put("sourceRevision", document.sourceRevision)
                        put("accessEpoch", document.accessEpoch)
                    },
                )
            }
        }
    }.toString()

    /** 幂等的运行内 sink：seq 事件落库；assistant 消息按 checkpoint 更新同一行。 */
    private inner class DatabaseSink(
        private val userId: Long,
        private val runId: String,
        private val conversationId: String,
    ) : AgentRuntimeSink {
        private var assistantMessageId: String? = null

        /** 最近一次写入的 assistant 行（跨工具段保留）：终态来源快照写在这一行上。 */
        private var lastAssistantMessageId: String? = null

        override suspend fun onEvent(type: String, toolCallId: String?, payloadJson: String) = database.withTransaction {
            if (!active()) return@withTransaction
            // Runtime diagnostics are not coordinator terminal events: observers must wait for finish().
            val persistedType = if (type == AgentRuntime.EVENT_RUN_ERROR) EVENT_RUNTIME_ERROR else type
            appendEvent(runId, persistedType, toolCallId, payloadJson)
            // 缺陷 2 修复（详细设计 §2 状态机）：工具事件落库副作用驱动
            // GENERATING → TOOL_RUNNING → GENERATING，不改 AgentRuntime 的事件语义。
            // 只在本 run 仍处于“生成中”时迁移；终态（COMPLETED/ERROR/CANCELLED/INTERRUPTED）
            // 一律忽略，避免 stop()/finish() 之后的迟到事件把 run 复活。
            when (type) {
                AgentRuntime.EVENT_TOOL_CALL -> moveToolPhase(
                    userId = userId,
                    runId = runId,
                    from = setOf(STATUS_GENERATING, STATUS_TOOL_RUNNING),
                    to = STATUS_TOOL_RUNNING,
                )

                AgentRuntime.EVENT_TOOL_RESULT, AgentRuntime.EVENT_TOOL_ERROR -> moveToolPhase(
                    userId = userId,
                    runId = runId,
                    from = setOf(STATUS_TOOL_RUNNING, STATUS_GENERATING),
                    to = STATUS_GENERATING,
                )
            }
            Unit
        }

        override suspend fun onAssistantMessage(message: ChatMessage, status: String) = database.withTransaction {
            if (!active()) return@withTransaction
            val existingId = assistantMessageId
            if (existingId == null) {
                val entity = conversations.appendMessage(
                    userId = userId,
                    conversationId = conversationId,
                    message = message,
                    status = status,
                )
                assistantMessageId = entity.id
                lastAssistantMessageId = entity.id
            } else {
                conversations.updateMessage(existingId, message, status)
                lastAssistantMessageId = existingId
            }
            messageUpdated(runId, assistantMessageId ?: return@withTransaction)
        }

        override suspend fun onToolMessage(message: ChatMessage) = database.withTransaction {
            if (!active()) return@withTransaction
            val entity = conversations.appendMessage(
                userId = userId,
                conversationId = conversationId,
                message = message,
                status = ConversationRepository.STATUS_COMPLETE,
            )
            messageUpdated(runId, entity.id)
        }

        override suspend fun onAssistantTurnFinished() {
            assistantMessageId = null
        }

        /**
         * 把本轮来源快照写到最近的 assistant 消息（W4-02：`ai_messages.sources_json`）。
         * 只更新 sources_json，不重写 content/message_json；没有 assistant 行时不造消息。
         */
        suspend fun persistSources(sourcesJson: String) = database.withTransaction {
            if (!active()) return@withTransaction
            val messageId = lastAssistantMessageId ?: return@withTransaction
            conversations.updateMessageSources(messageId, sourcesJson)
            messageUpdated(runId, messageId)
        }

        private suspend fun active(): Boolean =
            database.aiRunDao().findRun(userId, runId)?.status in ACTIVE_STATUSES

        override suspend fun isCancelled(): Boolean {
            val current = database.aiRunDao().findRun(userId, runId) ?: return true
            return current.status !in ACTIVE_STATUSES
        }
    }

    companion object {
        const val STATUS_PREPARING = "PREPARING"
        const val STATUS_GENERATING = "GENERATING"
        const val STATUS_TOOL_RUNNING = "TOOL_RUNNING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_ERROR = "ERROR"
        const val STATUS_CANCELLED = "CANCELLED"
        const val STATUS_INTERRUPTED = "INTERRUPTED"
        val ACTIVE_STATUSES = setOf(STATUS_PREPARING, STATUS_GENERATING, STATUS_TOOL_RUNNING, "RUNNING")

        const val EVENT_RUNTIME_ERROR = "RUNTIME_ERROR"
        const val EVENT_MESSAGE_UPDATED = "MESSAGE_UPDATED"
        const val EVENT_RUN_STARTED = "RUN_STARTED"
        const val EVENT_GENERATING = "GENERATING"
        const val EVENT_COMPLETED = "RUN_COMPLETED"
        const val EVENT_ERROR = "RUN_ERROR"
        const val EVENT_CANCELLED = "RUN_CANCELLED"
        const val EVENT_INTERRUPTED = "RUN_INTERRUPTED"
        const val EVENT_POLL_MS = 100L
    }
}
