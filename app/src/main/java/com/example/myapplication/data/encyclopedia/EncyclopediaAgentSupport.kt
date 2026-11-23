package com.example.myapplication.data.encyclopedia

import com.example.myapplication.domain.agent.AgentHashing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class EncyclopediaEntryKind(val wire: String, val label: String) {
    ACUPOINT("acupoint", "穴位"),
    SYMPTOM("symptom", "病症");

    companion object {
        fun fromWire(raw: String?): EncyclopediaEntryKind? = entries.firstOrNull { it.wire == raw }
    }
}

/**
 * 经学百科词条的独立 AI 启动上下文：不复用或伪造书库 documentId。
 * 仅携带用户显式打开并查看的当前词条分段摘要（及用于关闭后原位恢复的检查点）。
 */
data class EncyclopediaAgentLaunchContext(
    val kind: EncyclopediaEntryKind,
    val entryId: String,
    val contentRevision: String,
    val title: String,
    val selectedSectionId: String,
    val selectedSectionTitle: String,
    val selectedQuote: String,
    val detailTab: String? = null,
    val openClues: List<Int> = emptyList(),
    val returnCheckpointJson: String? = null,
) {
    val namespaceDocumentKey: String
        get() = "$NAMESPACE_PREFIX:${kind.wire}:$entryId"

    fun toLocator(): EncyclopediaSourceLocator = EncyclopediaSourceLocator(
        kind = kind,
        entryId = entryId,
        sectionId = selectedSectionId,
        sectionTitle = selectedSectionTitle,
        contentRevision = contentRevision,
        detailTab = detailTab,
        openClues = openClues.distinct().sorted(),
    )

    fun toJson(): JsonObject = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        put("kind", kind.wire)
        put("entryId", entryId)
        put("contentRevision", contentRevision)
        put("title", title)
        put("selectedSectionId", selectedSectionId)
        put("selectedSectionTitle", selectedSectionTitle)
        put("selectedQuote", selectedQuote.take(MAX_QUOTE_CHARS))
        detailTab?.takeIf(String::isNotBlank)?.let { put("detailTab", it) }
        if (openClues.isNotEmpty()) {
            put("openClues", buildJsonArray { openClues.distinct().sorted().forEach { add(JsonPrimitive(it)) } })
        }
        returnCheckpointJson?.takeIf(String::isNotBlank)?.let { put("returnCheckpointJson", it) }
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        const val SCHEMA_VERSION = 1
        const val NAMESPACE_PREFIX = "meridian-encyclopedia"
        const val MAX_QUOTE_CHARS = 2_000

        fun fromJson(obj: JsonObject?): EncyclopediaAgentLaunchContext? {
            if (obj == null) return null
            if (obj["schemaVersion"]?.jsonPrimitive?.intOrNull != SCHEMA_VERSION) return null
            val kind = EncyclopediaEntryKind.fromWire(obj["kind"]?.jsonPrimitive?.contentOrNull) ?: return null
            val entryId = obj["entryId"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            val rev = obj["contentRevision"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            val secId = obj["selectedSectionId"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            val secTitle = obj["selectedSectionTitle"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            val quote = obj["selectedQuote"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            val detailTab = obj["detailTab"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            val openClues = (obj["openClues"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.intOrNull }
                ?.filter { it >= 0 }
                ?.distinct()
                ?.sorted()
                .orEmpty()
            val checkpoint = obj["returnCheckpointJson"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            return EncyclopediaAgentLaunchContext(
                kind = kind,
                entryId = entryId,
                contentRevision = rev,
                title = title,
                selectedSectionId = secId,
                selectedSectionTitle = secTitle,
                selectedQuote = quote.take(MAX_QUOTE_CHARS),
                detailTab = detailTab,
                openClues = openClues,
                returnCheckpointJson = checkpoint,
            )
        }

        fun fromJsonString(raw: String?): EncyclopediaAgentLaunchContext? {
            if (raw.isNullOrBlank()) return null
            val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
            return fromJson(obj)
        }
    }
}

/** 独立百科来源定位器：存入 `reader_source_references.locator_json` 及 AI `sources_json`。 */
data class EncyclopediaSourceLocator(
    val kind: EncyclopediaEntryKind,
    val entryId: String,
    val sectionId: String,
    val sectionTitle: String,
    val contentRevision: String,
    val detailTab: String? = null,
    val openClues: List<Int> = emptyList(),
) {
    val namespaceDocumentKey: String
        get() = "${EncyclopediaAgentLaunchContext.NAMESPACE_PREFIX}:${kind.wire}:$entryId"

    companion object {
        const val LOCATOR_TYPE = "MERIDIAN_ENCYCLOPEDIA"
        const val SCHEMA_VERSION = 1

        fun encode(locator: EncyclopediaSourceLocator): String = buildJsonObject {
            put("type", LOCATOR_TYPE)
            put("schemaVersion", SCHEMA_VERSION)
            put("kind", locator.kind.wire)
            put("entryId", locator.entryId)
            put("sectionId", locator.sectionId)
            put("sectionTitle", locator.sectionTitle)
            put("contentRevision", locator.contentRevision)
            locator.detailTab?.takeIf(String::isNotBlank)?.let { put("detailTab", it) }
            if (locator.openClues.isNotEmpty()) {
                put(
                    "openClues",
                    buildJsonArray {
                        locator.openClues.distinct().sorted().forEach { add(JsonPrimitive(it)) }
                    },
                )
            }
        }.toString()

        fun decode(raw: String?): EncyclopediaSourceLocator? {
            if (raw.isNullOrBlank()) return null
            val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
            if (obj["type"]?.jsonPrimitive?.contentOrNull != LOCATOR_TYPE) return null
            if (obj["schemaVersion"]?.jsonPrimitive?.intOrNull != SCHEMA_VERSION) return null
            val kind = EncyclopediaEntryKind.fromWire(obj["kind"]?.jsonPrimitive?.contentOrNull) ?: return null
            val entryId = obj["entryId"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            val secId = obj["sectionId"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            val secTitle = obj["sectionTitle"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            val rev = obj["contentRevision"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            val detailTab = obj["detailTab"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            val openClues = (obj["openClues"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.intOrNull }
                ?.filter { it >= 0 }
                ?.distinct()
                ?.sorted()
                .orEmpty()
            return EncyclopediaSourceLocator(
                kind = kind,
                entryId = entryId,
                sectionId = secId,
                sectionTitle = secTitle,
                contentRevision = rev,
                detailTab = detailTab,
                openClues = openClues,
            )
        }
    }
}

data class EncyclopediaVerifiedExtract(
    val locator: EncyclopediaSourceLocator,
    val title: String,
    val sectionTitle: String,
    val quote: String,
    val quoteSha256: String,
)

enum class EncyclopediaFailureReasonKind {
    NOT_FOUND,
    REVISION_MISSING,
    EVIDENCE_UNVERIFIED,
}

sealed interface EncyclopediaVerificationResult {
    data class Verified(val extract: EncyclopediaVerifiedExtract) : EncyclopediaVerificationResult
    data class Rejected(
        val failureKind: EncyclopediaFailureReasonKind,
        val reason: String,
    ) : EncyclopediaVerificationResult
}

/**
 * 固定离线资料包摘录构建器：
 * - 穴位：只取当前激活分页（定位 / 经属 / 主治 / 来源）；
 * - 病症：只取概览表现以及用户当前展开的辨证线索索引。
 */
object EncyclopediaEvidenceExtractor {

    fun extractLaunchContext(
        snapshot: EncyclopediaSnapshot,
        kind: EncyclopediaEntryKind,
        entryId: String,
        detailTabWire: String? = null,
        openClues: List<Int> = emptyList(),
        returnCheckpointJson: String? = null,
    ): EncyclopediaAgentLaunchContext? {
        val locator = when (kind) {
            EncyclopediaEntryKind.ACUPOINT -> {
                val section = detailTabWire ?: "location"
                EncyclopediaSourceLocator(
                    kind = kind,
                    entryId = entryId,
                    sectionId = section,
                    sectionTitle = sectionLabelForAcupoint(snapshot, entryId, section),
                    contentRevision = snapshot.manifest.contentRevision,
                    detailTab = section.uppercase(),
                )
            }
            EncyclopediaEntryKind.SYMPTOM -> {
                val sortedClues = openClues.distinct().sorted()
                EncyclopediaSourceLocator(
                    kind = kind,
                    entryId = entryId,
                    sectionId = if (sortedClues.isEmpty()) "overview" else "clues:${sortedClues.joinToString(",")}",
                    sectionTitle = if (sortedClues.isEmpty()) "常见表现与概览" else "常见表现与已展开辨证线索",
                    contentRevision = snapshot.manifest.contentRevision,
                    openClues = sortedClues,
                )
            }
        }
        val verified = extractForLocator(snapshot, locator) ?: return null
        return EncyclopediaAgentLaunchContext(
            kind = verified.locator.kind,
            entryId = verified.locator.entryId,
            contentRevision = verified.locator.contentRevision,
            title = verified.title,
            selectedSectionId = verified.locator.sectionId,
            selectedSectionTitle = verified.sectionTitle,
            selectedQuote = verified.quote,
            detailTab = verified.locator.detailTab,
            openClues = verified.locator.openClues,
            returnCheckpointJson = returnCheckpointJson,
        )
    }

    fun extractForLocator(
        snapshot: EncyclopediaSnapshot,
        locator: EncyclopediaSourceLocator,
    ): EncyclopediaVerifiedExtract? = when (locator.kind) {
        EncyclopediaEntryKind.ACUPOINT -> extractAcupoint(snapshot, locator)
        EncyclopediaEntryKind.SYMPTOM -> extractSymptom(snapshot, locator)
    }

    private fun sectionLabelForAcupoint(
        snapshot: EncyclopediaSnapshot,
        pointCode: String,
        section: String,
    ): String = when (section.lowercase()) {
        "location" -> "定位"
        "meridian" -> "经属"
        "indications" -> snapshot.learningForPoint(pointCode)?.displayHeading ?: "主治"
        "source", "sources" -> "来源"
        else -> "概览"
    }

    private fun extractAcupoint(
        snapshot: EncyclopediaSnapshot,
        locator: EncyclopediaSourceLocator,
    ): EncyclopediaVerifiedExtract? {
        val point = snapshot.pointsByCode[locator.entryId] ?: return null
        val meridian = snapshot.meridiansByCode[point.meridianCode]
        val occluded = snapshot.occludedByPointCode[point.code]
        val learning = snapshot.learningForPoint(point.code)
        val relatedSymptoms = snapshot.symptomsForPoint(point.code)
        val normalizedSection = (locator.detailTab ?: locator.sectionId).lowercase()
        val sectionTitle = sectionLabelForAcupoint(snapshot, point.code, normalizedSection)
        val quote = buildString {
            when (normalizedSection) {
                "meridian" -> {
                    append("${meridian?.name ?: point.meridianCode}（${point.meridianCode} · 第 ${point.orderInMeridian} 穴）")
                    meridian?.direction?.takeIf(String::isNotBlank)?.let { append("\n循行方向：$it") }
                    point.special.takeIf(String::isNotBlank)?.let { append("\n特定穴：$it") }
                    meridian?.description?.takeIf(String::isNotBlank)?.let { append("\n$it") }
                }
                "indications" -> {
                    if (learning == null || learning.groups.isEmpty()) {
                        append("本穴主治资料待教材核对。")
                    } else {
                        append("${learning.displayHeading}：")
                        learning.groups.forEach { group ->
                            append("\n${group.title}：${group.content}")
                        }
                        append("\n出处：${learning.source.title}")
                    }
                    if (relatedSymptoms.isNotEmpty()) {
                        append("\n关联病症：")
                        append(relatedSymptoms.joinToString("、") { it.name })
                    }
                }
                "source", "sources" -> {
                    append("GB/T 12346-2021 §${point.clause}（第 ${point.pdfPage} 页）")
                    if (learning != null) {
                        append("\n${learning.displayHeading}出处：${learning.source.title}")
                    }
                    if (relatedSymptoms.isNotEmpty()) {
                        relatedSymptoms.take(5).forEach { symptom ->
                            val assoc = snapshot.associationsByKey[symptom.id to point.code]
                            val title = assoc?.dedicatedSource?.title ?: learning?.source?.title
                            if (!title.isNullOrBlank()) {
                                append("\n${symptom.name}：$title")
                            }
                        }
                    }
                }
                else -> {
                    val reg = occluded?.regionLabel ?: point.region
                    append("定位（$reg）：${point.location}")
                    point.projectionNote?.takeIf(String::isNotBlank)?.let { append("\n$it") }
                    point.special.takeIf(String::isNotBlank)?.let { append("\n特定穴：$it") }
                }
            }
        }.trim().take(EncyclopediaAgentLaunchContext.MAX_QUOTE_CHARS)
        if (quote.isBlank()) return null
        val normalizedLocator = locator.copy(
            sectionId = normalizedSection,
            sectionTitle = sectionTitle,
            detailTab = normalizedSection.uppercase(),
            contentRevision = snapshot.manifest.contentRevision,
        )
        return EncyclopediaVerifiedExtract(
            locator = normalizedLocator,
            title = "${point.name} ${point.code} · ${meridian?.shortName ?: point.meridianCode}",
            sectionTitle = sectionTitle,
            quote = quote,
            quoteSha256 = AgentHashing.sha256(quote),
        )
    }

    private fun extractSymptom(
        snapshot: EncyclopediaSnapshot,
        locator: EncyclopediaSourceLocator,
    ): EncyclopediaVerifiedExtract? {
        val symptom = snapshot.symptomsById[locator.entryId] ?: return null
        val selectedClues = locator.openClues.filter { it in symptom.clues.indices }.distinct().sorted()
        val sectionTitle = if (selectedClues.isEmpty()) "常见表现与概览" else "常见表现与辨证线索"
        val quote = buildString {
            append("${symptom.name}（${symptom.category}）：${symptom.summary}")
            if (symptom.signals.isNotEmpty()) {
                append("\n常见表现：")
                symptom.signals.forEach { signal ->
                    append("\n• ${signal.title}：${signal.content}")
                }
            }
            if (selectedClues.isNotEmpty()) {
                append("\n已展开辨证线索：")
                selectedClues.forEach { idx ->
                    val clue = symptom.clues[idx]
                    append("\n• ${clue.title}：${clue.content}")
                }
            }
            if (symptom.links.isNotEmpty()) {
                append("\n关联穴位：")
                append(
                    symptom.links.joinToString("、") { link ->
                        val pName = snapshot.pointsByCode[link.pointCode]?.name ?: link.pointCode
                        "$pName(${link.pointCode})"
                    },
                )
            }
        }.trim().take(EncyclopediaAgentLaunchContext.MAX_QUOTE_CHARS)
        if (quote.isBlank()) return null
        val normalizedLocator = locator.copy(
            sectionTitle = sectionTitle,
            openClues = selectedClues,
            contentRevision = snapshot.manifest.contentRevision,
        )
        return EncyclopediaVerifiedExtract(
            locator = normalizedLocator,
            title = "${symptom.name} · ${symptom.category}",
            sectionTitle = sectionTitle,
            quote = quote,
            quoteSha256 = AgentHashing.sha256(quote),
        )
    }
}

/** 离线百科固定修订核验器：保证任何回链来源与当前离线包修订及词条文本一致。 */
object EncyclopediaEvidenceVerifier {

    fun verify(
        snapshot: EncyclopediaSnapshot?,
        locator: EncyclopediaSourceLocator,
        expectedRevision: String,
        expectedQuote: String,
    ): EncyclopediaVerificationResult {
        if (expectedQuote.isBlank()) {
            return EncyclopediaVerificationResult.Rejected(
                EncyclopediaFailureReasonKind.EVIDENCE_UNVERIFIED,
                "来源摘录为空，无法核验",
            )
        }
        if (snapshot == null) {
            return EncyclopediaVerificationResult.Rejected(
                EncyclopediaFailureReasonKind.REVISION_MISSING,
                "百科离线资料包尚未加载，无法回跳",
            )
        }
        if (locator.contentRevision != expectedRevision) {
            return EncyclopediaVerificationResult.Rejected(
                EncyclopediaFailureReasonKind.EVIDENCE_UNVERIFIED,
                "定位器修订版本号与记录不一致",
            )
        }
        val exists = when (locator.kind) {
            EncyclopediaEntryKind.ACUPOINT -> snapshot.pointsByCode[locator.entryId] != null
            EncyclopediaEntryKind.SYMPTOM -> snapshot.symptomsById[locator.entryId] != null
        }
        if (!exists) {
            return EncyclopediaVerificationResult.Rejected(
                EncyclopediaFailureReasonKind.NOT_FOUND,
                "百科词条（${locator.entryId}）已不存在，保留历史摘录",
            )
        }
        if (snapshot.manifest.contentRevision != expectedRevision) {
            return EncyclopediaVerificationResult.Rejected(
                EncyclopediaFailureReasonKind.REVISION_MISSING,
                "百科资料包已修订（原版本 $expectedRevision），保留历史摘录但禁用回跳",
            )
        }
        val current = EncyclopediaEvidenceExtractor.extractForLocator(snapshot, locator)
            ?: return EncyclopediaVerificationResult.Rejected(
                EncyclopediaFailureReasonKind.NOT_FOUND,
                "目标分段内容已不存在，保留历史摘录",
            )
        if (!quotesMatch(current.quote, expectedQuote)) {
            return EncyclopediaVerificationResult.Rejected(
                EncyclopediaFailureReasonKind.EVIDENCE_UNVERIFIED,
                "词条摘录与当前版本不一致，保留历史摘录但禁用回跳",
            )
        }
        return EncyclopediaVerificationResult.Verified(current)
    }

    private fun quotesMatch(canonical: String, candidate: String): Boolean {
        val normCanonical = canonical.replace(Regex("\\s+"), " ").trim()
        val normCandidate = candidate.replace(Regex("\\s+"), " ").trim()
        if (normCandidate.isEmpty()) return false
        return normCanonical == normCandidate || normCanonical.contains(normCandidate)
    }
}
