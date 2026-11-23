package com.example.myapplication.data.test

import android.content.Context
import android.util.AtomicFile
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.FavoriteEntity
import com.example.myapplication.data.local.entity.QuestionEntity
import com.example.myapplication.data.local.entity.TestAnswerEntity
import com.example.myapplication.data.local.entity.TestRecordEntity
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.data.session.SessionManager
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import com.example.myapplication.domain.reader.ReaderLocatorValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

data class OrdinaryTestSession(
    val record: TestRecordEntity,
    val questions: List<QuestionEntity>,
    val answers: List<TestAnswerEntity>,
    val currentOrdinal: Int,
    val originalSnapshot: Boolean = true,
    val availableSources: Set<Long> = emptySet(),
    val provenanceByQuestionId: Map<Long, OrdinaryQuestionProvenance> = emptyMap(),
)

data class OrdinaryHomeSummary(
    val totalBankCount: Int,
    val countsByType: Map<String, Int>,
    val submittedCount: Int,
    val inProgressTestId: Long? = null,
    val inProgressOrdinal: Int = 0,
    val inProgressAnsweredCount: Int = 0,
    val inProgressRemainingSeconds: Int = OrdinaryTestRules.durationSeconds,
)

data class OrdinaryBankEntry(
    val question: QuestionEntity,
    val provenance: OrdinaryQuestionProvenance?,
    val inActivePaper: Boolean,
)

data class OrdinaryBankCatalog(
    val summary: OrdinaryHomeSummary,
    val entries: List<OrdinaryBankEntry>,
    val activeQuestionIds: Set<Long>,
)

/** 普通卷只写旧 test_records/test_answers；不复用 quiz_papers 的评分语义。 */
class OrdinaryTestService(
    private val db: AppDatabase,
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val identityProvider: suspend () -> SessionIdentity = { SessionManager(context.applicationContext).identity.first() },
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("ordinary-test-position-v1", Context.MODE_PRIVATE)
    private val directory = File(appContext.filesDir, "ordinary-tests")

    fun observeManualFavorites(userId: Long): Flow<Set<Long>> =
        db.favoriteDao().observeByUser(userId).map { rows -> rows.filter { it.source == "MANUAL" }.map { it.questionId }.toSet() }

    fun observeWrongFavorites(userId: Long): Flow<Set<Long>> =
        db.favoriteDao().observeByUser(userId).map { rows -> rows.filter { it.source == "WRONG" }.map { it.questionId }.toSet() }

    suspend fun startOrResume(userId: Long, expectedIdentity: SessionIdentity? = null): OrdinaryTestSession = guarded(userId, expectedIdentity) { identity ->
        BuiltinOrdinaryQuestionBank.awaitStartupIfRegistered(db)
        userTransaction(userId, identity) {
            db.testRecordDao().findInProgress(userId)?.let { return@userTransaction loadWithin(userId, it.id) }
            val questions = OrdinaryTestRules.blueprint.flatMap { (type, count) ->
                val ids = db.query(SimpleSQLiteQuery(
                    "SELECT id FROM questions WHERE type=? AND enabled=1 AND owner_user_id IS NULL AND origin IN ('BUILTIN','CONTENT_PACKAGE') ORDER BY RANDOM() LIMIT 1200",
                    arrayOf<Any>(type),
                )).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
                val selected = mutableListOf<QuestionEntity>()
                for (id in ids) {
                    val question = db.questionDao().findById(id) ?: continue
                    if (OrdinaryTestRules.validQuestion(question)) selected += question
                    if (selected.size == count) break
                }
                if (selected.size != count) error("QUESTION_BANK_UNAVAILABLE: 需要选择20、判断10、填空5、应用5道结构完整的内置题")
                selected
            }
            check(OrdinaryTestRules.validPaper(questions)) { "QUESTION_BANK_UNAVAILABLE: 题型或分值不符合40题100分规则" }
            val now = clock()
            val initial = TestRecordEntity(userId = userId, paperType = "RANDOM", status = "IN_PROGRESS",
                startedAt = now, remainingSeconds = OrdinaryTestRules.durationSeconds, createdAt = now)
            val record = initial.copy(id = db.testRecordDao().insert(initial))
            // 先落独立快照；失败回滚 Room 事务。孤儿文件没有记录身份，不能用于恢复。
            writeSnapshot(record, questions, original = true)
            val answers = questions.mapIndexed { index, question -> TestAnswerEntity(testId = record.id, questionId = question.id, orderIndex = index) }
            db.testAnswerDao().insertAll(answers)
            check(preferences.edit().remove(positionKey(userId, record.id)).commit()) { "POSITION_STORAGE_FAILED" }
            loadWithin(userId, record.id)
        }
    }

    suspend fun load(userId: Long, testId: Long, expectedIdentity: SessionIdentity? = null): OrdinaryTestSession = guarded(userId, expectedIdentity) { identity ->
        userTransaction(userId, identity) { loadWithin(userId, testId) }
    }

    suspend fun save(userId: Long, testId: Long, ordinal: Int, answer: String, remainingSeconds: Int, expectedIdentity: SessionIdentity? = null): OrdinaryTestSession = guarded(userId, expectedIdentity) { identity ->
        require(answer.length <= 32_000) { "ANSWER_TOO_LARGE" }
        require(remainingSeconds >= 0) { "INVALID_REMAINING_TIME" }
        val saved = userTransaction(userId, identity) {
            val current = loadWithin(userId, testId)
            if (current.record.status != "IN_PROGRESS") return@userTransaction current
            require(ordinal in current.questions.indices) { "INVALID_QUESTION_ORDINAL" }
            val row = current.answers[ordinal]
            db.testAnswerDao().insert(row.copy(userAnswer = answer))
            val remaining = minOf(current.record.remainingSeconds ?: current.record.durationLimitSeconds,
                remainingSeconds.coerceAtMost(current.record.durationLimitSeconds))
            db.testRecordDao().update(current.record.copy(remainingSeconds = remaining))
            loadWithin(userId, testId)
        }
        if (saved.record.status != "IN_PROGRESS") return@guarded saved
        // 游标晚于答案提交；崩溃时最多滞后一页，绝不把内存答案当已入库。
        val binding = positionBinding(saved.record, saved.questions)
        requireIdentity(userId, identity)
        check(preferences.edit().putString(positionKey(userId, testId), "$binding:$ordinal").commit()) { "POSITION_STORAGE_FAILED: 答案已保存，题号未保存" }
        saved.copy(currentOrdinal = ordinal)
    }

    suspend fun submit(userId: Long, testId: Long, expectedIdentity: SessionIdentity? = null): OrdinaryTestSession = guarded(userId, expectedIdentity) { identity ->
        val submitted = userTransaction(userId, identity) {
            val current = loadWithin(userId, testId)
            if (current.record.status == "SUBMITTED") return@userTransaction current
            check(current.record.status == "IN_PROGRESS") { "TEST_NOT_IN_PROGRESS" }
            val graded = current.answers.mapIndexed { index, row ->
                val question = current.questions[index]
                val score = OrdinaryTestRules.grade(question, row.userAnswer)
                row.copy(score = score, isCorrect = if (score == question.score) 1 else if (score == 0) 0 else null)
            }
            db.testAnswerDao().insertAll(graded)
            graded.forEachIndexed { index, row ->
                if (row.score < current.questions[index].score) {
                    db.favoriteDao().insert(FavoriteEntity(userId = userId, questionId = row.questionId, source = "WRONG", createdAt = clock()))
                }
            }
            fun total(type: String) = graded.indices.filter { current.questions[it].type == type }.sumOf { graded[it].score }
            db.testRecordDao().update(current.record.copy(status = "SUBMITTED", submittedAt = clock(),
                remainingSeconds = null, usedSeconds = current.record.durationLimitSeconds -
                    (current.record.remainingSeconds ?: current.record.durationLimitSeconds),
                totalScore = graded.sumOf { it.score }, choiceScore = total("CHOICE"), judgeScore = total("JUDGE"),
                fillScore = total("FILL"), essayScore = total("ESSAY")))
            loadWithin(userId, testId)
        }
        requireIdentity(userId, identity)
        preferences.edit().remove(positionKey(userId, testId)).commit()
        submitted
    }

    suspend fun setManualFavorite(userId: Long, questionId: Long, favorite: Boolean, expectedIdentity: SessionIdentity? = null) = guarded(userId, expectedIdentity) { identity ->
        userTransaction(userId, identity) {
            val question = db.questionDao().findById(questionId) ?: error("QUESTION_UNAVAILABLE")
            require(question.ownerUserId == null || question.ownerUserId == userId) { "QUESTION_ACCESS_DENIED" }
            if (favorite) db.favoriteDao().insertManualOwned(userId, questionId, clock())
            else db.favoriteDao().delete(userId, questionId, "MANUAL")
        }
    }

    suspend fun removeWrongFavorite(userId: Long, questionId: Long, expectedIdentity: SessionIdentity? = null) = guarded(userId, expectedIdentity) { identity ->
        userTransaction(userId, identity) {
            db.favoriteDao().delete(userId, questionId, "WRONG")
        }
    }

    suspend fun findLatestSubmittedLocation(
        userId: Long,
        questionId: Long,
        expectedIdentity: SessionIdentity? = null,
    ) = guarded(userId, expectedIdentity) { _ ->
        db.questionDao().findLatestSubmittedAnswerLocation(userId, questionId)
    }

    suspend fun loadHomeSummary(userId: Long, expectedIdentity: SessionIdentity? = null): OrdinaryHomeSummary = guarded(userId, expectedIdentity) { identity ->
        BuiltinOrdinaryQuestionBank.awaitStartupIfRegistered(db)
        userTransaction(userId, identity) {
            buildHomeSummary(userId).first
        }
    }

    suspend fun loadBankCatalog(userId: Long, expectedIdentity: SessionIdentity? = null): OrdinaryBankCatalog = guarded(userId, expectedIdentity) { identity ->
        BuiltinOrdinaryQuestionBank.awaitStartupIfRegistered(db)
        val provenanceMap = loadProvenanceMap()
        userTransaction(userId, identity) {
            val (summary, activeIds) = buildHomeSummary(userId)
            val bound = db.questionDao().listBoundQuestions(BuiltinOrdinaryQuestionBank.PACKAGE_ID)
            val questions = if (bound.isNotEmpty()) bound else db.questionDao().listBuiltInQuestions()
            val sortedEntries = questions.map { q ->
                OrdinaryBankEntry(
                    question = q,
                    provenance = provenanceMap[q.id],
                    inActivePaper = q.id in activeIds,
                )
            }.sortedWith(compareBy<OrdinaryBankEntry> { it.provenance?.designId ?: Int.MAX_VALUE }.thenBy { it.question.id })
            OrdinaryBankCatalog(summary = summary, entries = sortedEntries, activeQuestionIds = activeIds)
        }
    }

    private suspend fun buildHomeSummary(userId: Long): Pair<OrdinaryHomeSummary, Set<Long>> {
        val questionDao = db.questionDao()
        val bound = questionDao.listBoundQuestions(BuiltinOrdinaryQuestionBank.PACKAGE_ID)
        val pool = if (bound.isNotEmpty()) bound else questionDao.listBuiltInQuestions()
        val counts = pool.groupingBy { it.type }.eachCount()
        val submittedCount = questionDao.countSubmittedTests(userId)
        val activeRecord = db.testRecordDao().findInProgress(userId)
        if (activeRecord == null) {
            return OrdinaryHomeSummary(
                totalBankCount = pool.size,
                countsByType = counts,
                submittedCount = submittedCount,
            ) to emptySet()
        }
        val activeSession = runCatching { loadWithin(userId, activeRecord.id) }.getOrNull()
        val answeredCount = activeSession?.answers?.count { hasEffectiveAnswer(it.userAnswer) } ?: 0
        val activeIds = activeSession?.questions?.map { it.id }?.toSet().orEmpty()
        return OrdinaryHomeSummary(
            totalBankCount = pool.size,
            countsByType = counts,
            submittedCount = submittedCount,
            inProgressTestId = activeRecord.id,
            inProgressOrdinal = activeSession?.currentOrdinal ?: 0,
            inProgressAnsweredCount = answeredCount,
            inProgressRemainingSeconds = activeRecord.remainingSeconds ?: activeRecord.durationLimitSeconds,
        ) to activeIds
    }

    private suspend fun loadProvenanceMap(): Map<Long, OrdinaryQuestionProvenance> =
        BuiltinOrdinaryQuestionBank.loadProvenanceByQuestionId(db) { path ->
            appContext.assets.open(path).use { it.readBytes() }
        }

    private fun hasEffectiveAnswer(raw: String?): Boolean {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        return if (trimmed.startsWith("[")) {
            OrdinaryTestRules.arrayStrings(trimmed).any { it.isNotBlank() }
        } else {
            true
        }
    }

    suspend fun currentIdentity(): SessionIdentity = identityProvider()

    private suspend fun requireIdentity(userId: Long, identity: SessionIdentity) {
        check(identity.userId == userId && identityProvider() == identity) { "SESSION_CHANGED" }
        require(userId > 0 && db.userDao().findById(userId) != null) { "USER_UNAVAILABLE" }
    }

    private suspend fun <T> guarded(userId: Long, expectedIdentity: SessionIdentity?, block: suspend (SessionIdentity) -> T): T {
        val identity = expectedIdentity ?: identityProvider()
        requireIdentity(userId, identity)
        return withContext(Dispatchers.IO) {
            requireIdentity(userId, identity)
            block(identity)
        }
    }

    private suspend fun <T> userTransaction(userId: Long, identity: SessionIdentity, block: suspend () -> T): T = db.withTransaction {
        requireIdentity(userId, identity)
        val result = block()
        requireIdentity(userId, identity) // failure here rolls back answers, grades, WRONG and MANUAL together
        result
    }

    private suspend fun loadWithin(userId: Long, testId: Long): OrdinaryTestSession {
        val record = db.testRecordDao().findById(testId) ?: error("TEST_UNAVAILABLE")
        require(record.userId == userId) { "TEST_ACCESS_DENIED" }
        val answers = db.testAnswerDao().findByTest(testId)
        val firstOrdinal = answers.firstOrNull()?.orderIndex
        check(firstOrdinal in listOf(0, 1) && answers.size == 40 &&
            answers.map { it.orderIndex } == answers.indices.map { it + firstOrdinal!! }) { "TEST_ANSWER_ORDER_INVALID" }
        val file = snapshotFile(record)
        val snapshot = if (file.exists() || File(file.path + ".bak").exists()) readSnapshot(record, answers) else {
            // 旧记录没有独立题面可恢复，明确保留降级标记，不冒充原始试卷。
            val legacy = answers.map { db.questionDao().findById(it.questionId) ?: error("QUESTION_UNAVAILABLE") }
            check(OrdinaryTestRules.validPaper(legacy)) { "TEST_PAPER_INVALID" }
            writeSnapshot(record, legacy, original = false)
            legacy to false
        }
        val questions = snapshot.first
        check(OrdinaryTestRules.validPaper(questions)) { "TEST_PAPER_INVALID" }
        check(questions.map { it.id } == answers.map { it.questionId }) { "PAPER_SNAPSHOT_IDENTITY_MISMATCH" }
        check(questions.all { it.ownerUserId == null || it.ownerUserId == userId }) { "QUESTION_ACCESS_DENIED" }
        val binding = positionBinding(record, questions)
        val stored = preferences.getString(positionKey(userId, testId), null)
        val ordinal = if (record.status == "IN_PROGRESS" && stored?.startsWith("$binding:") == true)
            stored.substringAfterLast(':').toIntOrNull()?.takeIf { it in questions.indices } ?: 0 else 0
        val available = questions.filter { question ->
            val documentId = question.sourceDocumentId
            if (documentId.isNullOrBlank() || question.sourceLocatorJson.isNullOrBlank()) false else {
                val locator = runCatching { ReaderLocatorCodec.decode(question.sourceLocatorJson) }.getOrNull()
                val document = db.readerCatalogDao().findDocument(documentId)
                document != null && document.deletedAt == null && (document.kind == "BUILTIN" || document.ownerUserId == userId) &&
                    locator != null && ReaderLocatorValidator.validateForDocument(locator, documentId, document.contentRevision).isValid &&
                    (question.sourceRevision == null || document.contentRevision == question.sourceRevision) &&
                    db.readerCatalogDao().findSections(documentId, document.contentRevision).any { it.id == locator.sectionId }
            }
        }.map { it.id }.toSet()
        return OrdinaryTestSession(record, questions, answers, ordinal, snapshot.second, available, loadProvenanceMap())
    }

    private fun snapshotFile(record: TestRecordEntity) = File(directory, "u${record.userId}/t${record.id}/snapshot-v1.json")
    private fun positionKey(userId: Long, testId: Long) = "u$userId:t$testId"
    private fun positionBinding(record: TestRecordEntity, questions: List<QuestionEntity>): String {
        val ids = questions.joinToString(",") { it.id.toString() }
        val digest = MessageDigest.getInstance("SHA-256").digest(ids.toByteArray()).joinToString("") { "%02x".format(it) }
        return "${record.startedAt}:$digest"
    }

    private fun writeSnapshot(record: TestRecordEntity, questions: List<QuestionEntity>, original: Boolean) {
        val root = buildJsonObject {
            put("version", 1); put("userId", record.userId); put("testId", record.id); put("startedAt", record.startedAt)
            put("original", original)
            put("questions", JsonArray(questions.map { q -> buildJsonObject {
                put("id", q.id); put("type", q.type); put("stem", q.stem); put("answer", q.answer)
                put("analysis", q.analysis); put("score", q.score); put("createdAt", q.createdAt); put("origin", q.origin)
                q.chapterId?.let { put("chapterId", it) }; q.ownerUserId?.let { put("ownerUserId", it) }
                q.options?.let { put("options", it) }; q.synonyms?.let { put("synonyms", it) }
                q.knowledgePoint?.let { put("knowledgePoint", it) }; q.sourceDocumentId?.let { put("sourceDocumentId", it) }
                q.sourceLocatorJson?.let { put("sourceLocatorJson", it) }; q.sourceExcerpt?.let { put("sourceExcerpt", it) }
                q.sourceRevision?.let { put("sourceRevision", it) }
            } }))
        }
        val bytes = root.toString().toByteArray()
        require(bytes.size <= 4 * 1024 * 1024) { "PAPER_SNAPSHOT_TOO_LARGE" }
        val target = snapshotFile(record)
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "PAPER_SNAPSHOT_STORAGE_FAILED" }
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }

    private fun readSnapshot(record: TestRecordEntity, answers: List<TestAnswerEntity>): Pair<List<QuestionEntity>, Boolean> {
        val target = snapshotFile(record)
        check(target.length() <= 4 * 1024 * 1024 && File(target.path + ".bak").length() <= 4 * 1024 * 1024) { "PAPER_SNAPSHOT_TOO_LARGE" }
        val atomic = AtomicFile(target)
        val bytes = atomic.openRead().use { stream ->
            val value = stream.readBytes()
            check(value.size <= 4 * 1024 * 1024) { "PAPER_SNAPSHOT_TOO_LARGE" }
            value
        }
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        check(root["version"]?.jsonPrimitive?.int == 1 &&
            root["userId"]?.jsonPrimitive?.long == record.userId &&
            root["testId"]?.jsonPrimitive?.long == record.id &&
            root["startedAt"]?.jsonPrimitive?.long == record.startedAt) { "PAPER_SNAPSHOT_IDENTITY_MISMATCH" }
        val questions = root.getValue("questions").jsonArray.map { element ->
            val q = element.jsonObject
            fun text(key: String) = q[key]?.jsonPrimitive?.contentOrNull
            QuestionEntity(id = q.getValue("id").jsonPrimitive.long, type = text("type")!!,
                chapterId = q["chapterId"]?.jsonPrimitive?.long, stem = text("stem")!!, options = text("options"),
                answer = text("answer")!!, synonyms = text("synonyms"), knowledgePoint = text("knowledgePoint"),
                analysis = text("analysis")!!, score = q.getValue("score").jsonPrimitive.int,
                createdAt = q.getValue("createdAt").jsonPrimitive.long, ownerUserId = q["ownerUserId"]?.jsonPrimitive?.long,
                origin = text("origin") ?: "BUILTIN", sourceDocumentId = text("sourceDocumentId"),
                sourceLocatorJson = text("sourceLocatorJson"), sourceExcerpt = text("sourceExcerpt"),
                sourceRevision = text("sourceRevision"))
        }
        check(questions.map { it.id } == answers.map { it.questionId }) { "PAPER_SNAPSHOT_IDENTITY_MISMATCH" }
        return questions to (root["original"]?.jsonPrimitive?.booleanOrNull ?: false)
    }
}
