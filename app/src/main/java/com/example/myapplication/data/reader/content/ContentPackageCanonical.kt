package com.example.myapplication.data.reader.content

import com.example.myapplication.data.reader.ReaderHtmlBlockParser
import com.example.myapplication.domain.reader.ExtractionQuality
import kotlinx.serialization.json.*

/** Rebuilds section text and wire aliases from the exact BODY bytes using the Room reader algorithm. */
object ContentPackageCanonical {
    fun parse(chapter: JsonObject, body: ByteArray): ContentCanonicalChapter {
        ContentPackageWire.v2.fields.getValue("chapters").item!!.check(chapter, "chapter")
        require(body.isNotEmpty() && body.size <= 8 * 1024 * 1024) { "chapter.bodyPath: invalid BODY size" }
        val key = chapter.text("chapterKey")
        val sha = ContentPackageCodec.sha256(body)
        require(sha == chapter.text("bodySha256")) { "chapter.bodySha256: actual BODY sha256 mismatch" }
        val revision = chapter.text("contentRevision")
        if (chapter.text("revisionAlgorithm") == "CONTENT_V2") {
            require(revision == ContentPackageCodec.contentRevision(chapter)) { "chapter.contentRevision: CONTENT_V2 revision mismatch" }
        } else {
            require(chapter.text("bookKey") == "acupuncture-foundation" &&
                chapter.text("bodyPath") == "chapters/$key.html" &&
                ContentPackageCodec.legacyBodies[key] == sha && chapter.objects("knowledgePoints").isEmpty() &&
                chapter.getValue("review") == ContentPackageCodec.draftReview &&
                revision == ContentPackageCodec.sha256("${ContentPackageCodec.LEGACY_MANIFEST_HASH}:$key:$sha".encodeToByteArray())) {
                "chapter.revisionAlgorithm: LEGACY_V1 requires original BODY and revision"
            }
        }
        val html = try { ContentPackageCodec.utf8(body) } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("chapter.bodyPath: invalid BODY UTF-8", error)
        }
        val documentId = "builtin:chapter:$key"
        val sectionId = "$documentId:$revision:section-1"
        val parsed = ReaderHtmlBlockParser.parse(html, sectionId)
        require(parsed.duplicateDomIds.isEmpty()) { "chapter.bodyPath: duplicate DOM ids ${parsed.duplicateDomIds.sorted()}" }
        require(contentUtf16Valid(parsed.canonicalText)) { "chapter.bodyPath: canonical text is invalid UTF-16" }
        val runtimeById = parsed.runtimeBlocks.associateBy { it.blockId }
        val index = ContentCanonicalChapter(
            canonicalText = parsed.canonicalText,
            blocks = parsed.emittedRootIds.map { (alias, runtimeId) ->
                val block = runtimeById.getValue(runtimeId)
                ContentCanonicalBlock(alias, block.canonicalStart, block.canonicalEnd, runtimeId)
            },
            contentRevision = revision,
            bodySha256 = sha,
            documentId = documentId,
            sectionId = sectionId,
            parserVersion = ReaderHtmlBlockParser.VERSION,
            runtimeBlocks = parsed.runtimeBlocks,
        )
        val byAlias = index.blocks.associateBy { it.blockId }
        chapter.objects("knowledgePoints").forEachIndexed { ki, point ->
            val path = "chapter.knowledgePoints[$ki]"
            val aliases = point.strings("blockIds")
            val sources = point.strings("sourceKeys")
            require(aliases.distinct().size == aliases.size) { "$path.blockIds: duplicate block binding" }
            require(sources.distinct().size == sources.size) { "$path.sourceKeys: duplicate source binding" }
            aliases.forEachIndexed { bi, alias ->
                require(!alias.contains(revision)) { "$path.blockIds[$bi]: stable block id cannot contain runtime revision" }
                val block = byAlias[alias]
                require(block != null) { "$path.blockIds[$bi]: block is not a unique emitted DOM root" }
                require(runtimeById.getValue(requireNotNull(block.runtimeBlockId)).extractionQuality != ExtractionQuality.NO_TEXT) {
                    "$path.blockIds[$bi]: NO_TEXT block cannot be evidence"
                }
            }
            point.objects("facts").forEachIndexed { fi, fact ->
                fact.objects("evidence").forEachIndexed { ei, evidence ->
                    val evidencePath = "$path.facts[$fi].evidence[$ei]"
                    require(evidence.text("sourceKey") in sources) { "$evidencePath.sourceKey: source is not bound to knowledge point" }
                    require(evidence.text("blockId") in aliases) { "$evidencePath.blockId: block is not bound to knowledge point" }
                    requireExactCanonicalQuote(evidence, index, evidencePath)
                }
            }
        }
        return index
    }
}

/** Only the declared global UTF-16 slice in its actual emitted root qualifies; never search the quote. */
internal fun requireExactCanonicalQuote(evidence: JsonObject, index: ContentCanonicalChapter, path: String) {
    val start = evidence.text("canonicalStart").toInt()
    val end = evidence.text("canonicalEnd").toInt()
    val block = index.blocks.singleOrNull { it.blockId == evidence.text("blockId") }
    val runtime = block?.let { found -> index.runtimeBlocks.singleOrNull { it.blockId == found.runtimeBlockId } }
    val valid = block != null && runtime != null && runtime.extractionQuality != ExtractionQuality.NO_TEXT &&
        start >= block.canonicalStart && end <= block.canonicalEnd && end > start &&
        contentUtf16Valid(index.canonicalText) && contentUtf16Boundary(index.canonicalText, start) && contentUtf16Boundary(index.canonicalText, end)
    require(valid && index.canonicalText.substring(start, end) == evidence.text("quote")) {
        "$path: quote does not exactly match canonical UTF-16 slice/block"
    }
}

private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
private fun JsonObject.objects(name: String): List<JsonObject> = getValue(name).jsonArray.map { it.jsonObject }
private fun JsonObject.strings(name: String): List<String> = getValue(name).jsonArray.map { it.jsonPrimitive.content }
