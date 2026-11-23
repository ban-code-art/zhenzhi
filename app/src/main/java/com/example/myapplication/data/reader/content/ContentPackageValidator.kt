package com.example.myapplication.data.reader.content

import com.example.myapplication.domain.reader.ContentBlock
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

enum class ContentValidationMode { FORMAT, CANONICAL, PRODUCTION }
data class ContentCanonicalBlock(val blockId: String, val canonicalStart: Int, val canonicalEnd: Int, val runtimeBlockId: String? = null)
/** Index from the fixed reader parser, bound to the exact body/revision; DOM ids alone are not proof. */
data class ContentCanonicalChapter(
    val canonicalText: String,
    val blocks: List<ContentCanonicalBlock>,
    val contentRevision: String? = null,
    val bodySha256: String? = null,
    val documentId: String? = null,
    val sectionId: String? = null,
    val parserVersion: String? = null,
    val runtimeBlocks: List<ContentBlock> = emptyList(),
)
data class ContentProfessionalChapterBinding(val chapterKey: String, val contentRevision: String, val bodySha256: String)
data class ContentProfessionalReviewRequest(
    val scopeKey: String,
    val subject: JsonObject,
    val manifest: JsonObject,
    val review: JsonObject,
    val recordBytes: ByteArray,
    val chapterBindings: List<ContentProfessionalChapterBinding>,
)
fun interface ContentProfessionalReviewVerifier {
    /** Verify this exact subject, manifest and chapter bindings against the real record. */
    fun isVerified(request: ContentProfessionalReviewRequest): Boolean
}
data class ContentPackageIssue(val path: String, val message: String)
data class ContentPackageValidation(val formatValid: Boolean, val productionValid: Boolean, val issues: List<ContentPackageIssue>, val canonicalValid: Boolean? = null)

class ContentPackageValidator {
    fun validate(
        manifestBytes: ByteArray,
        resources: Map<String, ByteArray>,
        mode: ContentValidationMode = ContentValidationMode.FORMAT,
        canonicalChapters: Map<String, ContentCanonicalChapter> = emptyMap(),
        professionalReviewVerifier: ContentProfessionalReviewVerifier? = null,
    ): ContentPackageValidation {
        val root = try { ContentPackageCodec.decode(manifestBytes) } catch (error: IllegalArgumentException) {
            val message = error.message.orEmpty()
            return ContentPackageValidation(false, false, listOf(ContentPackageIssue(message.substringBefore(": ").ifBlank { "root" }, message.substringAfter(": ", "invalid wire"))), if (mode == ContentValidationMode.FORMAT) null else false)
        }
        return Validation(root, manifestBytes.size, resources, canonicalChapters, professionalReviewVerifier).run(mode)
    }

    private class Validation(
        private val root: JsonObject,
        private val manifestSize: Int,
        private val resources: Map<String, ByteArray>,
        private val providedCanonical: Map<String, ContentCanonicalChapter>,
        private val professional: ContentProfessionalReviewVerifier?,
    ) {
        private val issues = mutableListOf<ContentPackageIssue>()
        private val canonical = linkedMapOf<String, ContentCanonicalChapter>()
        private val books = root.objects("books")
        private val chapters = root.objects("chapters")
        private val sources = root.objects("sources")
        private val questions = root.objects("questions")
        private val descriptors = root.objects("resources")
        private val bookByKey = books.associateBy { it.text("bookKey") }
        private val chapterByKey = chapters.associateBy { it.text("chapterKey") }
        private val sourceKeys = sources.map { it.text("sourceKey") }.toSet()
        private val resourceByPath = descriptors.associateBy { it.text("path") }
        private val knowledge = chapters.flatMap { chapter -> chapter.objects("knowledgePoints").map { it.text("knowledgePointKey") to (chapter.text("chapterKey") to it) } }.toMap()

        fun run(mode: ContentValidationMode): ContentPackageValidation {
            resourceChecks()
            identityAndBindings()
            dependencyChecks()
            val formatValid = issues.isEmpty()
            if (mode == ContentValidationMode.FORMAT) return ContentPackageValidation(formatValid, false, issues.toList())
            canonicalChecks()
            val canonicalValid = formatValid && issues.isEmpty()
            if (mode == ContentValidationMode.CANONICAL) return ContentPackageValidation(formatValid, false, issues.toList(), canonicalValid)
            if (mode == ContentValidationMode.PRODUCTION) productionChecks()
            return ContentPackageValidation(formatValid, mode == ContentValidationMode.PRODUCTION && canonicalValid && issues.isEmpty(), issues.toList(), canonicalValid)
        }
        private fun fail(path: String, message: String) { issues += ContentPackageIssue(path, message) }
        private fun check(ok: Boolean, path: String, message: String) { if (!ok) fail(path, message) }
        private fun resourceChecks() {
            check(resources.size + 1 <= 10_000 && descriptors.size + 1 <= 10_000, "root.resources", "file count exceeds 10000")
            check(resources.values.sumOf { it.size.toLong() } + manifestSize <= 256L * 1024 * 1024, "root.resources", "expanded total exceeds 256 MiB")
            val names = hashSetOf<String>()
            descriptors.forEachIndexed { i, descriptor ->
                val path = descriptor.text("path")
                val prefix = "root.resources[$i]"
                check(contentPackagePath(path), "$prefix.path", "unsafe resource path")
                check(path != "manifest.json", "$prefix.path", "root manifest cannot be a resource")
                check(names.add(path.lowercase(Locale.ROOT)), "$prefix.path", "duplicate/case-colliding resource path")
                val actual = resources[path]
                check(actual != null, "$prefix.path", "resource missing")
                if (actual != null) {
                    check(actual.size <= 8 * 1024 * 1024, "$prefix.bytes", "single resource exceeds 8 MiB")
                    check(actual.size == descriptor.number("bytes"), "$prefix.bytes", "resource byte count mismatch")
                    check(ContentPackageCodec.sha256(actual) == descriptor.text("sha256"), "$prefix.sha256", "resource sha256 mismatch")
                }
                if (descriptor.text("kind") == "IMAGE") check(descriptor.optionalText("altText")?.isNotBlank() == true, "$prefix.altText", "image requires altText")
                if (descriptor.text("kind") in setOf("IMAGE", "COVER")) {
                    val name = path.lowercase(Locale.ROOT)
                    val svgBytes = actual?.let { bytes ->
                        Regex("(?i)<\\s*(?:[a-z_][a-z0-9_.-]*:)?svg\\b").containsMatchIn(bytes.toString(Charsets.UTF_8).replace("\u0000", ""))
                    } == true
                    check(!name.endsWith(".svg") && !name.endsWith(".svgz") && !svgBytes, prefix, "SVG/SVGZ resources are unsupported")
                }
                if (descriptor.text("kind") == "STYLE" && actual != null) {
                    try { cssReferences(ContentPackageCodec.utf8(actual), path, prefix) } catch (_: IllegalArgumentException) { fail(prefix, "style must be UTF-8") }
                }
            }
            val actualNames = hashSetOf<String>()
            resources.forEach { (path, _) ->
                check(contentPackagePath(path), "root.resources.$path", "unsafe supplied resource path")
                check(actualNames.add(path.lowercase(Locale.ROOT)), "root.resources.$path", "supplied case collision")
                check(path in resourceByPath, "root.resources.$path", "unregistered resource")
            }
        }
        private fun identityAndBindings() {
            val allKeys = hashSetOf<String>()
            fun key(value: String, path: String) { check(allKeys.add(value), path, "duplicate key") }
            books.forEachIndexed { bi, book ->
                val p = "root.books[$bi]"; val bookKey = book.text("bookKey")
                key(bookKey, "$p.bookKey")
                check(book.number("orderIndex") == bi, "$p.orderIndex", "book order must match array sequence")
                timestamp(book.text("updatedAtUtc"), "$p.updatedAtUtc")
                review(book.obj("review"), "$p.review")
                book.optionalText("coverPath")?.let { resourceReference(it, "COVER", "$p.coverPath") }
                book.strings("chapterKeys").forEachIndexed { i, chapterKey ->
                    val chapter = chapterByKey[chapterKey]
                    check(chapter != null && chapter.text("bookKey") == bookKey, "$p.chapterKeys[$i]", "chapter target missing or owned by another book")
                    if (chapter != null) check(chapter.number("orderIndex") == i, "$p.chapterKeys[$i]", "chapter order mismatch")
                }
                val owned = chapters.filter { it.text("bookKey") == bookKey }.map { it.text("chapterKey") }
                check(owned.toSet() == book.strings("chapterKeys").toSet() && owned.size == book.strings("chapterKeys").size, "$p.chapterKeys", "book/chapter mapping must be exact and bidirectional")
            }
            sources.forEachIndexed { i, source -> key(source.text("sourceKey"), "root.sources[$i].sourceKey") }
            chapters.forEachIndexed { ci, chapter ->
                val p = "root.chapters[$ci]"; val chapterKey = chapter.text("chapterKey"); val bookKey = chapter.text("bookKey")
                key(chapterKey, "$p.chapterKey")
                check(bookKey in bookByKey, "$p.bookKey", "orphan chapter book")
                check((chapterKey in ContentPackageCodec.legacyBodies && bookKey == "acupuncture-foundation") || localKey(chapterKey, bookKey), "$p.chapterKey", "chapter namespace does not match book")
                check(bookByKey[bookKey]?.strings("chapterKeys")?.count { it == chapterKey } == 1, "$p.chapterKey", "chapter must occur in exactly one book sequence")
                timestamp(chapter.text("updatedAtUtc"), "$p.updatedAtUtc")
                review(chapter.obj("review"), "$p.review")
                val bodyPath = chapter.text("bodyPath")
                resourceReference(bodyPath, "BODY", "$p.bodyPath", chapter.text("bodySha256"))
                resources[bodyPath]?.let { bytes ->
                    try {
                        val html = ContentPackageCodec.utf8(bytes)
                        check(Jsoup.parse(html).body().text().isNotBlank(), "$p.bodyPath", "body text is empty")
                        bodyReferences(html, bodyPath, "$p.bodyPath")
                    } catch (_: IllegalArgumentException) { fail("$p.bodyPath", "body must be UTF-8") }
                }
                if (chapter.text("revisionAlgorithm") == "CONTENT_V2") {
                    check(chapter.text("contentRevision") == ContentPackageCodec.contentRevision(chapter), "$p.contentRevision", "CONTENT_V2 revision mismatch")
                } else {
                    val oldHash = ContentPackageCodec.legacyBodies[chapterKey]
                    check(root.text("packageId") == "builtin-acupuncture" && root.boolean("testOnly") && bookKey == "acupuncture-foundation" &&
                        bodyPath == "chapters/$chapterKey.html" && oldHash == chapter.text("bodySha256") && chapter.objects("knowledgePoints").isEmpty() &&
                        chapter.obj("review") == ContentPackageCodec.draftReview && oldHash != null &&
                        chapter.text("contentRevision") == ContentPackageCodec.sha256("${ContentPackageCodec.LEGACY_MANIFEST_HASH}:$chapterKey:$oldHash".encodeToByteArray()),
                        "$p.revisionAlgorithm", "LEGACY_V1 only permits known original content/revision")
                }
                chapter.objects("knowledgePoints").forEachIndexed { ki, point ->
                    val kp = "$p.knowledgePoints[$ki]"; val pointKey = point.text("knowledgePointKey")
                    key(pointKey, "$kp.knowledgePointKey")
                    check(localKey(pointKey, chapterKey), "$kp.knowledgePointKey", "knowledge point namespace mismatch")
                    check(point.strings("blockIds").distinct().size == point.strings("blockIds").size, "$kp.blockIds", "duplicate block binding")
                    check(point.strings("sourceKeys").distinct().size == point.strings("sourceKeys").size, "$kp.sourceKeys", "duplicate source binding")
                    point.strings("sourceKeys").forEachIndexed { i, source -> check(source in sourceKeys, "$kp.sourceKeys[$i]", "unknown source") }
                    point.objects("facts").forEachIndexed { fi, fact ->
                        val fp = "$kp.facts[$fi]"; key(fact.text("factKey"), "$fp.factKey")
                        check(localKey(fact.text("factKey"), pointKey), "$fp.factKey", "fact namespace mismatch")
                        check(fact.strings("answerParts").distinct().size == fact.strings("answerParts").size, "$fp.answerParts", "duplicate answer part")
                        check(fact.strings("distractors").distinct().size == fact.strings("distractors").size, "$fp.distractors", "duplicate distractor")
                        check(fact.strings("distractors").none { it == fact.strings("answerParts").joinToString("；") }, "$fp.distractors", "distractor repeats correct answer")
                        review(fact.obj("review"), "$fp.review")
                        fact.objects("evidence").forEachIndexed { i, evidence -> evidenceShape(evidence, "$fp.evidence[$i]", point.strings("sourceKeys").toSet(), point.strings("blockIds").toSet()) }
                    }
                }
            }
            questions.forEachIndexed { qi, question ->
                val p = "root.questions[$qi]"; val chapterKey = question.text("chapterKey")
                key(question.text("questionKey"), "$p.questionKey")
                check(question.text("questionKey").matches(Regex("${Regex.escape(chapterKey)}:question-[a-z0-9-]+")), "$p.questionKey", "question namespace mismatch")
                check(chapterKey in chapterByKey, "$p.chapterKey", "unknown question chapter")
                val points = question.strings("knowledgePointKeys")
                check(points.distinct().size == points.size, "$p.knowledgePointKeys", "duplicate knowledge point binding")
                points.forEachIndexed { i, point -> check(knowledge[point]?.first == chapterKey, "$p.knowledgePointKeys[$i]", "question knowledge point belongs to another chapter or is missing") }
                val boundPoints = points.mapNotNull { knowledge[it]?.second }
                val allowedSources = boundPoints.flatMap { it.strings("sourceKeys") }.toSet()
                val allowedBlocks = boundPoints.flatMap { it.strings("blockIds") }.toSet()
                question.objects("evidenceRefs").forEachIndexed { i, evidence -> evidenceShape(evidence, "$p.evidenceRefs[$i]", allowedSources, allowedBlocks) }
                questionShape(question, p)
                review(question.obj("review"), "$p.review")
            }
        }
        private fun evidenceShape(evidence: JsonObject, p: String, allowedSources: Set<String>, allowedBlocks: Set<String>) {
            check(evidence.text("sourceKey") in sourceKeys && evidence.text("sourceKey") in allowedSources, "$p.sourceKey", "evidence source is missing or not bound to knowledge point")
            check(evidence.text("blockId") in allowedBlocks, "$p.blockId", "evidence block is not bound to knowledge point")
            val start = evidence.number("canonicalStart"); val end = evidence.number("canonicalEnd")
            check(end > start && end.toLong() - start == evidence.text("quote").length.toLong(), p, "quote UTF-16 length/range mismatch")
        }
        private fun questionShape(question: JsonObject, p: String) {
            val options = question.strings("options"); val answers = question.strings("answer")
            if (question.text("type") == "CHOICE") {
                check(options.size == 4 && options.distinct().size == 4, "$p.options", "choice requires four unique options")
                check(answers.size == 1 && (answers.singleOrNull() in options || answers.singleOrNull() in listOf("A", "B", "C", "D")), "$p.answer", "choice requires one valid option/letter")
                check(question.objects("rubric").isEmpty(), "$p.rubric", "choice must not have essay rubric")
            } else {
                check(options.isEmpty(), "$p.options", "essay must not have choice options")
                val rubric = question.objects("rubric")
                check(rubric.map { it.text("key") }.distinct().size == rubric.size, "$p.rubric", "duplicate rubric key")
                check(rubric.sumOf { it.number("weight").toLong() } == question.number("score").toLong(), "$p.rubric", "rubric weights must sum to score")
                rubric.forEachIndexed { i, item -> check(item.strings("accepted").isNotEmpty(), "$p.rubric[$i].accepted", "rubric requires accepted answer") }
            }
        }
        private fun timestamp(value: String, p: String) {
            check(Api24UtcTimestamp.isValid(value), p, "expected real UTC timestamp")
        }
        private fun review(review: JsonObject, p: String) {
            val nullableFields = listOf("reviewer", "reviewedAtUtc", "recordPath", "recordSha256")
            if (review.text("status") == "DRAFT") {
                nullableFields.forEach { check(review[it] === JsonNull, "$p.$it", "DRAFT review fields must be explicit null") }
            } else {
                nullableFields.forEach { check(review.optionalText(it)?.isNotBlank() == true, "$p.$it", "reviewed status requires real record metadata") }
                review.optionalText("reviewedAtUtc")?.let { timestamp(it, "$p.reviewedAtUtc") }
                review.optionalText("recordPath")?.let { path ->
                    resourceReference(path, "REVIEW", "$p.recordPath", review.optionalText("recordSha256"))
                    check(resources[path]?.any { it.toInt().toChar() !in " \t\r\n" } == true, "$p.recordPath", "review record must be nonempty")
                }
            }
        }
        private fun resourceReference(path: String, kind: String, p: String, hash: String? = null) {
            check(contentPackagePath(path), p, "unsafe resource path")
            val descriptor = resourceByPath[path]
            check(descriptor != null && path in resources, p, "resource missing/unregistered")
            if (descriptor != null) {
                check(descriptor.text("kind") == kind, p, "resource kind must be $kind")
                if (hash != null) check(descriptor.text("sha256") == hash, p, "bound sha256 mismatch")
            }
        }
        private fun bodyReferences(html: String, bodyPath: String, p: String) {
            val dom = Jsoup.parse(html)
            check(dom.select("script, iframe, frame, object, embed, base, svg, math").isEmpty(), p, "active/external or unsupported inline resource content is forbidden")
            check(dom.select("meta[http-equiv]").none { it.attr("http-equiv").trim().equals("refresh", true) }, p, "meta refresh navigation is forbidden")
            dom.allElements.forEach { element ->
                check(element.attributes().none { it.key.lowercase(Locale.ROOT) in setOf("background", "lowsrc", "dynsrc", "ping", "srcdoc", "codebase", "archive", "manifest") }, p, "unsupported resource-bearing attribute")
                element.attributes().filter { it.key.lowercase(Locale.ROOT) in setOf("href", "xlink:href", "action", "formaction") }.forEach { attribute ->
                    val target = attribute.value.filterNot { it.isWhitespace() || it.code <= 0x20 }.lowercase(Locale.ROOT)
                    check(listOf("javascript:", "data:", "vbscript:", "file:", "filesystem:", "blob:").none { target.startsWith(it) }, p, "script/data navigation is forbidden")
                }
            }
            dom.select("[src], [srcset], link[href], video[poster]").forEach { element ->
                when {
                    element.tagName() == "img" && element.hasAttr("src") -> relativeReference(element.attr("src"), bodyPath, "IMAGE", p)
                    element.tagName() == "link" && element.attr("rel").equals("stylesheet", true) -> relativeReference(element.attr("href"), bodyPath, "STYLE", p)
                    else -> fail(p, "unsupported resource-bearing element")
                }
                check(!element.hasAttr("srcset"), p, "srcset is unsupported; use a registered image")
            }
            dom.select("style").forEach { cssReferences(it.data(), bodyPath, p) }
            dom.select("[style]").forEach { cssReferences(it.attr("style"), bodyPath, p) }
            check(dom.allElements.none { e -> e.attributes().any { it.key.startsWith("on", true) } }, p, "event handlers are forbidden")
        }
        private fun cssReferences(css: String, owner: String, p: String) {
            check(!css.contains("\\"), p, "escaped CSS resource paths are unsupported")
            check(!Regex("(?i)@import").containsMatchIn(css), p, "CSS imports are unsupported")
            check(!Regex("(?i)@font-face\\b").containsMatchIn(css), p, "CSS font-face is unsupported")
            val allowedFunctions = setOf(
                "url", "rgb", "rgba", "hsl", "hsla", "calc", "min", "max", "clamp",
                "linear-gradient", "repeating-linear-gradient", "radial-gradient", "repeating-radial-gradient",
                "conic-gradient", "repeating-conic-gradient", "translate", "translatex", "translatey", "translatez",
                "translate3d", "scale", "scalex", "scaley", "scalez", "scale3d", "rotate", "rotatex", "rotatey",
                "rotatez", "rotate3d", "skew", "skewx", "skewy", "matrix", "matrix3d", "perspective",
                "cubic-bezier", "steps", "counter", "counters", "nth-child", "nth-of-type",
            )
            Regex("[A-Za-z_-][A-Za-z0-9_-]*\\s*\\(").findAll(css).forEach { function ->
                val name = function.value.substringBefore('(').trim().lowercase(Locale.ROOT)
                check(name in allowedFunctions, p, "unsupported CSS function: $name")
            }
            Regex("(?i)url\\s*\\(([^)]*)\\)").findAll(css).forEach { match ->
                val ref = match.groupValues[1].trim().removeSurrounding("\"").removeSurrounding("'")
                relativeReference(ref, owner, "IMAGE", p)
            }
        }
        private fun relativeReference(reference: String, owner: String, kind: String, p: String) {
            if (!contentPackagePath(reference)) { fail(p, "unsafe/network resource reference: $reference"); return }
            val prefix = owner.substringBeforeLast('/', "")
            val path = if (prefix.isEmpty()) reference else "$prefix/$reference"
            resourceReference(path, kind, p)
        }
        private fun dependencyChecks() {
            val edges = chapters.associate { it.text("chapterKey") to it.obj("learning").strings("prerequisiteChapterKeys").toMutableSet() }.toMutableMap()
            chapters.forEachIndexed { i, chapter ->
                val key = chapter.text("chapterKey")
                edges.getValue(key).forEach { target -> check(target in chapterByKey && target != key, "root.chapters[$i].learning.prerequisiteChapterKeys", "missing/self prerequisite") }
            }
            books.forEachIndexed { i, book ->
                val targets = book.obj("learning").strings("prerequisiteChapterKeys")
                targets.forEach { target -> check(target in chapterByKey && target !in book.strings("chapterKeys"), "root.books[$i].learning.prerequisiteChapterKeys", "missing/self-book prerequisite") }
                book.strings("chapterKeys").forEach { key -> edges[key]?.addAll(targets) }
            }
            // Kahn avoids recursion on a legal package with thousands of chapters.
            val indegrees = edges.keys.associateWith { 0 }.toMutableMap()
            edges.values.forEach { targets -> targets.filter { it in indegrees }.forEach { indegrees[it] = indegrees.getValue(it) + 1 } }
            val queue = java.util.ArrayDeque(indegrees.filterValues { it == 0 }.keys)
            var visited = 0
            while (queue.isNotEmpty()) {
                val key = queue.removeFirst(); visited++
                edges[key].orEmpty().filter { it in indegrees }.forEach { next ->
                    indegrees[next] = indegrees.getValue(next) - 1
                    if (indegrees[next] == 0) queue.add(next)
                }
            }
            check(visited == edges.size, "root.chapters.learning.prerequisiteChapterKeys", "prerequisite cycle")
        }

        private fun canonicalChecks() {
            chapters.forEachIndexed { ci, chapter ->
                val path = "root.chapters[$ci]"
                val key = chapter.text("chapterKey")
                val bytes = resources[chapter.text("bodyPath")]
                if (bytes == null) {
                    fail("$path.bodyPath", "actual BODY missing for canonical parser")
                } else {
                    try {
                        canonical[key] = ContentPackageCanonical.parse(chapter, bytes)
                    } catch (error: IllegalArgumentException) {
                        val message = error.message.orEmpty()
                        val localPath = message.substringBefore(": ").removePrefix("chapter")
                        fail(if (localPath.startsWith('.')) path + localPath else "$path.bodyPath", message.substringAfter(": ", "canonical parser rejected BODY"))
                    }
                }
                if (providedCanonical.isNotEmpty()) {
                    check(providedCanonical[key] != null && providedCanonical[key] == canonical[key], "$path.knowledgePoints", "canonical provider differs from actual BODY reader parser output")
                }
            }
            (providedCanonical.keys - chapterByKey.keys).forEach { key ->
                fail("root.canonicalChapters.$key", "canonical provider contains an unknown chapter")
            }
            questions.forEachIndexed { qi, question ->
                canonical[question.text("chapterKey")]?.let { index ->
                    question.objects("evidenceRefs").forEachIndexed { ei, evidence ->
                        exactQuote(evidence, index, "root.questions[$qi].evidenceRefs[$ei]")
                    }
                }
            }
        }

        private fun productionChecks() {
            check(!root.boolean("testOnly"), "root.testOnly", "testOnly package cannot be production")
            check(bookByKey.keys == PRODUCTION_CHAPTERS.keys && books.size == 4, "root.books", "production requires the four fixed course books")
            check(chapters.size == 25, "root.chapters", "production requires all 25 chapters")
            check(professional != null, "root.review", "professional review verification is not connected")
            books.forEachIndexed { i, book ->
                val p = "root.books[$i]"
                check(book.strings("chapterKeys") == PRODUCTION_CHAPTERS[book.text("bookKey")], "$p.chapterKeys", "production course chapter keys/count/order mismatch")
                professionalReview("book:${book.text("bookKey")}", book, "$p.review", book.strings("chapterKeys"))
            }
            chapters.forEachIndexed { ci, chapter ->
                val p = "root.chapters[$ci]"; val key = chapter.text("chapterKey")
                professionalReview("chapter:$key", chapter, "$p.review", listOf(key))
                val points = chapter.objects("knowledgePoints")
                check(points.size >= 3, "$p.knowledgePoints", "production requires at least three knowledge points")
                val index = canonical[key]
                check(index != null, "$p.knowledgePoints", "real reader canonical index missing")
                if (index != null) {
                    check(index.contentRevision == chapter.text("contentRevision") && index.bodySha256 == chapter.text("bodySha256"), "$p.knowledgePoints", "canonical index body/revision binding mismatch")
                    check(contentUtf16Valid(index.canonicalText), "$p.knowledgePoints", "canonical text is invalid UTF-16")
                    check(index.blocks.map { it.blockId }.distinct().size == index.blocks.size, "$p.knowledgePoints", "canonical index has duplicate block IDs")
                    index.blocks.forEach { block ->
                        check(block.canonicalEnd > block.canonicalStart && contentUtf16Boundary(index.canonicalText, block.canonicalStart) && contentUtf16Boundary(index.canonicalText, block.canonicalEnd), "$p.knowledgePoints", "invalid canonical block range")
                    }
                }
                val facts = points.flatMap { it.objects("facts") }
                check(facts.map { normalizedPrompt(it.text("prompt")) }.distinct().size == facts.size, "$p.knowledgePoints.facts", "production facts repeat question intent")
                val choiceFacts = facts.filter { it.strings("distractors").size >= 3 }.map { it.text("factKey") }.toSet()
                val essayFacts = facts.filter { it.strings("answerParts").size in 3..5 }.map { it.text("factKey") }.toSet()
                check(choiceFacts.size >= 7 && essayFacts.size >= 3 && (choiceFacts + essayFacts).size >= 10, "$p.knowledgePoints.facts", "default 7+3 template coverage insufficient; save draft")
                points.forEachIndexed { ki, point ->
                    val kp = "$p.knowledgePoints[$ki]"
                    check(point.objects("facts").isNotEmpty(), "$kp.facts", "production knowledge point has no supported fact")
                    if (index != null) point.strings("blockIds").forEachIndexed { bi, block -> check(index.blocks.any { it.blockId == block }, "$kp.blockIds[$bi]", "block missing from real canonical index") }
                    point.objects("facts").forEachIndexed { fi, fact ->
                        val fp = "$kp.facts[$fi]"
                        professionalReview("fact:${fact.text("factKey")}", fact, "$fp.review", listOf(key))
                        check(fact.objects("evidence").isNotEmpty(), "$fp.evidence", "production fact requires evidence")
                        if (index != null) fact.objects("evidence").forEachIndexed { ei, evidence -> exactQuote(evidence, index, "$fp.evidence[$ei]") }
                    }
                }
                val offline = questions.filter { it.text("chapterKey") == key }
                check(offline.count { it.text("type") == "CHOICE" } >= 5 && offline.count { it.text("type") == "ESSAY" } >= 1, "root.questions", "chapter $key requires five choices and one essay")
                check(offline.flatMap { it.strings("knowledgePointKeys") }.distinct().size >= 3, "root.questions", "chapter $key offline questions must cover three knowledge points")
            }
            check(questions.map { it.text("chapterKey") to normalizedPrompt(it.text("stem")) }.distinct().size == questions.size, "root.questions", "production questions repeat intent")
            questions.forEachIndexed { qi, question ->
                val p = "root.questions[$qi]"
                professionalReview("question:${question.text("questionKey")}", question, "$p.review", listOf(question.text("chapterKey")))
                if (question.text("type") == "ESSAY") check(question.objects("rubric").size in 3..5, "$p.rubric", "production essay requires three to five rubric points")
                canonical[question.text("chapterKey")]?.let { index ->
                    question.objects("evidenceRefs").forEachIndexed { i, evidence -> exactQuote(evidence, index, "$p.evidenceRefs[$i]") }
                }
            }
        }
        private fun exactQuote(evidence: JsonObject, index: ContentCanonicalChapter, p: String) {
            try { requireExactCanonicalQuote(evidence, index, p) } catch (error: IllegalArgumentException) {
                fail(p, error.message.orEmpty().substringAfter(": ", "invalid canonical quote"))
            }
        }
        private fun professionalReview(scope: String, subject: JsonObject, p: String, chapterKeys: List<String>) {
            val review = subject.obj("review")
            check(review.text("status") == "APPROVED", "$p.status", "production requires professional APPROVED review")
            if (professional == null || review.text("status") != "APPROVED") return
            val bytes = review.optionalText("recordPath")?.let { resources[it] }
            val bindings = chapterKeys.mapNotNull { key -> chapterByKey[key]?.let { chapter ->
                ContentProfessionalChapterBinding(key, chapter.text("contentRevision"), chapter.text("bodySha256"))
            } }
            val accepted = bytes != null && try {
                professional.isVerified(ContentProfessionalReviewRequest(scope, subject, root, review, bytes.copyOf(), bindings))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { false }
            check(accepted, p, "professional record not verified for $scope")
        }
        private fun localKey(key: String, parent: String): Boolean = key.matches(Regex("${Regex.escape(parent)}:[a-z0-9-]+"))
        private fun normalizedPrompt(prompt: String): String = prompt.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
    }

    companion object {
        private val PRODUCTION_CHAPTERS = linkedMapOf(
            "acupuncture-foundation" to ContentPackageCodec.legacyBodies.keys.toList(),
            "acupuncture-methods" to listOf("learning-framework", "tools-preparation", "positioning-review", "needling-terms", "moxibustion-comparison", "case-reading").map { "acupuncture-methods:$it" },
            "tcm-inquiry" to listOf("purpose-structure", "questioning-methods", "chief-complaint", "inquiry-dimensions", "recording-synthesis", "inquiry-cases").map { "tcm-inquiry:$it" },
            "tcm-pulse" to listOf("pulse-concepts", "positions-methods", "normal-influences", "pulse-elements", "confusable-patterns", "pulse-cases").map { "tcm-pulse:$it" },
        )
    }
}

private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
private fun JsonObject.optionalText(name: String): String? = getValue(name).takeUnless { it === JsonNull }?.jsonPrimitive?.content
private fun JsonObject.number(name: String): Int = text(name).toInt()
private fun JsonObject.boolean(name: String): Boolean = text(name).toBooleanStrict()
private fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject
private fun JsonObject.objects(name: String): List<JsonObject> = getValue(name).jsonArray.map { it.jsonObject }
private fun JsonObject.strings(name: String): List<String> = getValue(name).jsonArray.map { it.jsonPrimitive.content }
