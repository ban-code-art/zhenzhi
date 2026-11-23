package com.example.myapplication.data.review

import androidx.room.withTransaction
import com.example.myapplication.data.encyclopedia.EncyclopediaLoadState
import com.example.myapplication.data.encyclopedia.EncyclopediaPackRepository
import com.example.myapplication.data.encyclopedia.EncyclopediaUserRepository
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.QuestionEntity
import com.example.myapplication.data.local.entity.ReaderAnnotationEntity
import com.example.myapplication.data.quiz.SourcePracticeArchiveRepository
import com.example.myapplication.data.quiz.SourcePracticeRecord
import com.example.myapplication.data.quiz.SourcePracticeKind
import com.example.myapplication.data.quiz.SourcePracticeFactory
import com.example.myapplication.data.quiz.SourcePracticeQuestion
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.data.session.SessionManager
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import com.example.myapplication.domain.reader.sha256Hex
import org.json.JSONArray
import org.json.JSONObject

enum class ReviewMaterialGroup(val label: String) {
    ANNOTATIONS("标注与摘录"), NOTES("笔记"), QUESTIONS("收藏与错题"), PRACTICE("随堂测");
}

data class ReviewMaterialPage(val items: List<ReviewCandidate>, val hasMore: Boolean)

sealed interface ReviewSourceLink {
    data class Reader(val documentId: String, val locatorJson: String) : ReviewSourceLink
    data class Notebook(val documentId: String) : ReviewSourceLink
    data class Practice(val recordId: String, val questionId: String) : ReviewSourceLink
    data class Question(val title: String, val answerAndAnalysis: String) : ReviewSourceLink
    data class Acupoint(val pointCode: String) : ReviewSourceLink
}

/** Bounded, account-scoped source discovery and live access checks for source readback. */
class ReviewSourceCatalog(
    private val database: AppDatabase,
    private val sessions: SessionManager,
    private val practice: SourcePracticeArchiveRepository,
    private val readReflowText: suspend (Long, ReaderLocator.Reflow) -> String? = { _, _ -> null },
    private val encyclopedia: EncyclopediaPackRepository? = null,
) {
    suspend fun page(identity: SessionIdentity, group: ReviewMaterialGroup, page: Int): ReviewMaterialPage =
        guarded(identity) { user ->
            require(page in 0..1000)
            val offset = page * PAGE_SIZE
            val dao = database.reviewMaterialDao()
            var hasMore = false
            val rows = when (group) {
                ReviewMaterialGroup.ANNOTATIONS -> dao.annotations(user, PAGE_SIZE + 1, offset).also { hasMore = it.size > PAGE_SIZE }.take(PAGE_SIZE).mapNotNull { annotation ->
                    annotationCandidate(user, annotation)
                }
                ReviewMaterialGroup.NOTES -> dao.notes(user, PAGE_SIZE + 1, offset).also { hasMore = it.size > PAGE_SIZE }.take(PAGE_SIZE).mapNotNull { note ->
                    val title = readable(user, note.documentId)?.title ?: return@mapNotNull null
                    val question = ReviewQuestionBuilder.fromPassage(note.bodyMarkdown)
                    ReviewCandidate(
                        sourceNamespace = "notebook", sourceKey = "notebook:${note.id}",
                        sourceRefJson = ref("notebook", "id" to note.id), sourceTags = setOf("NOTE"),
                        prompt = question?.prompt.orEmpty(), answerSnapshot = question?.answer.orEmpty(),
                        sourceRevision = note.revision.toString(), bookKey = "import:${note.documentId}", sourceTitle = title,
                        materialExcerpt = note.bodyMarkdown,
                    )
                }
                ReviewMaterialGroup.QUESTIONS -> dao.questions(user, PAGE_SIZE + 1, offset).also { hasMore = it.size > PAGE_SIZE }.take(PAGE_SIZE).mapNotNull { row ->
                    val tags = buildSet { if (row.isWrong) add("WRONG"); if (row.isFavorite) add("MANUAL") }
                    questionCandidate(row.question, tags)
                }
                ReviewMaterialGroup.PRACTICE -> practice.listForReview(identity).filter { practiceReadable(user, it) }.asSequence()
                    .flatMap { record ->
                        record.practice.questions.asSequence().mapIndexedNotNull { index, question ->
                            practiceCandidate(record, index, question)
                        }
                    }
                    .drop(offset).take(PAGE_SIZE + 1).toList().also { hasMore = it.size > PAGE_SIZE }.take(PAGE_SIZE)
            }.mapNotNull { it.bounded() }
            ReviewMaterialPage(rows, hasMore)
        }

    suspend fun quickAnnotation(identity: SessionIdentity, id: String, expectedRevision: Long): ReviewCandidate? =
        guarded(identity) { user ->
            database.readerAnnotationDao().findAnnotation(user, id)
                ?.takeIf { it.revision == expectedRevision }?.let { annotationCandidate(user, it)?.bounded() }
        }

    suspend fun quickNotebookExcerpt(
        identity: SessionIdentity,
        id: String,
        expectedRevision: Long,
        excerpt: String,
        prompt: String,
        operationId: String,
    ): ReviewCandidate? = guarded(identity) { user ->
        val note = database.readerNotebookDao().findNotebook(user, id) ?: return@guarded null
        val document = readable(user, note.documentId) ?: return@guarded null
        val selected = excerpt.trim()
        if (note.revision != expectedRevision || selected.isBlank() || selected.length > MAX_TEXT ||
            selected !in note.bodyMarkdown || prompt.length > MAX_PROMPT || operationId.isBlank() || operationId.length > 256
        ) return@guarded null
        val question = if (prompt.isBlank()) {
            ReviewQuestionBuilder.fromPassage(selected) ?: return@guarded null
        } else {
            ReviewQuestion(prompt.trim(), selected)
        }
        ReviewCandidate(
            sourceNamespace = "notebook-excerpt", sourceKey = "notebook-excerpt:$id:$operationId",
            sourceRefJson = ref("notebook", "id" to id), sourceTags = setOf("NOTE"),
            prompt = question.prompt, answerSnapshot = question.answer,
            sourceRevision = note.revision.toString(), bookKey = "import:${note.documentId}",
            sourceTitle = document.title, creationOperationId = operationId,
            excerptFingerprint = sha256Hex("notebook:$id\n$selected"),
        ).bounded()
    }

    suspend fun quickQuestion(identity: SessionIdentity, id: Long, expectedRevision: String): ReviewCandidate? =
        guarded(identity) { user ->
            val question = database.questionDao().findById(id)?.takeIf {
                it.enabled && (it.ownerUserId == null || it.ownerUserId == user) &&
                    (it.sourceRevision ?: it.createdAt.toString()) == expectedRevision
            } ?: return@guarded null
            if (question.sourceDocumentId?.let { readable(user, it) == null } == true) return@guarded null
            val tags = database.reviewMaterialDao().questionTags(user, id).toSet()
            questionCandidate(question, tags)?.bounded()
        }

    suspend fun quickPractice(identity: SessionIdentity, recordId: String, questionId: String): ReviewCandidate? =
        guarded(identity) { user ->
            val record = practice.load(identity, recordId)?.takeIf { practiceReadable(user, it) }
                ?: return@guarded null
            val index = record.practice.questions.indexOfFirst { it.id == questionId }
            record.practice.questions.getOrNull(index)?.let { practiceCandidate(record, index, it)?.bounded() }
        }

    suspend fun open(identity: SessionIdentity, sourceRefJson: String): ReviewSourceLink? =
        guarded(identity) { user ->
            val data = JSONObject(sourceRefJson)
            val id = data.getString("id")
            when (data.getString("kind")) {
                "annotation" -> database.readerAnnotationDao().findAnnotation(user, id)
                    ?.takeIf { it.deletedAt == null }?.let { annotation ->
                        val locator = ReaderLocatorCodec.decodeOrNull(annotation.locatorJson)
                        val document = readable(user, annotation.documentId)
                        if (locator != null && locator.documentId == annotation.documentId &&
                            document?.contentRevision == locator.contentRevision
                        ) ReviewSourceLink.Reader(annotation.documentId, annotation.locatorJson) else null
                    }
                "notebook" -> database.readerNotebookDao().findNotebook(user, id)?.documentId
                    ?.takeIf { readable(user, it) != null }?.let(ReviewSourceLink::Notebook)
                "question" -> id.toLongOrNull()?.let { database.questionDao().findById(it) }
                    ?.takeIf { it.enabled && (it.ownerUserId == null || it.ownerUserId == user) }
                    ?.takeIf { it.sourceDocumentId?.let { doc -> readable(user, doc) != null } ?: true }
                    ?.let { ReviewSourceLink.Question(questionPrompt(it), questionAnswer(it)) }
                "practice" -> {
                    val qid = data.getString("question")
                    practice.load(identity, id)?.takeIf { practiceReadable(user, it) }
                        ?.takeIf { current -> current.practice.questions.any { it.id == qid } }
                        ?.let { ReviewSourceLink.Practice(it.id, qid) }
                }
                EncyclopediaUserRepository.REVIEW_SOURCE_KIND -> {
                    val snapshot = encyclopedia?.currentSnapshot
                        ?: (encyclopedia?.loadIfNeeded() as? EncyclopediaLoadState.Ready)?.snapshot
                    if (snapshot != null && snapshot.pointsByCode[id] != null) ReviewSourceLink.Acupoint(id) else null
                }
                else -> null
            }
        }

    /** Called under the repository's shared account lock and Room transaction. */
    suspend fun statusWithinIdentity(identity: SessionIdentity, item: ReviewItemEntity): String {
        if (item.userId != identity.userId) return ReviewAvailability.SOURCE_REVOKED
        val user = item.userId
        val reference = runCatching { JSONObject(item.sourceRefJson) }.getOrNull()
            ?: return ReviewAvailability.SOURCE_MISSING
        val id = reference.optString("id")
        if (reference.optString("kind") == "standalone" && item.sourceNamespace == "standalone") {
            return ReviewAvailability.AVAILABLE
        }
        val revision = when (reference.optString("kind")) {
            "annotation" -> {
                val row = database.readerAnnotationDao().findAnnotation(user, id)
                    ?.takeIf { it.deletedAt == null } ?: return ReviewAvailability.SOURCE_MISSING
                val document = readable(user, row.documentId) ?: return ReviewAvailability.SOURCE_REVOKED
                val locator = ReaderLocatorCodec.decodeOrNull(row.locatorJson)
                if (locator?.documentId != document.id || locator.contentRevision != document.contentRevision) {
                    return ReviewAvailability.SOURCE_CHANGED
                }
                row.revision.toString()
            }
            "notebook" -> {
                val row = database.readerNotebookDao().findNotebook(user, id)
                    ?.takeIf { it.bodyMarkdown.isNotBlank() } ?: return ReviewAvailability.SOURCE_MISSING
                if (readable(user, row.documentId) == null) return ReviewAvailability.SOURCE_REVOKED
                row.revision.toString()
            }
            "question" -> {
                val qid = id.toLongOrNull() ?: return ReviewAvailability.SOURCE_MISSING
                val row = database.questionDao().findById(qid)?.takeIf { it.enabled }
                    ?: return ReviewAvailability.SOURCE_MISSING
                if (row.ownerUserId != null && row.ownerUserId != user) return ReviewAvailability.SOURCE_REVOKED
                if (row.sourceDocumentId?.let { readable(user, it) == null } == true) {
                    return ReviewAvailability.SOURCE_REVOKED
                }
                row.sourceRevision ?: row.createdAt.toString()
            }
            "practice" -> {
                val row = practice.load(identity, id) ?: return ReviewAvailability.SOURCE_MISSING
                if (!practiceReadable(user, row)) return ReviewAvailability.SOURCE_REVOKED
                if (row.practice.questions.none { it.id == reference.optString("question") }) {
                    return ReviewAvailability.SOURCE_MISSING
                }
                row.sourceRevision
            }
            EncyclopediaUserRepository.REVIEW_SOURCE_KIND -> {
                val snapshot = encyclopedia?.currentSnapshot
                    ?: (encyclopedia?.loadIfNeeded() as? EncyclopediaLoadState.Ready)?.snapshot
                    ?: return ReviewAvailability.SOURCE_MISSING
                if (snapshot.pointsByCode[id] == null) return ReviewAvailability.SOURCE_MISSING
                snapshot.manifest.contentRevision
            }
            else -> return ReviewAvailability.SOURCE_MISSING
        }
        return if (item.sourceRevision == revision) ReviewAvailability.AVAILABLE else ReviewAvailability.SOURCE_CHANGED
    }

    /** Regenerates only the old default prompts and only from their original readable revision. */
    internal suspend fun repairLegacyWithinIdentity(identity: SessionIdentity, item: ReviewItemEntity): ReviewQuestion? {
        if (identity.userId != item.userId || !item.hasLegacyGeneratedPrompt()) return null
        val id = JSONObject(item.sourceRefJson).getString("id")
        return when (item.sourceNamespace) {
            "annotation" -> database.readerAnnotationDao().findAnnotation(item.userId, id)?.let { annotation ->
                val locator = ReaderLocatorCodec.decodeOrNull(annotation.locatorJson) ?: return@let null
                annotationQuestion(item.userId, annotation, locator)
            }
            "notebook", "notebook-excerpt" -> ReviewQuestionBuilder.fromPassage(item.answerSnapshot)
            else -> null
        }
    }

    private suspend fun annotationCandidate(user: Long, annotation: ReaderAnnotationEntity): ReviewCandidate? {
        if (annotation.deletedAt != null || annotation.kind == "INK") return null
        val locator = ReaderLocatorCodec.decodeOrNull(annotation.locatorJson) ?: return null
        val document = readable(user, annotation.documentId) ?: return null
        if (locator.documentId != document.id || locator.contentRevision != document.contentRevision) return null
        val question = annotationQuestion(user, annotation, locator) ?: return null
        return ReviewCandidate(
            sourceNamespace = "annotation", sourceKey = "annotation:${annotation.id}",
            sourceRefJson = ref("annotation", "id" to annotation.id), sourceTags = setOf("ANNOTATION"),
            prompt = question.prompt, answerSnapshot = question.answer,
            sourceRevision = annotation.revision.toString(), bookKey = "import:${annotation.documentId}",
            sourceTitle = document.title,
        )
    }

    private suspend fun annotationQuestion(user: Long, annotation: ReaderAnnotationEntity, locator: ReaderLocator): ReviewQuestion? {
        val quote = annotation.quoteSnapshot?.trim()?.takeIf(String::isNotBlank) ?: return null
        val original = ReviewQuestionBuilder.fromPassage(quote)
        val contextQuestion = if (original != null && quote.last() in "。.!！") original else (locator as? ReaderLocator.Reflow)?.let { reflow ->
            val text = readReflowText(user, reflow)
            val anchor = text?.let { reflow.selectionIn(it) }
            if (text != null && anchor != null) ReviewQuestionBuilder.fromPassage(text, anchor)
            else ReviewQuestionBuilder.fromSavedContext(reflow.prefix, reflow.exactQuote, reflow.suffix, reflow.canonicalStart)
        }
        val note = annotation.noteText?.trim()?.takeIf { it.isNotBlank() && it != quote }
        val sourceQuestion = contextQuestion ?: original
        return when {
            sourceQuestion == null -> note?.let { ReviewQuestionBuilder.fromPassage(it) }
            note == null || sourceQuestion.answer.contains(note) || note.length + sourceQuestion.answer.length + 8 > MAX_TEXT ->
                sourceQuestion
            else -> sourceQuestion.copy(answer = sourceQuestion.answer + "\n\n我的笔记：$note")
        }
    }

    private fun ReaderLocator.Reflow.selectionIn(text: String): IntRange? {
        if (exactQuote.isBlank()) return null
        if (canonicalEnd <= text.length && canonicalStart < canonicalEnd &&
            text.substring(canonicalStart, canonicalEnd) == exactQuote
        ) return canonicalStart until canonicalEnd
        val anchor = prefix.orEmpty() + exactQuote + suffix.orEmpty()
        val at = text.indexOf(anchor)
        if (at < 0 || text.indexOf(anchor, at + 1) >= 0) return null
        val start = at + prefix.orEmpty().length
        return start until start + exactQuote.length
    }

    private fun questionCandidate(question: QuestionEntity, tags: Set<String>): ReviewCandidate? {
        if (tags.isEmpty() || question.answer.isBlank()) return null
        return ReviewCandidate(
            sourceNamespace = "question", sourceKey = "question:${question.id}",
            sourceRefJson = ref("question", "id" to question.id.toString()), sourceTags = tags,
            prompt = questionPrompt(question), answerSnapshot = questionAnswer(question),
            sourceRevision = question.sourceRevision ?: question.createdAt.toString(),
            bookKey = question.sourceDocumentId?.let { "import:$it" }, sourceTitle = "题库",
            priority = if ("WRONG" in tags) 0 else 1,
        )
    }

    private fun practiceCandidate(record: SourcePracticeRecord, index: Int, question: SourcePracticeQuestion): ReviewCandidate? {
        val tags = record.reviewTags(index, question)
        if (tags.isEmpty()) return null
        return ReviewCandidate(
            sourceNamespace = "practice", sourceKey = "practice:${record.id}:${question.id}",
            sourceRefJson = ref("practice", "id" to record.id, "question" to question.id), sourceTags = tags,
            prompt = (listOf(question.stem) + question.options).joinToString("\n"),
            answerSnapshot = (question.answers + question.analysis).filter(String::isNotBlank).joinToString("\n"),
            sourceRevision = record.sourceRevision,
            bookKey = record.scope.bundledBookKey?.let { "course:$it" } ?: record.scope.documentId?.let { "import:$it" },
            sourceTitle = record.title, priority = if ("WRONG" in tags) 0 else 1,
        )
    }

    private fun ReviewCandidate.bounded(): ReviewCandidate? =
        takeIf { (it.prompt.isNotBlank() && it.answerSnapshot.isNotBlank()) || !it.materialExcerpt.isNullOrBlank() }?.copy(
            prompt = prompt.take(MAX_PROMPT), answerSnapshot = answerSnapshot.take(MAX_TEXT),
            sourceTitle = sourceTitle.take(MAX_PROMPT),
        )

    private suspend fun practiceReadable(userId: Long, record: SourcePracticeRecord): Boolean {
        val scope = record.scope
        if (scope.documentId?.let { readable(userId, it)?.contentRevision != record.sourceRevision } == true) return false
        return scope.relatedSources.all { source -> readable(userId, source.documentId)?.contentRevision == source.sourceRevision }
    }

    private suspend fun readable(user: Long, documentId: String) =
        database.readerCatalogDao().findDocument(documentId)?.takeIf { document ->
            document.deletedAt == null && document.importStatus == "READY" &&
                (document.kind == "BUILTIN" || document.ownerUserId == user) &&
                database.readerStateDao().find(user, documentId)?.isHidden != true
        }

    private suspend fun <T> guarded(identity: SessionIdentity, read: suspend (Long) -> T): T {
        val user = identity.userId?.takeIf { it > 0 } ?: throw ReviewIdentityExpiredException()
        val result = sessions.withCurrentIdentity(identity) {
            database.withTransaction { Result.success(read(user)) }
        } ?: throw ReviewIdentityExpiredException()
        return result.getOrThrow()
    }

    private fun ref(kind: String, vararg values: Pair<String, String>): String =
        JSONObject().put("kind", kind).apply { values.forEach { (key, value) -> put(key, value) } }.toString()

    private fun questionPrompt(q: QuestionEntity): String {
        val options = q.options?.let { encoded ->
            runCatching {
                val values = JSONArray(encoded)
                List(values.length()) { values.optString(it) }.filter(String::isNotBlank)
            }.getOrElse { listOf(encoded) }
        }.orEmpty()
        return (listOf(q.stem) + options).joinToString("\n")
    }

    private fun questionAnswer(q: QuestionEntity): String =
        listOf(q.answer, q.analysis).filter(String::isNotBlank).joinToString("\n\n")

    companion object {
        const val PAGE_SIZE = 20
        private const val MAX_PROMPT = 500
        private const val MAX_TEXT = 8000
    }
}

// Eligibility reads the last saved progress and favorites, never an in-flight answer or favorite draft.
internal fun SourcePracticeRecord.isReviewableQuestion(questionId: String): Boolean {
    val index = practice.questions.indexOfFirst { it.id == questionId }
    return practice.questions.getOrNull(index)?.let { reviewTags(index, it).isNotEmpty() } == true
}

private fun SourcePracticeRecord.reviewTags(index: Int, question: SourcePracticeQuestion): Set<String> {
    if (question.answers.none(String::isNotBlank)) return emptySet()
    val favorite = question.id in favoriteQuestionIds
    val needsReview = progress.submitted.getOrElse(index) { false } && if (question.kind == SourcePracticeKind.RECALL) {
        progress.selfReviews.getOrElse(index) { 0 } == 2
    } else {
        SourcePracticeFactory.isAnswerCorrect(question, progress.responses.getOrElse(index) { "" }) == false
    }
    if (!favorite && !needsReview) return emptySet()
    return buildSet {
        add("PRACTICE")
        if (favorite) add("MANUAL")
        if (needsReview) add("WRONG")
    }
}
