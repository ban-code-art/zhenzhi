package com.example.myapplication.data.reader

import android.content.res.AssetManager
import androidx.room.withTransaction
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.ChapterEntity
import com.example.myapplication.data.local.entity.ContentPackageEntity
import com.example.myapplication.data.local.entity.CourseBookEntity
import com.example.myapplication.data.local.entity.CourseBookChapterEntity
import com.example.myapplication.data.local.entity.ReaderContentRevisionEntity
import com.example.myapplication.data.local.entity.ReaderDocumentEntity
import com.example.myapplication.data.local.entity.ReaderOutlineNodeEntity
import com.example.myapplication.data.local.entity.ReaderSectionEntity
import com.example.myapplication.data.reader.content.ContentPackageCodec
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class BuiltInContentSeeder(
    private val database: AppDatabase,
    assets: AssetManager,
    private val clock: () -> Long = System::currentTimeMillis,
    private val readAsset: (String) -> ByteArray = { path -> assets.open(path).use { it.readBytes() } },
) {
    suspend fun seedIfNeeded() {
        val manifestBytes = readAsset(MANIFEST_PATH)
        require(manifestBytes.size <= 1024 * 1024) { "built-in manifest too large" }
        // Validate the complete legacy wire before reading resources or entering publication.
        // Revisions below still use the original manifest bytes, never a re-encoded JSON object.
        val root = ContentPackageCodec.decodeLegacyManifest(manifestBytes)
        require(root.requireInt("schemaVersion") == 1) { "legacy seeder requires content schema1" }
        val packageId = root.requireString("packageId")
        val version = root.requireInt("version")
        require(packageId == LEGACY_PACKAGE_ID && version > 0) { "unexpected legacy package identity/version" }
        val chapters = root["chapters"]?.jsonArray?.map { element ->
            val chapter = element.jsonObject
            BuiltInChapter(
                key = chapter.requireString("key"),
                order = chapter.requireInt("order"),
                title = chapter.requireString("title"),
                summary = chapter["summary"]?.let { (it as? JsonPrimitive)?.content },
                bodyPath = chapter.requireString("body"),
            )
        } ?: emptyList()
        require(chapters.isNotEmpty()) { "built-in manifest has no chapters" }
        require(chapters.map { it.key }.distinct().size == chapters.size) { "duplicate built-in chapter key" }
        require(chapters.map { it.key }.toSet() == LEGACY_KEYS) { "legacy foundation must contain the original seven chapter keys" }
        require(chapters.map { it.order }.sorted() == chapters.indices.toList()) { "invalid built-in chapter order" }

        val chapterBodies = chapters.associate { chapter ->
            require(chapter.bodyPath.matches(Regex("[A-Za-z0-9_./-]+")) &&
                chapter.bodyPath.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "invalid built-in body path" }
            val bytes = readAsset("content/${chapter.bodyPath}")
            require(bytes.size <= 8 * 1024 * 1024 && parseText(bytes).isNotEmpty()) { "missing/empty built-in body: ${chapter.key}" }
            chapter.key to bytes
        }
        val manifestHash = sha256(
            manifestBytes + chapters.joinToString("") { chapter ->
                chapter.key + sha256(chapterBodies.getValue(chapter.key))
            }.toByteArray(StandardCharsets.UTF_8),
        )

        val packageDao = database.readerJobDao()
        val revisions = chapters.associate { chapter ->
            chapter.key to sha256("$manifestHash:${chapter.key}:${sha256(chapterBodies.getValue(chapter.key))}")
        }

        database.withTransaction {
            // Read under the same transaction as publication: concurrent starts must not race.
            val existingPackage = packageDao.findPackage(packageId)
            if (existingPackage?.status?.startsWith("SCHEMA2_APPLIED:") == true) {
                require(existingPackage.version > 0 && existingPackage.manifestHash.matches(Regex("[0-9a-f]{64}")) &&
                    existingPackage.status.matches(Regex("SCHEMA2_APPLIED:[1-9][0-9]*")) &&
                    existingPackage.status.substringAfter(':').toLongOrNull() != null) { "invalid schema2 generation" }
                return@withTransaction
            }
            require(existingPackage == null || existingPackage.status == "APPLIED") { "unknown built-in package status" }
            require(existingPackage == null || existingPackage.version <= version) { "refusing built-in package downgrade" }
            val contentCurrent = existingPackage?.version == version &&
                existingPackage.manifestHash == manifestHash && existingPackage.status == "APPLIED"
            val catalog = database.readerCatalogDao()
            val chapterDao = database.chapterDao()
            val now = clock()
            if (!contentCurrent) {
                chapters.sortedBy { it.order }.forEach { chapter ->
                    val existingChapter = chapterDao.findByContentKey(chapter.key)
                    val chapterId = existingChapter?.id ?: chapterDao.insert(
                        ChapterEntity(
                            orderIndex = chapter.order,
                            title = chapter.title,
                            summary = chapter.summary,
                            contentKey = chapter.key,
                            enabled = true,
                            createdAt = now,
                        ),
                    )
                    if (existingChapter != null) {
                        chapterDao.updateMetadata(
                            id = chapterId,
                            orderIndex = chapter.order,
                            title = chapter.title,
                            summary = chapter.summary,
                            enabled = true,
                        )
                    }
                    val documentId = "builtin:chapter:${chapter.key}"
                    val revision = revisions.getValue(chapter.key)
                    val existingDocument = catalog.findDocumentIncludingDeleted(documentId)
                    if (existingDocument != null) {
                        require(existingDocument.kind == "BUILTIN" && existingDocument.ownerUserId == null &&
                            existingDocument.legacyChapterId == chapterId && existingDocument.deletedAt == null &&
                            existingDocument.importStatus == "READY") { "invalid built-in document identity: ${chapter.key}" }
                    }
                    if (existingDocument == null) {
                        catalog.insertDocument(
                            ReaderDocumentEntity(
                                id = documentId,
                                ownerUserId = null,
                                kind = "BUILTIN",
                                legacyChapterId = chapterId,
                                format = "HTML",
                                title = chapter.title,
                                author = "内置课程",
                                blobPath = "asset:content/${chapter.bodyPath}",
                                fileSha256 = null,
                                contentRevision = revision,
                                extractorVersion = "builtin-v$version",
                                metadataRevision = 1,
                                accessEpoch = 1,
                                importStatus = "READY",
                                createdAt = now,
                                updatedAt = now,
                            ),
                        )
                        catalog.insertContentRevision(
                            ReaderContentRevisionEntity(
                                documentId = documentId,
                                revision = revision,
                                sourceSha256 = null,
                                sourceBlobPath = "asset:content/${chapter.bodyPath}",
                                manifestPath = "asset:content/manifest.json",
                                extractorVersion = "builtin-v$version",
                                status = "READY",
                                createdAt = now,
                            ),
                        )
                    } else if (existingDocument.contentRevision != revision) {
                        catalog.updateContentRevision(documentId, revision, "builtin-v$version", now)
                        catalog.insertContentRevision(
                            ReaderContentRevisionEntity(
                                documentId = documentId,
                                revision = revision,
                                sourceSha256 = null,
                                sourceBlobPath = "asset:content/${chapter.bodyPath}",
                                manifestPath = "asset:content/manifest.json",
                                extractorVersion = "builtin-v$version",
                                status = "READY",
                                createdAt = now,
                            ),
                        )
                    }
                    if (existingDocument?.contentRevision == revision) return@forEach
                    val sectionId = "$documentId:$revision:section-1"
                    val text = parseText(chapterBodies.getValue(chapter.key))
                    catalog.insertSections(
                        listOf(
                            ReaderSectionEntity(
                                id = sectionId,
                                documentId = documentId,
                                contentRevision = revision,
                                parentId = null,
                                orderIndex = chapter.order,
                                title = chapter.title,
                                depth = 0,
                                resourceHref = "asset:content/${chapter.bodyPath}",
                                canonicalPath = "asset:content/${chapter.bodyPath}",
                                charCount = text.length,
                                pageStart = null,
                                pageEnd = null,
                            ),
                        ),
                    )
                    catalog.insertOutline(
                        listOf(
                            ReaderOutlineNodeEntity(
                                id = "$documentId:$revision:toc-1",
                                documentId = documentId,
                                contentRevision = revision,
                                nodeKey = "toc-1",
                                parentId = null,
                                orderIndex = 0,
                                title = chapter.title,
                                targetSectionId = sectionId,
                                targetLocatorJson = null,
                            ),
                        ),
                    )
                }
            }

            // Even an APPLIED schema1 package needs this after the schema6 -> schema7 upgrade.
            publishFoundation(packageId, version, chapters, revisions)
            if (!contentCurrent && existingPackage == null) {
                packageDao.insertPackage(
                    ContentPackageEntity(
                        packageId = packageId,
                        version = version,
                        manifestHash = manifestHash,
                        status = "APPLIED",
                        appliedAt = now,
                    ),
                )
            } else if (!contentCurrent) {
                packageDao.updatePackage(packageId, version, manifestHash, "APPLIED", now)
            }
        }
    }

    /** Called only inside seedIfNeeded's transaction, after all legacy content is ready. */
    private suspend fun publishFoundation(
        packageId: String,
        version: Int,
        chapters: List<BuiltInChapter>,
        revisions: Map<String, String>,
    ) {
        val catalog = database.readerCatalogDao()
        val mappings = chapters.sortedBy { it.order }.map { chapter ->
            val legacy = requireNotNull(database.chapterDao().findByContentKey(chapter.key)) { "missing legacy chapter: ${chapter.key}" }
            val document = requireNotNull(catalog.findDocumentIncludingDeleted("builtin:chapter:${chapter.key}")) { "missing built-in document: ${chapter.key}" }
            require(legacy.enabled && document.kind == "BUILTIN" && document.ownerUserId == null &&
                document.legacyChapterId == legacy.id && document.deletedAt == null &&
                document.importStatus == "READY" && document.contentRevision == revisions.getValue(chapter.key)) { "built-in source not ready: ${chapter.key}" }
            require(catalog.findContentRevision(document.id, document.contentRevision)?.status == "READY") { "built-in revision not ready: ${chapter.key}" }
            val section = requireNotNull(catalog.findSections(document.id, document.contentRevision).firstOrNull()) { "missing readable built-in section: ${chapter.key}" }
            CourseBookChapterEntity(
                bookId = FOUNDATION_BOOK_ID,
                chapterKey = chapter.key,
                orderIndex = chapter.order,
                documentId = document.id,
                sectionId = section.id,
                legacyChapterId = legacy.id,
            )
        }
        val dao = database.courseBookDao()
        val existingBook = dao.findBook(FOUNDATION_BOOK_ID)
        require(existingBook == null || (existingBook.contentKey == FOUNDATION_KEY &&
            existingBook.packageId == packageId && existingBook.contentVersion <= version.toLong())) { "foundation book identity/version conflict" }
        val oldMappings = dao.listAllChapters(FOUNDATION_BOOK_ID).associateBy { it.chapterKey }
        require(oldMappings.keys.all { it in LEGACY_KEYS }) { "unexpected chapter in legacy foundation book" }
        val book = CourseBookEntity(
            bookId = FOUNDATION_BOOK_ID,
            contentKey = FOUNDATION_KEY,
            title = "针灸基础与经络腧穴",
            summary = "经络、腧穴、刺法、灸法与治疗原则的基础学习课程",
            orderIndex = 0,
            packageId = packageId,
            contentVersion = version.toLong(),
            reviewStatus = "DRAFT", // DEVELOPMENT_SAMPLE must not become professional approval.
        )
        if (existingBook == null) dao.insertBook(book)
        else if (existingBook != book) check(dao.updateBook(book) == 1) { "foundation metadata update lost" }
        for (mapping in mappings) {
            val previous = oldMappings[mapping.chapterKey]
            if (previous == null) dao.insertChapters(listOf(mapping))
            else if (previous != mapping) check(dao.updateChapter(mapping) == 1) { "foundation mapping update lost" }
        }
    }

    private fun parseText(bytes: ByteArray): String =
        bytes.toString(StandardCharsets.UTF_8)
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(value: String): String = sha256(value.toByteArray(StandardCharsets.UTF_8))

    private fun JsonObject.requireString(name: String): String =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?: error("missing built-in manifest field: $name")

    private fun JsonObject.requireInt(name: String): Int =
        (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toIntOrNull()
            ?: error("missing built-in manifest integer: $name")

    private data class BuiltInChapter(
        val key: String,
        val order: Int,
        val title: String,
        val summary: String?,
        val bodyPath: String,
    )

    companion object {
        private const val MANIFEST_PATH = "content/manifest.json"
        private const val LEGACY_PACKAGE_ID = "builtin-acupuncture"
        private const val FOUNDATION_KEY = "acupuncture-foundation"
        private const val FOUNDATION_BOOK_ID = "course:acupuncture-foundation"
        private val LEGACY_KEYS = setOf("meridian-overview", "twelve-meridians", "acupoint-basics", "common-acupoints", "needling-basics", "moxibustion-basics", "treatment-principles")
    }
}
