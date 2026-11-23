package com.example.myapplication.data.reader.content

import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

enum class ContentPackageReviewExportState { REJECTED, REVIEW_REQUIRED }

/** Each accessor returns a fresh view of the frozen CJ2 identity, never caller-owned arrays. */
class ContentPackageReviewExportResult internal constructor(
    val state: ContentPackageReviewExportState,
    payload: JsonObject,
) {
    private val frozenBytes = boundedReviewCj2(payload)
    val sha256: String = ContentPackageCodec.sha256(frozenBytes)
    val bytes: ByteArray get() = frozenBytes.copyOf()
    val json: JsonObject get() = Json.parseToJsonElement(frozenBytes.toString(Charsets.UTF_8)).jsonObject
}

/** Pure review-material export; contains no signing or reviewer authority. */
object ContentPackageReviewExport {
    fun export(manifestBytes: ByteArray, resources: Map<String, ByteArray>, chapterKey: String): ContentPackageReviewExportResult {
        if (manifestBytes.size !in 1..MAX_MANIFEST_BYTES || resources.size >= MAX_FILE_COUNT ||
            resources.values.any { it.size > MAX_RESOURCE_BYTES } ||
            resources.values.sumOf { it.size.toLong() } + manifestBytes.size > MAX_INPUT_BYTES)
            return rejected(listOf(ContentPackageIssue("root", "bounded review export input required")))
        return try {
            // Freeze the selected references and recheck their actual sizes before copying any byte array.
            val entries = mutableListOf<Pair<String, ByteArray>>()
            var expanded = manifestBytes.size.toLong()
            for ((path, bytes) in resources.entries) {
                expanded += bytes.size.toLong()
                require(entries.size < MAX_FILE_COUNT - 1 && bytes.size <= MAX_RESOURCE_BYTES && expanded <= MAX_INPUT_BYTES) {
                    "root.resources: bounded review export input required"
                }
                entries += path to bytes
            }
            val raw = manifestBytes.copyOf()
            val snapshot = entries.associate { (path, bytes) -> path to bytes.copyOf() }
            val validator = ContentPackageValidator()
            val format = validator.validate(raw, snapshot)
            if (!format.formatValid) return rejected(format.issues)
            val manifest = ContentPackageCodec.decode(raw)
            val chapters = manifest.objects("chapters")
            val chapter = chapters.singleOrNull { it.text("chapterKey") == chapterKey }
                ?: return rejected(listOf(ContentPackageIssue("root.chapters", "requested chapter is missing")))
            val canonical = chapters.associate { c -> c.text("chapterKey") to ContentPackageCanonical.parse(c, snapshot.getValue(c.text("bodyPath"))) }
            val checked = validator.validate(raw, snapshot, ContentValidationMode.CANONICAL, canonical)
            if (checked.canonicalValid != true) return rejected(checked.issues)
            val draft = ContentPackageQuizFactAdapter().prepareDraft(raw, snapshot, chapterKey)
            if (draft.state != ContentQuizFactState.REVIEW_REQUIRED) return rejected(draft.issues)
            val index = canonical.getValue(chapterKey)
            val points = chapter.objects("knowledgePoints")
            val facts = points.flatMap { it.objects("facts") }
            val questions = manifest.objects("questions").filter { it.text("chapterKey") == chapterKey }
            val book = manifest.objects("books").single { it.text("bookKey") == chapter.text("bookKey") }
            val bookChapters = book.strings("chapterKeys").map { key -> chapters.single { it.text("chapterKey") == key } }
            val reviewBindings = mutableListOf<JsonElement>()
            var bindingBytes = 0L
            fun binding(scopeKey: String, subject: JsonObject, bound: List<JsonObject>) {
                val request = ContentProfessionalReviewRequest(scopeKey, subject, manifest, subject.getValue("review").jsonObject,
                    ByteArray(0), bound.map { ContentProfessionalChapterBinding(it.text("chapterKey"), it.text("contentRevision"), it.text("bodySha256")) })
                val payload = ContentPackageReviewBinding.payload(request)
                val bytes = boundedReviewCj2(payload)
                bindingBytes += bytes.size
                require(bindingBytes <= MAX_REVIEW_OUTPUT_BYTES) { "root.reviewBindings: review export exceeds 16 MiB" }
                reviewBindings += buildJsonObject {
                    put("scopeKey", scopeKey);put("payload", payload);put("sha256", ContentPackageCodec.sha256(bytes))
                    put("chapterBindings", JsonArray(request.chapterBindings.map { b -> buildJsonObject {
                        put("chapterKey", b.chapterKey);put("contentRevision", b.contentRevision);put("bodySha256", b.bodySha256)
                    } }))
                }
            }
            binding("book:${book.text("bookKey")}", book, bookChapters)
            binding("chapter:$chapterKey", chapter, listOf(chapter))
            facts.forEach { binding("fact:${it.text("factKey")}", it, listOf(chapter)) }
            questions.forEach { binding("question:${it.text("questionKey")}", it, listOf(chapter)) }
            val runtime = index.runtimeBlocks.associateBy { it.blockId }
            val sources = manifest.objects("sources").associateBy { it.text("sourceKey") }
            val mapped = mutableListOf<JsonElement>()
            fun evidence(ownerType: String, ownerKey: String, ref: JsonObject) {
                requireExactCanonicalQuote(ref, index, "reviewExport.$ownerKey")
                val block = index.blocks.single { it.blockId == ref.text("blockId") }
                val native = runtime.getValue(requireNotNull(block.runtimeBlockId))
                mapped += buildJsonObject {
                    put("ownerType", ownerType);put("ownerKey", ownerKey);put("sourceKey", ref.text("sourceKey"))
                    put("source", sources.getValue(ref.text("sourceKey")))
                    put("wireBlockId", block.blockId);put("runtimeBlockId", native.blockId)
                    put("canonicalStart", ref.getValue("canonicalStart"));put("canonicalEnd", ref.getValue("canonicalEnd"))
                    put("quote", ref.getValue("quote"));put("nativeLocator", native.nativeLocator)
                    put("extractionQuality", native.extractionQuality.name)
                }
            }
            facts.forEach { fact -> fact.objects("evidence").forEach { evidence("FACT", fact.text("factKey"), it) } }
            questions.forEach { q -> q.objects("evidenceRefs").forEach { evidence("QUESTION", q.text("questionKey"), it) } }
            val distinct = facts.map { it.text("prompt").trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), "") }.distinct().size
            val choices = facts.filter { it.strings("distractors").size >= 3 }.map { it.text("factKey") }.toSet()
            val essays = facts.filter { it.strings("answerParts").size in 3..5 }.map { it.text("factKey") }.toSet()
            val payload = buildJsonObject {
                put("format", "content-review-export");put("schemaVersion", 1);put("state", ContentPackageReviewExportState.REVIEW_REQUIRED.name)
                put("identity", buildJsonObject {
                    put("packageId", manifest.getValue("packageId"));put("packageVersion", manifest.getValue("packageVersion"))
                    put("testOnly", manifest.getValue("testOnly"));put("manifestSha256", ContentPackageCodec.sha256(raw));put("manifestBytes", raw.size)
                    put("chapterKey", chapterKey);put("bodyPath", chapter.text("bodyPath"))
                    put("bodySha256", ContentPackageCodec.sha256(snapshot.getValue(chapter.text("bodyPath"))))
                    put("bodyBytes", snapshot.getValue(chapter.text("bodyPath")).size);put("contentRevision", chapter.text("contentRevision"))
                })
                put("resources", JsonArray(manifest.objects("resources").sortedBy { it.text("path") }.map { descriptor -> buildJsonObject {
                    put("descriptor", descriptor);put("path", descriptor.text("path"));put("sha256", ContentPackageCodec.sha256(snapshot.getValue(descriptor.text("path"))))
                    put("bytes", snapshot.getValue(descriptor.text("path")).size)
                } }))
                put("book", book);put("chapter", chapter);put("questions", JsonArray(questions));put("sources", manifest.getValue("sources"))
                put("reviewBindings", JsonArray(reviewBindings))
                put("bookScope", buildJsonObject {
                    put("basis", "CURRENT_PACKAGE_BOOK_SCOPE_ONLY");put("chapterKeys", book.getValue("chapterKeys"));put("chapterCount", bookChapters.size)
                    put("professionalApprovalGranted", false)
                    put("notice", "仅按当前包完整书scope列出材料；单章测试包不代表原七章或四书全包已审核。")
                })
                put("canonical", buildJsonObject {
                    put("documentId", requireNotNull(index.documentId));put("sectionId", requireNotNull(index.sectionId))
                    put("contentRevision", requireNotNull(index.contentRevision));put("parserVersion", requireNotNull(index.parserVersion))
                    put("canonicalText", index.canonicalText)
                    put("blocks", JsonArray(index.runtimeBlocks.map { b -> buildJsonObject {
                        put("runtimeBlockId", b.blockId);put("sectionId", b.sectionId);put("kind", b.kind.name)
                        put("wireBlockIds", JsonArray(index.blocks.filter { it.runtimeBlockId == b.blockId }.map { JsonPrimitive(it.blockId) }))
                        put("canonicalStart", b.canonicalStart);put("canonicalEnd", b.canonicalEnd);put("text", b.text)
                        put("headingPath", JsonArray(b.headingPath.map(::JsonPrimitive)));put("nativeLocator", b.nativeLocator)
                        put("extractionQuality", b.extractionQuality.name)
                    } }))
                    put("evidenceMappings", JsonArray(mapped))
                })
                put("provisionalExternalFinalReview", buildJsonObject {
                    put("provisional", true);put("mustReexportAfterProfessionalReview", true)
                    put("notice", "以下是当前草稿R/factHash输入；真实包内审核记录会改变CONTENT_V2 R，必须重导最终R输入并取得真实包外终审收据及Verifier。")
                    put("requests", JsonArray(draft.reviewRequests.map { r -> buildJsonObject {
                        put("factKey", r.factKey);put("sourceRevision", r.sourceRevision);put("factHash", r.factHash)
                        put("evidenceHashes", JsonArray(r.evidenceHashes.map(::JsonPrimitive)));put("documentId", r.documentId)
                        put("sectionIds", JsonArray(r.sectionIds.sorted().map(::JsonPrimitive)))
                    } }))
                })
                put("authority", buildJsonObject {
                    put("professionalApprovalGranted", false);put("signedReviewRecord", false);put("publicationAllowed", false)
                })
                put("coverage", buildJsonObject {
                    put("draftFactCount", facts.size);put("distinctPromptFacts", distinct);put("choiceCapableFacts", choices.size);put("essayCapableFacts", essays.size)
                    put("distinctTemplateCapableFacts", (choices + essays).size);put("requiredDistinctApprovedFacts", 10)
                    put("missingDistinctDraftFacts", (10 - distinct).coerceAtLeast(0));put("requiredChoice", 7);put("requiredEssay", 3)
                    put("duplicatePromptFacts", facts.size - distinct);put("missingDistinctTemplateCapableFacts", (10 - (choices + essays).size).coerceAtLeast(0))
                    put("missingChoiceCapableFacts", (7 - choices.size).coerceAtLeast(0));put("missingEssayCapableFacts", (3 - essays.size).coerceAtLeast(0))
                    put("professionallyVerifiedFacts", 0);put("defaultReady", false)
                })
                put("checksRequired", JsonArray(listOf(
                    "逐事实/答案/干扰项/题面/解析/评分点对照原来源，完成独立专业审核。",
                    "核对来源书目信息、译文、使用许可和商业分发权限；本导出未验证许可授权。",
                    "包外PDF/页图/ZIP原始档需另附相对路径、原SHA与字节清单交叉核对；本API只核manifest登记资源，不产生下载身份。",
                    "真实包内审核后重算当前R并重新导出；包外终审收据必须绑定最终R/factHash，且由真实Verifier核验。",
                    "满足10个独立审核facts及默认7＋3覆盖，并完成整包25章/四书发布门禁。"
                ).map(::JsonPrimitive)))
                put("validation", buildJsonObject { put("formatValid", true);put("canonicalValid", true);put("productionApprovalEvaluated", false) })
                put("issues", JsonArray(emptyList()))
            }
            ContentPackageReviewExportResult(ContentPackageReviewExportState.REVIEW_REQUIRED, payload)
        } catch (error: CancellationException) { throw error } catch (error: Exception) {
            rejected(listOf(ContentPackageIssue("root.reviewExport", error.message.orEmpty().take(512).ifBlank { "review export rejected" })))
        }
    }

    private fun rejected(issues: List<ContentPackageIssue>) = ContentPackageReviewExportResult(ContentPackageReviewExportState.REJECTED, buildJsonObject {
        put("format", "content-review-export");put("schemaVersion", 1);put("state", ContentPackageReviewExportState.REJECTED.name)
        put("issues", JsonArray(issues.map { issue -> buildJsonObject { put("path", issue.path.take(512));put("message", issue.message.take(512)) } }))
    })
    private fun JsonObject.objects(key: String) = getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }
    private const val MAX_MANIFEST_BYTES = 1024 * 1024
    private const val MAX_RESOURCE_BYTES = 8 * 1024 * 1024
    private const val MAX_INPUT_BYTES = 256L * 1024 * 1024
    private const val MAX_FILE_COUNT = 10_000
}

private const val MAX_REVIEW_OUTPUT_BYTES = 16L * 1024 * 1024

/** Exact CJ2 with an incremental byte limit, so repeated scoped materials cannot grow without bound. */
private fun boundedReviewCj2(value: JsonElement): ByteArray {
    val output = ByteArrayOutputStream()
    fun append(text: String) {
        val bytes = text.encodeToByteArray()
        require(output.size().toLong() + bytes.size <= MAX_REVIEW_OUTPUT_BYTES) { "root.reviewExport: review export exceeds 16 MiB" }
        output.write(bytes)
    }
    fun visit(node: JsonElement) {
        when (node) {
            is JsonObject -> {
                append("{")
                node.keys.sorted().forEachIndexed { i, key ->
                    if (i > 0) append(",")
                    append(ContentPackageCodec.canonicalJson(JsonPrimitive(key)));append(":");visit(node.getValue(key))
                }
                append("}")
            }
            is JsonArray -> {
                append("[");node.forEachIndexed { i, child -> if (i > 0) append(",");visit(child) };append("]")
            }
            is JsonPrimitive -> append(ContentPackageCodec.canonicalJson(node))
        }
    }
    visit(value)
    return output.toByteArray()
}
