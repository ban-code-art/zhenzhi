package com.example.myapplication.data.reader.content

import com.example.myapplication.data.reader.storage.*
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.*

data class ContentChapterProjection(val chapterKey: String, val manifest: ContentManifest, val resources: Map<String, ByteArray>)
/** Deterministic per-chapter projection. Global visual dependencies are deliberately pinned conservatively. */
object ContentPackageProjection {
    const val EXTRACTOR_VERSION = "content-schema2-v1"
    fun build(input: ContentPackageArchiveInput): List<ContentChapterProjection> = buildWithinBudget(input, 256L * 1024 * 1024)
    /** Conservative projected aggregate budget, checked before duplicating chapter dependencies. */
    internal fun buildWithinBudget(input: ContentPackageArchiveInput, maxProjectedBytes: Long): List<ContentChapterProjection> {
        val owned = ContentPackageArchiveInput(input.manifestBytes.copyOf(), input.resources.mapValues { it.value.copyOf() })
        val report = ContentPackageValidator().validate(owned.manifestBytes, owned.resources, ContentValidationMode.CANONICAL)
        require(report.canonicalValid == true) { "projection: invalid package ${report.issues}" }
        val root = ContentPackageCodec.decode(owned.manifestBytes)
        require(root["packageId"]!!.jsonPrimitive.content == "builtin-acupuncture") { "projection: foreign package namespace" }
        val descriptors = root["resources"]!!.jsonArray.map { it.jsonObject }
        require(maxProjectedBytes >= 0)
        val globalBytes = descriptors.filter { it["kind"]!!.jsonPrimitive.content in setOf("IMAGE","STYLE","COVER") }.sumOf { it["bytes"]!!.jsonPrimitive.long }
        val bodyBytes = root["chapters"]!!.jsonArray.sumOf { chapter ->
            val path = chapter.jsonObject["bodyPath"]!!.jsonPrimitive.content
            descriptors.single { it["path"]!!.jsonPrimitive.content == path }["bytes"]!!.jsonPrimitive.long
        }
        require(bodyBytes + globalBytes * root["chapters"]!!.jsonArray.size <= maxProjectedBytes) { "projection: aggregate memory budget exceeded" }
        require(descriptors.none { it["path"]!!.jsonPrimitive.content == "manifest.json" }) { "projection: reserved manifest path" }
        return root["chapters"]!!.jsonArray.map { it.jsonObject }.sortedBy { it["chapterKey"]!!.jsonPrimitive.content }.map { chapter ->
            fun text(key: String) = chapter[key]!!.jsonPrimitive.content
            val bodyPath = text("bodyPath")
            val canonical = ContentPackageCanonical.parse(chapter, owned.resources.getValue(bodyPath))
            val files = descriptors.filter { it["path"]!!.jsonPrimitive.content == bodyPath || it["kind"]!!.jsonPrimitive.content in setOf("IMAGE", "STYLE", "COVER") }.sortedBy { it["path"]!!.jsonPrimitive.content }.map { resource ->
                val path = resource["path"]!!.jsonPrimitive.content
                val kind = if(path == bodyPath) ManifestFileKind.SECTION else ManifestFileKind.valueOf(resource["kind"]!!.jsonPrimitive.content)
                ManifestFile(path, resource["sha256"]!!.jsonPrimitive.content, resource["bytes"]!!.jsonPrimitive.long, kind)
            }
            val section = ManifestSection(requireNotNull(canonical.sectionId), 0, text("title"), bodyPath, canonical.canonicalText.length)
            val document = requireNotNull(canonical.documentId)
            val manifest = ContentManifest(1, "content-schema2:$document:${text("contentRevision")}", document, text("bodySha256"), text("contentRevision"), EXTRACTOR_VERSION, text("title"), "内置课程", files, listOf(section), listOf(ManifestOutlineNode("toc-1", orderIndex=0,title=text("title"),targetSectionId=section.id)))
            require(ContentManifestValidator.validate(manifest).isValid) { "projection: invalid storage manifest" }
            ContentChapterProjection(text("chapterKey"), manifest, files.associate { it.relativePath to owned.resources.getValue(it.relativePath).copyOf() })
        }
    }
    fun prepare(storage: DocumentStorage, input: ContentPackageArchiveInput): List<PublishedRevision> = build(input).map { projection ->
        val manifest = projection.manifest
        val existing = storage.openRevision(manifest.documentId, manifest.contentRevision, true)
        if (existing != null) {
            require(existing.manifest.copy(title=manifest.title, sections=existing.manifest.sections.mapIndexed { i,v -> v.copy(title=manifest.sections[i].title) }, outline=existing.manifest.outline.mapIndexed { i,v -> v.copy(title=manifest.outline[i].title) }) == manifest) { "projection: same revision has conflicting dependencies" }
            existing
        } else {
            val stageId = "projection-${UUID.randomUUID()}"
            val directory = storage.stagingRevisionDirectory(stageId, manifest.contentRevision)
            try {
                require(directory.mkdirs()) { "projection: staging directory unavailable" }
                projection.resources.forEach { (path, bytes) ->
                    val target = File(directory, path)
                    require(target.canonicalPath.startsWith(directory.canonicalPath + File.separator))
                    target.parentFile!!.mkdirs()
                    target.outputStream().use { it.write(bytes) }
                }
                storage.writeManifest(directory, manifest)
                storage.publishRevision(directory, manifest)
            } finally { storage.deleteStagingJob(stageId) }
        }
    }
}
