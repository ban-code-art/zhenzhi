package com.example.myapplication.data.reader

import com.example.myapplication.domain.reader.*
import com.example.myapplication.ui.knowledge.reader.PreparedReflowDocument
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

/** Verifies the actual schema1 package used by the legacy seeder; never creates an import manifest. */
internal object BuiltInAssetVerifier {
    data class Verified(val revision: String, val extractor: String, val href: String, val html: String)
    private val keys = setOf("meridian-overview", "twelve-meridians", "acupoint-basics", "common-acupoints", "needling-basics", "moxibustion-basics", "treatment-principles")
    fun verify(documentId: String, read: (String) -> ByteArray): Verified {
        val manifest = read("content/manifest.json")
        require(manifest.size <= 1024 * 1024)
        val root = Json.parseToJsonElement(manifest.toString(Charsets.UTF_8)).jsonObject
        require(root.getValue("schemaVersion").jsonPrimitive.int == 1 && root.getValue("packageId").jsonPrimitive.content == "builtin-acupuncture")
        val version = root.getValue("version").jsonPrimitive.int
        require(version > 0)
        val chapters = root.getValue("chapters").jsonArray.map { it.jsonObject }
        require(chapters.size == keys.size && chapters.map { it.getValue("key").jsonPrimitive.content }.toSet() == keys)
        require(chapters.map { it.getValue("order").jsonPrimitive.int }.sorted() == chapters.indices.toList())
        val bodies = chapters.associate { chapter ->
            val key = chapter.getValue("key").jsonPrimitive.content
            val path = chapter.getValue("body").jsonPrimitive.content
            require(path.matches(Regex("[A-Za-z0-9_./-]+")) && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
            val bytes = read("content/$path")
            require(bytes.isNotEmpty() && bytes.size <= 8 * 1024 * 1024)
            key to bytes
        }
        val hash = sha(manifest + chapters.joinToString("") {
            val key = it.getValue("key").jsonPrimitive.content
            key + sha(bodies.getValue(key))
        }.toByteArray(Charsets.UTF_8))
        val selected = chapters.single { "builtin:chapter:" + it.getValue("key").jsonPrimitive.content == documentId }
        val key = selected.getValue("key").jsonPrimitive.content
        val body = bodies.getValue(key)
        val html = body.toString(Charsets.UTF_8)
        require(!html.contains('\uFFFD') && Jsoup.parse(html).body().text().isNotBlank())
        val doc = Jsoup.parse(html)
        // The shipped schema1 chapters are self contained. Future asset dependencies need an explicit verified allowlist.
        require(doc.select("[src],[srcset],link[href],style,script,iframe,object,embed,[style]").isEmpty())
        return Verified(sha("$hash:$key:${sha(body)}".toByteArray(Charsets.UTF_8)), "builtin-v$version",
            "asset:content/" + selected.getValue("body").jsonPrimitive.content, html)
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** Asset bytes are validated before canonical reads, so old revisions cannot consume current APK text. */
class BuiltInReflowResourcePreparer(
    private val access: DocumentAccess,
    private val repository: ReaderDocumentRepository,
    private val readAsset: (String) -> ByteArray,
) {
    suspend fun prepare(context: AccessContext, target: ReaderTarget): OperationResult<PreparedReflowDocument> {
        try {
            currentCoroutineContext().ensureActive()
            val asset = withContext(Dispatchers.IO) { BuiltInAssetVerifier.verify(target.documentId, readAsset) }
            if (asset.revision != target.contentRevision) return OperationResult.Rejected("BUILTIN_ASSET_REVISION_MISMATCH")
            val session = repository.openSession(context.userId, target.documentId, target.contentRevision)
            currentCoroutineContext().ensureActive()
            if (session !is OperationResult.Ok) return session.failure()
            val source = session.value
            if (source.document.kind != "BUILTIN" || source.document.ownerUserId != null || source.manifest != null ||
                source.revision.extractorVersion != asset.extractor || source.revision.manifestPath != "asset:content/manifest.json" ||
                source.revision.sourceBlobPath != asset.href || source.revision.sourceSha256 != null ||
                source.document.format != "HTML" ||
                source.sections.size != 1 || source.sections.single().canonicalPath != asset.href)
                return OperationResult.Rejected("BUILTIN_RESOURCE_IDENTITY_MISMATCH")
            val opened = access.open(context, target.documentId, target.contentRevision)
            currentCoroutineContext().ensureActive()
            if (opened !is OperationResult.Ok) return opened.failure()
            val snapshot = opened.value
            if (snapshot.sectionsNextCursor != null || snapshot.ownerUserId != null || snapshot.extractorVersion != asset.extractor ||
                snapshot.documentId != target.documentId || snapshot.contentRevision != asset.revision || snapshot.sections.size != 1)
                return OperationResult.Rejected("BUILTIN_RESOURCE_IDENTITY_MISMATCH")
            val section = snapshot.sections.single()
            if (section.resourceHref != asset.href || section.id != "${target.documentId}:${asset.revision}:section-1" ||
                section.pageStart != null || section.pageEnd != null)
                return OperationResult.Rejected("BUILTIN_RESOURCE_IDENTITY_MISMATCH")
            val sequence = ReaderSequenceMapper.map(snapshot, null, false)
            if (target !in sequence.orderedTargets || source.readingSequence().orderedTargets != sequence.orderedTargets)
                return OperationResult.Rejected("BUILTIN_RESOURCE_IDENTITY_MISMATCH")
            val blocks = mutableListOf<ContentBlock>()
            val seen = mutableSetOf<String>()
            var cursor: String? = null
            do {
                if (cursor != null && !seen.add(cursor)) return OperationResult.Rejected("REFLOW_BLOCK_CURSOR_CYCLE")
                val page = access.readBlocks(context, snapshot.snapshotId, section.id, cursor)
                currentCoroutineContext().ensureActive()
                if (page !is OperationResult.Ok) return page.failure()
                if (page.value.items.isEmpty() && page.value.nextCursor != null) return OperationResult.Rejected("REFLOW_EMPTY_BLOCK_PAGE")
                if (page.value.items.any { it.sectionId != section.id }) return OperationResult.Rejected("REFLOW_BLOCK_IDENTITY_MISMATCH")
                blocks += page.value.items
                if (blocks.size > 100_000) return OperationResult.Rejected("REFLOW_SECTION_TOO_LARGE")
                cursor = page.value.nextCursor
            } while (cursor != null)
            val html = withContext(Dispatchers.IO) { ReflowDomProjector.project(asset.html, blocks) }
            val latestAsset = withContext(Dispatchers.IO) { BuiltInAssetVerifier.verify(target.documentId, readAsset) }
            if (latestAsset != asset) return OperationResult.Rejected("BUILTIN_ASSET_REVISION_MISMATCH")
            val recheck = access.open(context, target.documentId, target.contentRevision)
            currentCoroutineContext().ensureActive()
            if (recheck !is OperationResult.Ok) return recheck.failure()
            if (recheck.value != snapshot) return OperationResult.Rejected("REFLOW_ACCESS_CHANGED")
            val resources = PreparedReaderResources(snapshot, sequence, target, emptyMap(), null, snapshot.sections)
            return OperationResult.Ok(PreparedReflowDocument(resources, html, blocks.toList(), asset.href, emptyMap()), context.requestId)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: IllegalArgumentException) {
            currentCoroutineContext().ensureActive()
            return OperationResult.Rejected("BUILTIN_RESOURCES_INVALID")
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return OperationResult.Failed("BUILTIN_RESOURCES_UNAVAILABLE", false)
        }
    }
    private fun OperationResult<*>.failure(): OperationResult<Nothing> = when (this) {
        is OperationResult.Ok -> error("expected failure")
        is OperationResult.Rejected -> this
        is OperationResult.Failed -> this
        is OperationResult.Conflict -> this
    }
}
