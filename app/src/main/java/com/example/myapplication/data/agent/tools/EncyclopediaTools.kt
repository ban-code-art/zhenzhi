package com.example.myapplication.data.agent.tools

import com.example.myapplication.data.encyclopedia.EncyclopediaAgentLaunchContext
import com.example.myapplication.data.encyclopedia.EncyclopediaEntryKind
import com.example.myapplication.data.encyclopedia.EncyclopediaEvidenceExtractor
import com.example.myapplication.data.encyclopedia.EncyclopediaLoadState
import com.example.myapplication.data.encyclopedia.EncyclopediaPackRepository
import com.example.myapplication.data.encyclopedia.EncyclopediaSnapshot
import com.example.myapplication.data.encyclopedia.EncyclopediaSourceLocator
import com.example.myapplication.domain.agent.AgentTool
import com.example.myapplication.domain.agent.ToolArgumentException
import com.example.myapplication.domain.agent.ToolArgs
import com.example.myapplication.domain.agent.ToolError
import com.example.myapplication.domain.agent.ToolErrorCode
import com.example.myapplication.domain.agent.ToolExecutionContext
import com.example.myapplication.domain.agent.ToolGroup
import com.example.myapplication.domain.agent.ToolResult
import com.example.myapplication.domain.agent.ToolSpec
import com.example.myapplication.domain.agent.boundedObject
import com.example.myapplication.domain.agent.integerSchema
import com.example.myapplication.domain.agent.stringSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 经学百科只读工具（仅授权当前离线经学资料包，不触碰书库表）：
 * - `read_encyclopedia_entry`：按词条编号（穴位代码如 `LU1` 或病症 id 如 `cough`）读取固定版本核验文本并产出可回跳来源；
 * - `search_encyclopedia`：在当前离线包内检索相关穴位、经络与病症。
 */
class EncyclopediaTools(
    private val packRepository: EncyclopediaPackRepository,
    private val initialContext: EncyclopediaAgentLaunchContext? = null,
) {
    fun tools(): List<AgentTool> = listOf(
        ReadEncyclopediaEntryTool(),
        SearchEncyclopediaTool(),
    )

    private suspend fun readySnapshot(): EncyclopediaSnapshot? =
        packRepository.currentSnapshot ?: (packRepository.loadIfNeeded() as? EncyclopediaLoadState.Ready)?.snapshot

    private inner class ReadEncyclopediaEntryTool : AgentTool {
        override val spec: ToolSpec = ToolSpec(
            name = "read_encyclopedia_entry",
            description = "读取经学百科穴位或病症的指定分段正文，用于回答与生成可核验引用（[S#]）。参数：kind(acupoint/symptom), entryId(如 LU1/cough), section(location/meridian/indications/sources 或 overview/clues)。",
            group = ToolGroup.READ_ONLY_CONTENT,
            parameters = boundedObject(
                required = listOf("kind", "entryId"),
                properties = mapOf(
                    "kind" to stringSchema(minLength = 1, maxLength = 16),
                    "entryId" to stringSchema(minLength = 1, maxLength = 64),
                    "section" to stringSchema(minLength = 1, maxLength = 32),
                ),
            ),
        )

        override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): ToolResult {
            val callId = context.requestId
            val rawKind = try {
                ToolArgs.requireString(arguments, "kind", 1, 16)
            } catch (bad: ToolArgumentException) {
                return ToolResult.failure(callId, spec.name, ToolError(ToolErrorCode.INVALID_ARGUMENT, bad.message ?: "invalid kind", field = bad.field))
            }
            val kind = EncyclopediaEntryKind.fromWire(rawKind.lowercase())
                ?: return ToolResult.failure(callId, spec.name, ToolError(ToolErrorCode.INVALID_ARGUMENT, "kind must be acupoint or symptom", field = "kind"))
            val entryId = try {
                ToolArgs.requireString(arguments, "entryId", 1, 64)
            } catch (bad: ToolArgumentException) {
                return ToolResult.failure(callId, spec.name, ToolError(ToolErrorCode.INVALID_ARGUMENT, bad.message ?: "invalid entryId", field = bad.field))
            }
            val section = try {
                ToolArgs.optionalString(arguments, "section", 32)
            } catch (bad: ToolArgumentException) {
                return ToolResult.failure(callId, spec.name, ToolError(ToolErrorCode.INVALID_ARGUMENT, bad.message ?: "invalid section", field = bad.field))
            } ?: if (kind == EncyclopediaEntryKind.ACUPOINT) "indications" else "clues"

            val snapshot = readySnapshot()
                ?: return ToolResult.failure(callId, spec.name, ToolError(ToolErrorCode.REVISION_MISSING, "百科离线资料包尚未就绪"))
            val expectedRevision = initialContext?.contentRevision
            if (expectedRevision != null && snapshot.manifest.contentRevision != expectedRevision) {
                return ToolResult.failure(callId, spec.name, ToolError(ToolErrorCode.REVISION_MISSING, "百科资料包版本已变化"))
            }
            val clueIndices = if (kind == EncyclopediaEntryKind.SYMPTOM) {
                if (initialContext != null && initialContext.kind == EncyclopediaEntryKind.SYMPTOM && initialContext.entryId == entryId && initialContext.openClues.isNotEmpty()) {
                    initialContext.openClues
                } else {
                    snapshot.symptomsById[entryId]?.clues?.indices?.toList().orEmpty()
                }
            } else {
                emptyList()
            }
            val locator = EncyclopediaSourceLocator(
                kind = kind,
                entryId = entryId,
                sectionId = section,
                sectionTitle = section,
                contentRevision = snapshot.manifest.contentRevision,
                detailTab = section.takeIf { kind == EncyclopediaEntryKind.ACUPOINT }?.uppercase(),
                openClues = clueIndices,
            )
            val extract = EncyclopediaEvidenceExtractor.extractForLocator(snapshot, locator)
                ?: return ToolResult.failure(callId, spec.name, ToolError(ToolErrorCode.NOT_FOUND, "未找到百科词条 $entryId"))

            val payload = buildJsonObject {
                put("kind", kind.wire)
                put("entryId", extract.locator.entryId)
                put("title", extract.title)
                put("section", extract.sectionTitle)
                put("contentRevision", extract.locator.contentRevision)
                put("text", extract.quote)
            }
            return ToolResult(
                callId = callId,
                toolName = spec.name,
                payload = payload,
                encyclopediaEvidence = listOf(extract),
            )
        }
    }

    private inner class SearchEncyclopediaTool : AgentTool {
        override val spec: ToolSpec = ToolSpec(
            name = "search_encyclopedia",
            description = "在经学百科离线资料中按穴位代码、穴名或病症名称搜索关联词条。参数：query(1-80字符), limit(1-8)。",
            group = ToolGroup.READ_ONLY_CONTENT,
            parameters = boundedObject(
                required = listOf("query"),
                properties = mapOf(
                    "query" to stringSchema(minLength = 1, maxLength = 80),
                    "limit" to integerSchema(minimum = 1, maximum = 8),
                ),
            ),
        )

        override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): ToolResult {
            val callId = context.requestId
            val query = try {
                ToolArgs.requireString(arguments, "query", 1, 80).trim()
            } catch (bad: ToolArgumentException) {
                return ToolResult.failure(callId, spec.name, ToolError(ToolErrorCode.INVALID_ARGUMENT, bad.message ?: "invalid query", field = bad.field))
            }
            val limit = try {
                ToolArgs.optionalInt(arguments, "limit", 1, 8) ?: 5
            } catch (bad: ToolArgumentException) {
                return ToolResult.failure(callId, spec.name, ToolError(ToolErrorCode.INVALID_ARGUMENT, bad.message ?: "invalid limit", field = bad.field))
            }
            val snapshot = readySnapshot()
                ?: return ToolResult.failure(callId, spec.name, ToolError(ToolErrorCode.REVISION_MISSING, "百科离线资料包尚未就绪"))

            val needle = query.lowercase()
            val hits = mutableListOf<JsonObject>()
            snapshot.points.asSequence()
                .filter { p ->
                    p.code.lowercase().contains(needle) ||
                        p.name.lowercase().contains(needle) ||
                        p.pinyin.lowercase().contains(needle)
                }
                .take(limit)
                .forEach { p ->
                    val mer = snapshot.meridiansByCode[p.meridianCode]?.shortName ?: p.meridianCode
                    hits += buildJsonObject {
                        put("kind", "acupoint")
                        put("entryId", p.code)
                        put("title", "${p.name} ${p.code} ($mer)")
                        put("snippet", p.location.take(120))
                    }
                }
            if (hits.size < limit) {
                snapshot.learning.symptoms.asSequence()
                    .filter { s ->
                        s.id.lowercase().contains(needle) ||
                            s.name.lowercase().contains(needle) ||
                            s.aliases.any { a -> a.lowercase().contains(needle) }
                    }
                    .take(limit - hits.size)
                    .forEach { s ->
                        hits += buildJsonObject {
                            put("kind", "symptom")
                            put("entryId", s.id)
                            put("title", "${s.name} (${s.category})")
                            put("snippet", s.summary.take(120))
                        }
                    }
            }
            return ToolResult.success(
                callId = callId,
                toolName = spec.name,
                payload = buildJsonObject {
                    put("query", query)
                    putJsonArray("results") { hits.forEach { add(it) } }
                },
            )
        }
    }
}
