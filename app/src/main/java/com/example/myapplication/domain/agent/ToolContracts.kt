package com.example.myapplication.domain.agent

import com.example.myapplication.data.encyclopedia.EncyclopediaVerifiedExtract
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.SourceEvidence
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Agent 工具契约（第四期 W4-02 / 接口明细 §6/§8/§15 / 详细设计 §3/§4/§13）。
 *
 * 只定义“模型可调用的服务边界”，不实现运行编排：
 *  - [ToolSpec] 携带**有界** JSON Schema（参数类型/长度/枚举/上限），注册表按 schema 拒绝未知字段；
 *  - [ToolRegistry] 只路由到已注册工具；write_source/delete/rebuild/http/shell 等入口
 *    在注册期即被拒绝，未知工具名不会落到通用执行器；
 *  - [ToolResult] 只用结构化错误码表达失败，模型侧的“参数不合法”可纠正；
 *  - 证据（[SourceEvidence]）随结果返回，由回答级证据注册表统一编号，
 *    工具本身不生成 [S#] 别名。
 */
enum class ToolGroup(internal val label: String) {
    /** 只读内容：list_sources / read_outline / read_source / search_knowledge。 */
    READ_ONLY_CONTENT("只读内容"),

    /** 笔记读取：read_note / search_notes（写提议在 [NOTE_PROPOSAL] 组，仍只产生待确认提议）。 */
    NOTE_READING("笔记读取"),

    /** 笔记提议：propose_note_patch（非只读工具，但只创建 PENDING 提议，不直接写正文）。 */
    NOTE_PROPOSAL("笔记提议"),

    /** 测验产物：随堂测草稿/状态（其它工作包实现，此处只保留分组）。 */
    QUIZ_PRODUCT("测验产物"),
}

/** 工具失败的稳定错误码（接口明细 §13 / 详细设计 §13）。 */
enum class ToolErrorCode {
    /** 参数缺失/类型错误/未知字段/超限：模型可纠正后重试。 */
    INVALID_ARGUMENT,

    /** 当前用户对该来源/笔记无权限（撤权、他人资源）。 */
    ACCESS_REVOKED,

    /** 目标不存在（或无权知晓其存在）。 */
    NOT_FOUND,

    /** 请求的 revision 已不是可用的固定版本（模型应重新 list/read 后重试）。 */
    REVISION_MISSING,

    /** 引用/证据无法逐字核验（伪造 quote、编号不存在、locator 对不上）。 */
    EVIDENCE_UNVERIFIED,

    /** 覆盖不完整（workBudget/deadline/ANN 截断），结果仍可用但必须如实标注。 */
    INDEX_PARTIAL,

    /** 当前运行/模型能力不支持该工具（例如本期未启用的 web_search）。 */
    CAPABILITY_UNSUPPORTED,

    /** 随堂测生成/校验/发布失败（模板/题数/评分/证据/覆盖等；含具体 item/field/code）。 */
    QUIZ_INVALID,
}

data class ToolError(
    val code: ToolErrorCode,
    val message: String,
    val retryable: Boolean = false,
    /** 出错参数名（INVALID_ARGUMENT 时给出，便于模型纠正）。 */
    val field: String? = null,
)

/**
 * 工具规格：[parameters] 是 JSON Schema 子集（object 根、additionalProperties=false、
 * 每个属性必须有类型与上限）。[maxArgumentBytes] 是模型参数 JSON 的字节上限。
 */
data class ToolSpec(
    val name: String,
    val group: ToolGroup,
    val description: String,
    val parameters: JsonObject,
    val maxArgumentBytes: Int = MAX_ARGUMENT_BYTES,
    val readOnly: Boolean = true,
) {
    init {
        require(NAME_PATTERN.matches(name)) { "invalid tool name: $name" }
        require(description.isNotBlank() && description.length <= MAX_DESCRIPTION_CHARS) {
            "tool description must be 1..$MAX_DESCRIPTION_CHARS chars"
        }
        require(maxArgumentBytes in MIN_ARGUMENT_BYTES..MAX_ARGUMENT_BYTES_HARD) {
            "maxArgumentBytes must be in $MIN_ARGUMENT_BYTES..$MAX_ARGUMENT_BYTES_HARD"
        }
        // 非只读工具只允许“笔记提议”（只创建待确认提议）与“测验产物”两组；
        // 不存在任何直接写正文/知识源的只读豁免。
        require(readOnly || group == ToolGroup.QUIZ_PRODUCT || group == ToolGroup.NOTE_PROPOSAL) {
            "only note-proposal/quiz-product tools may be non-read-only: $name"
        }
        require(parameters.stringOrNull("type") == "object") {
            "tool $name parameters must be an object schema"
        }
        require(parameters["additionalProperties"]?.jsonBooleanOrNull == false) {
            "tool $name must declare additionalProperties=false"
        }
    }

    companion object {
        /** 默认参数上限（绝大多数工具）：8KiB。 */
        const val MAX_ARGUMENT_BYTES = 8 * 1024

        /**
         * 单工具显式声明时的硬上限：propose_note_patch 的“≤20 段 / 32KiB 新增文本”
         * 经 JSON 转义后需要略大于 32KiB；其余工具仍用默认 [MAX_ARGUMENT_BYTES]。
         */
        const val MAX_ARGUMENT_BYTES_HARD = 40 * 1024
        const val MIN_ARGUMENT_BYTES = 256
        const val MAX_DESCRIPTION_CHARS = 512
        val NAME_PATTERN = Regex("[a-z][a-z0-9_]{1,47}")
    }
}

/** 一次工具调用（callId 由模型协议提供；argumentsJson 是原始字符串，不做自然语言解析）。 */
data class ToolCall(
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
) {
    init {
        require(callId.isNotBlank() && callId.length <= MAX_ID_CHARS) { "invalid callId" }
        require(toolName.isNotBlank() && toolName.length <= MAX_ID_CHARS) { "invalid toolName" }
        require(argumentsJson.length <= MAX_ARGUMENT_TEXT_CHARS) { "argumentsJson is too long" }
    }

    val argumentsHash: String get() = AgentHashing.sha256(argumentsJson)

    companion object {
        const val MAX_ID_CHARS = 128
        const val MAX_ARGUMENT_TEXT_CHARS = 64 * 1024
    }
}

/**
 * 工具执行上下文：全部来自应用侧（run 身份、授权 scope、预算、事件序号），
 * **不接受模型提供 userId/path**。
 */
data class ToolExecutionContext(
    val runId: String,
    val userId: Long,
    val sessionEpoch: Long,
    val requestId: String,
    val eventSeq: Long,
    val deadlineAtMs: Long? = null,
    val remainingTokens: Int = DEFAULT_REMAINING_TOKENS,
) {
    init {
        require(runId.isNotBlank() && runId.length <= ToolCall.MAX_ID_CHARS) { "invalid runId" }
        require(userId > 0) { "userId must be positive" }
        require(sessionEpoch >= 0) { "sessionEpoch must be >= 0" }
        require(requestId.isNotBlank() && requestId.length <= ToolCall.MAX_ID_CHARS) { "invalid requestId" }
        require(eventSeq >= 0) { "eventSeq must be >= 0" }
        require(remainingTokens >= 0) { "remainingTokens must be >= 0" }
        require(deadlineAtMs == null || deadlineAtMs >= 0) { "deadlineAtMs must be >= 0" }
    }

    fun accessContext(): AccessContext = AccessContext(
        userId = userId,
        sessionEpoch = sessionEpoch,
        requestId = requestId,
    )

    companion object {
        const val DEFAULT_REMAINING_TOKENS = 4_096
    }
}

/** 工具执行结果：成功携带 payload/证据/告警；失败携带 [ToolError]。 */
data class ToolResult(
    val callId: String,
    val toolName: String,
    val payload: JsonObject = JsonObject(emptyMap()),
    val evidence: List<SourceEvidence> = emptyList(),
    val warnings: List<String> = emptyList(),
    val error: ToolError? = null,
    val encyclopediaEvidence: List<EncyclopediaVerifiedExtract> = emptyList(),
) {
    val ok: Boolean get() = error == null

    fun toJson(): JsonObject = buildJsonObject {
        put("callId", callId)
        put("tool", toolName)
        put("ok", ok)
        if (error != null) {
            putJsonObject("error") {
                put("code", error.code.name)
                put("message", error.message)
                put("retryable", error.retryable)
                error.field?.let { put("field", it) }
            }
        } else {
            put("payload", payload)
        }
        if (warnings.isNotEmpty()) {
            putJsonArray("warnings") { warnings.forEach { add(JsonPrimitive(it)) } }
        }
        put("evidenceCount", evidence.size + encyclopediaEvidence.size)
    }

    companion object {
        fun success(
            callId: String,
            toolName: String,
            payload: JsonObject = JsonObject(emptyMap()),
            evidence: List<SourceEvidence> = emptyList(),
            warnings: List<String> = emptyList(),
        ): ToolResult = ToolResult(callId, toolName, payload, evidence, warnings)

        fun failure(
            callId: String,
            toolName: String,
            error: ToolError,
        ): ToolResult = ToolResult(callId = callId, toolName = toolName, error = error)

        fun invalidArgument(callId: String, toolName: String, message: String, field: String? = null): ToolResult =
            failure(callId, toolName, ToolError(ToolErrorCode.INVALID_ARGUMENT, message, retryable = true, field = field))
    }
}

/** 一个可注册工具：spec 固定，execute 只读。 */
interface AgentTool {
    val spec: ToolSpec
    suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): ToolResult
}

/**
 * 工具注册表（详细设计 §3）：分三组登记，只允许已知只读工具。
 *
 * - 禁止工具（write_source/delete_source/rebuild_index/arbitrary_http/shell…）在构造期直接拒绝；
 * - 未注册工具名不会路由到任何执行器：已知但本期未实现的工具返回
 *   [ToolErrorCode.CAPABILITY_UNSUPPORTED]，其余返回 [ToolErrorCode.INVALID_ARGUMENT]；
 * - 参数 JSON 先过字节上限、对象类型、未知字段/高危字段与 schema 校验，再交给工具。
 */
class ToolRegistry(tools: List<AgentTool>) {
    private val byName: Map<String, AgentTool>

    init {
        val map = linkedMapOf<String, AgentTool>()
        for (tool in tools) {
            val name = tool.spec.name
            require(name !in FORBIDDEN_TOOL_NAMES) { "forbidden tool cannot be registered: $name" }
            require(map.put(name, tool) == null) { "duplicate tool registration: $name" }
        }
        byName = map.toMap()
    }

    val specs: List<ToolSpec> = byName.values.map { it.spec }

    fun specOf(name: String): ToolSpec? = byName[name]?.spec

    fun names(): List<String> = byName.keys.toList()

    fun contains(name: String): Boolean = byName.containsKey(name)

    suspend fun dispatch(context: ToolExecutionContext, call: ToolCall): ToolResult {
        val tool = byName[call.toolName]
        if (tool == null) {
            return if (call.toolName in KNOWN_UNSUPPORTED_TOOL_NAMES) {
                ToolResult.failure(
                    call.callId,
                    call.toolName,
                    ToolError(
                        ToolErrorCode.CAPABILITY_UNSUPPORTED,
                        "tool ${call.toolName} is not enabled in this build",
                        retryable = false,
                    ),
                )
            } else {
                ToolResult.invalidArgument(
                    call.callId,
                    call.toolName,
                    "unknown tool: ${call.toolName}",
                )
            }
        }
        val spec = tool.spec
        val argumentBytes = call.argumentsJson.toByteArray(StandardCharsets.UTF_8).size
        if (argumentBytes > spec.maxArgumentBytes) {
            return ToolResult.invalidArgument(
                call.callId,
                call.toolName,
                "arguments exceed ${spec.maxArgumentBytes} bytes",
            )
        }
        val arguments = runCatching {
            Json.parseToJsonElement(call.argumentsJson) as? JsonObject
        }.getOrNull() ?: return ToolResult.invalidArgument(
            call.callId,
            call.toolName,
            "arguments must be a JSON object",
        )
        val denied = arguments.keys.firstOrNull { it in DENIED_ARGUMENT_KEYS }
        if (denied != null) {
            return ToolResult.invalidArgument(
                call.callId,
                call.toolName,
                "argument '$denied' is not accepted from the model",
                field = denied,
            )
        }
        val schemaError = ToolSchema.validate(spec, arguments)
        if (schemaError != null) return ToolResult.failure(call.callId, call.toolName, schemaError)
        val result = tool.execute(context, arguments)
        return result.copy(callId = call.callId, toolName = call.toolName)
    }

    companion object {
        /** 绝不存在的入口：即使模型拼出名字也不会路由。 */
        val FORBIDDEN_TOOL_NAMES: Set<String> = setOf(
            "write_source",
            "update_source",
            "delete_source",
            "delete_document",
            "rebuild_index",
            "http_request",
            "arbitrary_http",
            "shell",
            "exec",
            "write_file",
            "admin",
        )

        /** 协议/设计里存在、但本期未启用的工具：明确 CAPABILITY_UNSUPPORTED，不假装可用。 */
        val KNOWN_UNSUPPORTED_TOOL_NAMES: Set<String> = setOf(
            "propose_note_patch",
            "create_quiz_draft",
            "get_quiz_status",
            "web_search",
        )

        /** 只允许来自应用侧的字段；模型传入即 INVALID_ARGUMENT。 */
        val DENIED_ARGUMENT_KEYS: Set<String> = setOf(
            "userId",
            "user_id",
            "uid",
            "ownerId",
            "owner_id",
            "path",
            "filePath",
            "file_path",
            "documentPath",
            "sourcePath",
            "absolutePath",
            "sql",
        )
    }
}

/**
 * 有界 JSON Schema 校验器（只支持工具契约需要的子集）：
 * object/additionalProperties=false/required/properties；
 * string(minLength,maxLength,enum)、integer(minimum,maximum)、boolean、array(maxItems,items)。
 */
object ToolSchema {
    fun validate(spec: ToolSpec, arguments: JsonObject): ToolError? {
        val root = spec.parameters
        val properties = root["properties"]?.let { it as? JsonObject } ?: JsonObject(emptyMap())
        val required = root["required"]?.let { element ->
            (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
        }.orEmpty()
        for (name in required) {
            if (!arguments.containsKey(name)) {
                return ToolError(ToolErrorCode.INVALID_ARGUMENT, "missing required argument: $name", true, name)
            }
        }
        for ((name, value) in arguments) {
            val schema = properties[name] as? JsonObject
                ?: return ToolError(ToolErrorCode.INVALID_ARGUMENT, "unknown argument: $name", true, name)
            typeError(name, schema, value)?.let { return it }
        }
        return null
    }

    private fun typeError(name: String, schema: JsonObject, value: kotlinx.serialization.json.JsonElement): ToolError? {
        return when (schema.stringOrNull("type")) {
            "string" -> {
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return fieldError(name, "must be a string")
                val min = schema.intOrNull("minLength") ?: 0
                val max = schema.intOrNull("maxLength") ?: Int.MAX_VALUE
                if (text.length < min || text.length > max) {
                    return fieldError(name, "length must be in $min..$max")
                }
                val allowed = schema["enum"]?.let { enum ->
                    (enum as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
                }
                if (allowed != null && text !in allowed) return fieldError(name, "must be one of $allowed")
                null
            }

            "integer" -> {
                val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                    ?: return fieldError(name, "must be an integer")
                val min = schema.intOrNull("minimum") ?: Int.MIN_VALUE
                val max = schema.intOrNull("maximum") ?: Int.MAX_VALUE
                if (number < min || number > max) return fieldError(name, "must be in $min..$max")
                null
            }

            "boolean" -> {
                (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                    ?: return fieldError(name, "must be a boolean")
                null
            }

            "array" -> {
                val array = value as? JsonArray ?: return fieldError(name, "must be an array")
                val maxItems = schema.intOrNull("maxItems") ?: Int.MAX_VALUE
                if (array.size > maxItems) return fieldError(name, "must contain at most $maxItems items")
                val itemType = (schema["items"] as? JsonObject)?.stringOrNull("type")
                if (itemType == "string") {
                    val maxLength = (schema["items"] as? JsonObject)?.intOrNull("maxLength") ?: Int.MAX_VALUE
                    for (item in array) {
                        val text = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                            ?: return fieldError(name, "items must be strings")
                        if (text.length > maxLength) return fieldError(name, "item length exceeds $maxLength")
                    }
                }
                null
            }

            else -> null
        }
    }

    private fun fieldError(field: String, message: String) =
        ToolError(ToolErrorCode.INVALID_ARGUMENT, "$field $message", true, field)
}

/** 工具参数读取助手：失败抛 [ToolArgumentException]，由工具转成 INVALID_ARGUMENT。 */
class ToolArgumentException(val field: String, message: String) : IllegalArgumentException(message)

object ToolArgs {
    fun requireString(args: JsonObject, field: String, minLength: Int, maxLength: Int): String {
        val value = (args[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw ToolArgumentException(field, "$field is required")
        if (value.length < minLength || value.length > maxLength) {
            throw ToolArgumentException(field, "$field length must be in $minLength..$maxLength")
        }
        if (value.isBlank()) throw ToolArgumentException(field, "$field must not be blank")
        return value
    }

    fun optionalString(args: JsonObject, field: String, maxLength: Int): String? {
        val element = args[field] ?: return null
        val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw ToolArgumentException(field, "$field must be a string")
        if (value.length > maxLength) throw ToolArgumentException(field, "$field is too long")
        return value.ifBlank { throw ToolArgumentException(field, "$field must not be blank") }
    }

    fun optionalInt(args: JsonObject, field: String, min: Int, max: Int): Int? {
        val element = args[field] ?: return null
        val value = (element as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: throw ToolArgumentException(field, "$field must be an integer")
        if (value < min || value > max) throw ToolArgumentException(field, "$field must be in $min..$max")
        return value
    }

    fun optionalStringList(args: JsonObject, field: String, maxItems: Int, maxItemLength: Int): List<String>? {
        val element = args[field] ?: return null
        val array = element as? JsonArray ?: throw ToolArgumentException(field, "$field must be an array")
        if (array.size > maxItems) throw ToolArgumentException(field, "$field allows at most $maxItems items")
        return array.map { item ->
            val text = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw ToolArgumentException(field, "$field items must be strings")
            if (text.isBlank() || text.length > maxItemLength) {
                throw ToolArgumentException(field, "$field contains an invalid item")
            }
            text
        }
    }
}

/** 工具层使用的稳定哈希（参数指纹 / 回执 / 证据身份）。 */
object AgentHashing {
    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** JSON helper：构造有界 schema 时复用，避免手写字符串。 */
internal fun boundedObject(
    required: List<String>,
    properties: Map<String, JsonObject>,
): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    putJsonObject("properties") {
        properties.forEach { (name, schema) -> put(name, schema) }
    }
}

internal fun stringSchema(minLength: Int, maxLength: Int): JsonObject = buildJsonObject {
    put("type", "string")
    put("minLength", minLength)
    put("maxLength", maxLength)
}

internal fun integerSchema(minimum: Int, maximum: Int): JsonObject = buildJsonObject {
    put("type", "integer")
    put("minimum", minimum)
    put("maximum", maximum)
}

internal fun stringArraySchema(maxItems: Int, maxItemLength: Int): JsonObject = buildJsonObject {
    put("type", "array")
    put("maxItems", maxItems)
    putJsonObject("items") {
        put("type", "string")
        put("maxLength", maxItemLength)
    }
}

internal fun JsonObject.stringOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.intOrNull(name: String): Int? =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

internal val kotlinx.serialization.json.JsonElement.jsonBooleanOrNull: Boolean?
    get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

@Suppress("unused")
internal fun emptyArray(): JsonArray = buildJsonArray { }

