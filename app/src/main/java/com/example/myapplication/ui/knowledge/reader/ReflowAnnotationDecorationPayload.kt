package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.data.reader.ReflowDomSelectionProjector
import com.example.myapplication.domain.reader.*
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

/** Each target carries its own source proof; an old locator cannot suppress other marks. */
internal object ReflowAnnotationDecorationPayload {
    data class Result(val payload: JsonArray, val unmappableIds: Set<String>)

    fun build(document: PreparedReflowDocument, items: List<ReaderDecoration>): Result {
        val rejected = linkedSetOf<String>()
        val maps = try {
            ReflowDomSelectionProjector.annotate(Jsoup.parse(document.html), document.blocks)
        } catch (_: Exception) {
            return Result(JsonArray(emptyList()), items.map { it.annotationId }.toSet())
        }
        val blocks = document.blocks.withIndex().associateBy { it.value.sectionId to it.value.blockId }
        val payload = buildJsonArray {
            for (item in items) {
                if (item.annotationId.isBlank() || item.revision <= 0 ||
                    !item.colorHex.matches(Regex("#[0-9a-fA-F]{6}")) || item.targets.isEmpty()) {
                    rejected += item.annotationId
                    continue
                }
                item.targets.forEachIndexed { ordinal, value ->
                    val entry = runCatching {
                        val locator = value as? ReaderLocator.Reflow ?: error("REFLOW_TARGET_REQUIRED")
                        require(ReaderLocatorValidator.validateForDocument(locator, document.resources.target.documentId,
                            document.resources.target.contentRevision).isValid)
                        val indexed = requireNotNull(blocks[locator.sectionId to locator.blockId])
                        val block = indexed.value
                        val mapping = maps[indexed.index]
                        require(mapping.isMappable && locator.nativeLocator == block.nativeLocator)
                        val start = locator.canonicalStart - block.canonicalStart
                        val end = locator.canonicalEnd - block.canonicalStart
                        require(start >= 0 && end > start && end <= block.text.length &&
                            block.text.substring(start, end) == locator.exactQuote &&
                            ReaderLocatorValidator.isWellFormedUtf16(block.text.substring(start, end)))
                        buildJsonObject {
                            put("id", item.annotationId); put("ordinal", ordinal); put("revision", item.revision.toString())
                            put("color", item.colorHex); put("underline", item.underline); put("blockId", block.blockId)
                            put("text", block.text); put("quote", locator.exactQuote)
                            put("canonicalStart", locator.canonicalStart); put("canonicalEnd", locator.canonicalEnd)
                            put("runs", buildJsonArray { mapping.nodes.forEach { run -> add(buildJsonObject {
                                put("path", buildJsonArray { run.nodePath.forEach { add(it) } })
                                put("text", run.rawText); put("start", run.canonicalStart); put("end", run.canonicalEnd)
                            }) } })
                        }
                    }.getOrNull()
                    if (entry == null) rejected += item.annotationId else add(entry)
                }
            }
        }
        return Result(payload, rejected.toSet())
    }
}
