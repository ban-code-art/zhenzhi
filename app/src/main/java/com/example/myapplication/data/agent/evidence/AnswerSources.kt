package com.example.myapplication.data.agent.evidence

import com.example.myapplication.domain.reader.ReaderLocatorCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * assistant 消息的持久来源快照（W4-02 / 详细设计 §13、§21；接口明细 §15；FX-31）。
 *
 * 设计立场：
 *  - `[S1]` 只是**本次回答** registry 里的临时别名，不能当长期引用 id；
 *    长期可解析的是 [referenceId]（reader_source_references.id，按 userId 授权）；
 *  - quote/locator/contentRevision 都是应用侧经固定 revision 核验后的快照，
 *    因此源被删除/改版后仍能展示历史 quote（§17 SourceUnavailable），只是不可精确跳转；
 *  - 快照只作展示与回链，不参与权限判断：打开原文必须再走 SourceNavigator.lookup。
 */
data class AgentSourceRecord(
    /** 本轮回答内的展示编号（S1、S2…），重启后仅用于历史展示，不作长期 id。 */
    val ordinal: Int,
    val citation: String,
    /** 持久来源引用 id（SourceNavigator.lookup 的入参）。 */
    val referenceId: String,
    val documentId: String,
    /** 书名快照；来源已删时可能只剩快照，允许为空。 */
    val documentTitle: String?,
    val sectionId: String?,
    /** 已核验原文片段；来源失效后仍保留（不丢 quote）。 */
    val quote: String,
    /** 应用侧核验过的 ReaderLocator JSON；为空表示无法精确跳转。 */
    val locatorJson: String?,
    val contentRevision: String,
    val quoteSha256: String?,
)

/** 回答级 registry 的注册结果 → 持久快照（字段全部来自已核验证据）。 */
fun EvidenceRegistry.RegisteredSource.toAnswerSource(): AgentSourceRecord = AgentSourceRecord(
    ordinal = ordinal,
    citation = citation,
    referenceId = referenceId,
    documentId = documentId,
    documentTitle = sourceTitle.takeIf { it.isNotBlank() },
    sectionId = sectionId.takeIf { it.isNotBlank() },
    quote = quote,
    locatorJson = rawLocatorJson ?: evidence?.let { runCatching { ReaderLocatorCodec.encode(it.locator) }.getOrNull() },
    contentRevision = contentRevision,
    quoteSha256 = quoteSha256,
)

/**
 * `ai_messages.sources_json` 的稳定编解码（详细设计 §21：不扫 message JSON 猜引用）。
 *
 * 结构：
 * ~~~json
 * {"schemaVersion":1,"sources":[{"ordinal":1,"citation":"S1","referenceId":"source-…",
 *   "documentId":"doc-A","documentTitle":"…","sectionId":"sec-A","quote":"已核验原文",
 *   "locatorJson":"{…}","contentRevision":"sha","quoteSha256":"sha"}]}
 * ~~~
 * 解码规则（坏数据不冒充真实来源）：
 *  - 未知 schemaVersion / 非 JSON → 空列表（未来版本不猜字段）；
 *  - 单条缺少 citation/quote/referenceId/documentId/contentRevision → 丢弃该条，保留其余；
 *  - citation 必须是应用分配的 `S<正整数>` 形态，防止模型或旧数据塞入任意别名。
 */
object AgentSourcesCodec {
    const val SCHEMA_VERSION = 1

    /** 单条回答的来源上限：与工具调用预算同量级，避免异常数据撑爆消息行。 */
    const val MAX_SOURCES = 64

    const val MAX_QUOTE_CHARS = 8_192
    const val MAX_TITLE_CHARS = 240
    const val MAX_LOCATOR_CHARS = 4_096

    private val CITATION_PATTERN = Regex("S[1-9][0-9]{0,3}")

    fun encode(sources: List<AgentSourceRecord>): String = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        putJsonArray("sources") {
            sources.take(MAX_SOURCES).forEach { record ->
                addJsonObject {
                    put("ordinal", record.ordinal)
                    put("citation", record.citation)
                    put("referenceId", record.referenceId)
                    put("documentId", record.documentId)
                    put("documentTitle", record.documentTitle)
                    put("sectionId", record.sectionId)
                    put("quote", record.quote)
                    put("locatorJson", record.locatorJson)
                    put("contentRevision", record.contentRevision)
                    put("quoteSha256", record.quoteSha256)
                }
            }
        }
    }.toString()

    /** 容错解码：任何结构问题都不抛异常，只返回可安全展示的来源。 */
    fun decode(json: String?): List<AgentSourceRecord> {
        if (json.isNullOrBlank()) return emptyList()
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return emptyList()
        if (root.sourceInt("schemaVersion") != SCHEMA_VERSION) return emptyList()
        val array = root["sources"] as? JsonArray ?: return emptyList()
        return array.take(MAX_SOURCES).mapNotNull { element ->
            decodeRecord(element as? JsonObject ?: return@mapNotNull null)
        }
    }

    private fun decodeRecord(root: JsonObject): AgentSourceRecord? {
        val citation = root.sourceString("citation")?.trim().orEmpty()
        if (!CITATION_PATTERN.matches(citation)) return null
        val ordinal = root.sourceInt("ordinal") ?: citation.removePrefix("S").toIntOrNull() ?: return null
        if (ordinal <= 0) return null
        val referenceId = root.sourceString("referenceId")?.trim().orEmpty()
        if (referenceId.isBlank()) return null
        val documentId = root.sourceString("documentId")?.trim().orEmpty()
        if (documentId.isBlank()) return null
        val contentRevision = root.sourceString("contentRevision")?.trim().orEmpty()
        if (contentRevision.isBlank()) return null
        val quote = root.sourceString("quote")?.takeIf { it.isNotBlank() } ?: return null
        return AgentSourceRecord(
            ordinal = ordinal,
            citation = citation,
            referenceId = referenceId,
            documentId = documentId,
            documentTitle = root.sourceString("documentTitle")
                ?.takeIf { it.isNotBlank() }
                ?.take(MAX_TITLE_CHARS),
            sectionId = root.sourceString("sectionId")?.takeIf { it.isNotBlank() },
            quote = quote.take(MAX_QUOTE_CHARS),
            locatorJson = root.sourceString("locatorJson")
                ?.takeIf { it.isNotBlank() }
                ?.take(MAX_LOCATOR_CHARS),
            contentRevision = contentRevision,
            quoteSha256 = root.sourceString("quoteSha256")?.takeIf { it.isNotBlank() },
        )
    }

    private fun JsonObject.sourceString(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.sourceInt(name: String): Int? =
        (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
}
