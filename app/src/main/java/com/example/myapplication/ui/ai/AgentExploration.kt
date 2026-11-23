package com.example.myapplication.ui.ai

import com.example.myapplication.domain.ai.ToolCall
import kotlinx.serialization.json.*

/** Whitelisted, structured tool facts only. Never renders raw model arguments/results. */
internal object AgentExploration {
    fun step(call: ToolCall, status: AgentToolStatus, resultJson: String?, active: Boolean): AgentToolStepUi {
        fun obj(raw: String?) = runCatching { Json.parseToJsonElement(raw.orEmpty()) as? JsonObject }.getOrNull()
        fun JsonObject?.text(key: String) = (this?.get(key) as? JsonPrimitive)?.contentOrNull?.take(160)
        fun JsonObject?.number(key: String) = (this?.get(key) as? JsonPrimitive)?.intOrNull
        val args = obj(call.argumentsJson)
        val result = obj(resultJson)
        val payload = result?.get("payload") as? JsonObject
        val action = when (call.name) {
            "read_source", "read_note", "read_note_block" -> "阅读"
            "search_knowledge", "search_notes" -> "检索"
            "web_search" -> "搜索"
            "list_sources", "list_documents" -> "查找文档"
            "read_outline", "get_document_outline" -> "查看目录"
            else -> "执行"
        }
        val documentId = payload.text("documentId") ?: args.text("documentId")
        val subject = payload.text("title")?.takeIf { it.isNotBlank() }
            ?: args.text("query") ?: payload.text("query") ?: when (action) {
                "阅读" -> "原文"
                "查看目录" -> "文档目录"
                "查找文档" -> "可用文档"
                else -> call.name.replace('_', ' ')
            }
        val start = payload.number("startOffset") ?: args.number("startOffset")
        val end = payload.number("endOffset")
        val range = if (start != null && end != null && end >= start) "第 ${start + 1}–$end 字符" else null
        val resultRows = (payload?.get("results") as? JsonArray) ?: (payload?.get("sources") as? JsonArray)
            ?: (payload?.get("sections") as? JsonArray)
        val found = resultRows.orEmpty().take(30).mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val id = row.text("documentId") ?: documentId ?: return@mapNotNull null
            AgentToolHitUi(id, row.text("title") ?: "原文", (row["quote"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(1200), row.text("sectionId"))
        }
        val excerpt = (payload?.get("quote") as? JsonPrimitive)?.contentOrNull
        val results = if (found.isEmpty() && action == "阅读" && documentId != null && !excerpt.isNullOrBlank())
            listOf(AgentToolHitUi(documentId, subject, excerpt.take(1200), payload.text("sectionId") ?: args.text("sectionId"))) else found
        val count = resultRows?.size ?: payload.number("count") ?: payload.number("sectionCount")
        val summary = when (status) {
            AgentToolStatus.RUNNING -> if (active) "进行中…" else "未完成"
            AgentToolStatus.FAILED -> "未成功，可重试"
            AgentToolStatus.SUCCEEDED -> when {
                action == "阅读" && start != null && end != null -> "已读 ${end - start} 字符"
                count != null && action == "检索" -> "召回 $count 段"
                count != null && action == "查看目录" -> "$count 个章节"
                count != null -> "找到 $count 处"
                else -> "已完成"
            }
        }
        return AgentToolStepUi(call.callId, call.name, status, summary, action, subject, range, documentId, results,
            sectionId = payload.text("sectionId") ?: args.text("sectionId"), rangeStart = start, rangeEnd = end)
    }

    fun sourceForStep(step: AgentToolStepUi, sources: List<AgentSourceUi>): AgentSourceUi? {
        val candidates = sources.filter { it.documentId == step.documentId && (step.sectionId == null || it.sectionId == step.sectionId) }
        if (step.rangeStart == null || step.rangeEnd == null) return candidates.singleOrNull()
        return candidates.singleOrNull { source ->
            val locator = source.locatorJson?.let(com.example.myapplication.domain.reader.ReaderLocatorCodec::decodeOrNull)
                as? com.example.myapplication.domain.reader.ReaderLocator.Reflow
            locator?.canonicalStart == step.rangeStart && locator.canonicalEnd == step.rangeEnd
        }
    }

    /** All tool rounds belonging to the same question are one visible answer. */
    fun turns(messages: List<AgentMessageUi>): List<AgentMessageUi> {
        val out = mutableListOf<AgentMessageUi>()
        for (message in messages) {
            val last = out.lastOrNull()
            if (message.role != AgentMessageRole.ASSISTANT || last?.role != AgentMessageRole.ASSISTANT) {
                out += message
            } else {
                out[out.lastIndex] = last.copy(
                    content = listOf(last.content, message.content).filter { it.isNotBlank() }.joinToString("\n\n"),
                    streaming = message.streaming,
                    toolSteps = (last.toolSteps + message.toolSteps).associateBy { it.callId }.values.toList(),
                    sources = if (message.sources.isNotEmpty()) message.sources else last.sources,
                    statusLabel = message.statusLabel,
                )
            }
        }
        return out
    }
}
