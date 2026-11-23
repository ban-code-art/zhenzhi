package com.example.myapplication.ui.knowledge.course

import com.example.myapplication.data.reader.content.ContentPackageArchive
import com.example.myapplication.data.reader.content.ContentPackageCodec
import com.example.myapplication.data.reader.content.ContentPackageValidator
import com.example.myapplication.data.reader.content.ContentValidationMode
import kotlinx.serialization.json.*

enum class CourseSubject(val label: String) {
    ALL("全部"), ACUPUNCTURE("针灸"), INQUIRY("问诊"), PULSE("脉诊"), OTHER("其他");

    companion object {
        fun forBook(key: String, title: String): CourseSubject = when {
            key.startsWith("acupuncture") || title.contains("针灸") -> ACUPUNCTURE
            key == "tcm-inquiry" || title.contains("问诊") -> INQUIRY
            key == "tcm-pulse" || title.contains("脉诊") -> PULSE
            else -> OTHER
        }
    }
}

data class BundledCourseChapter(
    val key: String,
    val title: String,
    val summary: String,
    val bodyPath: String,
    val reviewStatus: String,
    val difficulty: String?,
    val objectives: List<String>,
    val questionCount: Int,
    /** Index in the existing byte-preserving preview's book/chapter ordering. */
    val archiveIndex: Int,
)

data class BundledCourseBook(
    val key: String,
    val title: String,
    val sourceSummary: String,
    val reviewStatus: String,
    val subject: CourseSubject,
    val chapters: List<BundledCourseChapter>,
) {
    val questionCount: Int get() = chapters.sumOf { it.questionCount }
    val description: String get() = chapters.take(3).joinToString("、") { it.title }
    val objectives: List<String> get() = chapters.flatMap { it.objectives }.distinct()
}

/** Read-only presentation of real manifest metadata; it never publishes or creates progress. */
data class BundledCourseCatalog(val books: List<BundledCourseBook>, val testOnly: Boolean) {
    val chapterCount: Int get() = books.sumOf { it.chapters.size }
    val questionCount: Int get() = books.sumOf { it.questionCount }

    fun search(query: String, subject: CourseSubject = CourseSubject.ALL): List<BundledCourseBook> {
        val term = query.trim()
        return books.filter { book ->
            (subject == CourseSubject.ALL || book.subject == subject) && (term.isEmpty() ||
                book.title.contains(term, ignoreCase = true) ||
                book.description.contains(term, ignoreCase = true) ||
                book.chapters.any { chapter ->
                    chapter.title.contains(term, ignoreCase = true) ||
                        chapter.summary.contains(term, ignoreCase = true) ||
                        chapter.objectives.any { it.contains(term, ignoreCase = true) }
                })
        }
    }

    companion object {
        const val ASSET_PATH = "course-drafts/builtin-acupuncture-draft.zip"

        fun fromBytes(bytes: ByteArray): BundledCourseCatalog {
            val archive = ContentPackageArchive.read(bytes)
            require(ContentPackageValidator().validate(archive.manifestBytes, archive.resources,
                ContentValidationMode.CANONICAL).canonicalValid == true) { "课程结构或正文身份校验失败" }
            return fromManifest(ContentPackageCodec.decode(archive.manifestBytes))
        }

        fun fromManifest(root: JsonObject): BundledCourseCatalog {
            val books = root.getValue("books").jsonArray.map { it.jsonObject }
            val chapters = root.getValue("chapters").jsonArray.map { it.jsonObject }
            require(books.isNotEmpty() && chapters.isNotEmpty()) { "课程目录为空" }
            val chapterKeys = chapters.map { it.text("chapterKey") }
            require(chapterKeys.distinct().size == chapters.size) { "章节身份重复" }
            val byChapterKey = chapters.associateBy { it.text("chapterKey") }
            val bookKeys = books.map { it.text("bookKey") }
            require(bookKeys.distinct().size == books.size) { "课程书身份重复" }
            val orders = books.map { it.getValue("orderIndex").jsonPrimitive.int }
            require(orders.all { it >= 0 } && orders.distinct().size == books.size) { "课程书顺序无效" }
            val declaredKeys = books.flatMap { book ->
                book.getValue("chapterKeys").jsonArray.map { it.jsonPrimitive.content }
            }
            require(declaredKeys.size == chapters.size && declaredKeys.toSet() == chapterKeys.toSet() &&
                declaredKeys.distinct().size == declaredKeys.size) { "课程章节引用缺失或重复" }
            val questionCounts = root.getValue("questions").jsonArray.map { question ->
                question.jsonObject.text("chapterKey").also {
                    require(it in byChapterKey) { "练习引用了缺失章节" }
                }
            }.groupingBy { it }.eachCount()
            var archiveIndex = 0
            val orderedBooks = books.sortedBy { it.getValue("orderIndex").jsonPrimitive.int }.map { book ->
                val key = book.text("bookKey")
                val title = book.text("title")
                val orderedChapters = book.getValue("chapterKeys").jsonArray.map { item ->
                    val chapterKey = item.jsonPrimitive.content
                    val chapter = requireNotNull(byChapterKey[chapterKey]) { "课程章节不存在" }
                    require(chapter.text("bookKey") == key) { "章节不属于当前课程书" }
                    val learning = chapter["learning"] as? JsonObject
                    BundledCourseChapter(chapterKey, chapter.text("title"), chapter.optionalText("summary").orEmpty(),
                        chapter.text("bodyPath"), chapter.reviewStatus(), learning?.optionalText("difficulty"),
                        (learning?.get("objectives") as? JsonArray).orEmpty().mapNotNull {
                            (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content?.takeIf(String::isNotBlank)
                        }, questionCounts[chapterKey] ?: 0, archiveIndex++)
                }
                require(orderedChapters.isNotEmpty()) { "课程书没有章节" }
                BundledCourseBook(key, title, book.optionalText("summary").orEmpty(), book.reviewStatus(),
                    CourseSubject.forBook(key, title), orderedChapters)
            }
            return BundledCourseCatalog(orderedBooks, root["testOnly"]?.jsonPrimitive?.booleanOrNull == true)
        }
    }
}

private fun JsonObject.text(key: String): String = requireNotNull(optionalText(key)?.takeIf(String::isNotBlank)) {
    "课程字段缺失：$key"
}
private fun JsonObject.optionalText(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.reviewStatus(): String = (this["review"] as? JsonObject)?.optionalText("status").orEmpty()
