package com.example.myapplication.data.test

import android.content.res.AssetManager
import androidx.room.withTransaction
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.ContentPackageEntity
import com.example.myapplication.data.local.entity.ContentQuestionBindingEntity
import com.example.myapplication.data.local.entity.QuestionEntity
import com.example.myapplication.data.reader.content.ContentPackageQuestionGrading
import java.security.MessageDigest
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class OrdinaryQuestionProvenance(
    val designId: Int,
    val questionKey: String,
    val sourceOrigin: String,
    val sourceId: Int?,
    val reviewStatus: String,
    val chapterTitle: String,
    val points: Int,
)

data class ParsedBankItem(
    val provenance: OrdinaryQuestionProvenance,
    val dbType: String,
    val stem: String,
    val optionsJson: String?,
    val answerValue: String,
    val synonymsValue: String?,
    val knowledgePoint: String,
    val analysis: String,
    val score: Int,
    val mappedChapterKey: String?,
) {
    fun toQuestionEntity(chapterId: Long?, createdAt: Long): QuestionEntity = QuestionEntity(
        type = dbType,
        chapterId = chapterId,
        stem = stem,
        options = optionsJson,
        answer = answerValue,
        synonyms = synonymsValue,
        knowledgePoint = knowledgePoint,
        analysis = analysis,
        score = score,
        enabled = true,
        createdAt = createdAt,
        ownerUserId = null,
        origin = "BUILTIN",
    )
}

data class ParsedOrdinaryBank(
    val bankId: String,
    val manifestHash: String,
    val countsByType: Map<String, Int>,
    val items: List<ParsedBankItem>,
) {
    val byQuestionKey: Map<String, OrdinaryQuestionProvenance> by lazy {
        items.associate { it.provenance.questionKey to it.provenance }
    }
}

data class OrdinaryBankImportReport(
    val totalCount: Int,
    val insertedCount: Int,
    val updatedCount: Int,
    val skipped: Boolean,
    val countsByType: Map<String, Int>,
    val packageHash: String,
)

/**
 * 200 道随机测试BuiltIn题库导入与稳定题键映射。
 *
 * 稳定题键 `q:<id>` 存储在 `content_question_bindings (package_id = zhenzhi-acupuncture-20261123-design)`，
 * 与 Room 自增 `questions.id` 解耦；重复启动/刷新时只原地 `UPDATE` 绑定行，绝不对 `questions` 执行 `REPLACE`，
 * 从而保留既有试卷快照、历史成绩、错题与手动收藏外键引用。
 */
object BuiltinOrdinaryQuestionBank {
    const val PACKAGE_ID = "zhenzhi-acupuncture-20261123-design"
    const val ASSET_PATH = "question-banks/acupuncture-200.json"
    const val EXPECTED_TOTAL = 200
    const val EXPECTED_CHOICE = 96
    const val EXPECTED_JUDGE = 46
    const val EXPECTED_FILL = 29
    const val EXPECTED_ESSAY = 29
    const val EXPECTED_MEMBER3 = 60
    const val EXPECTED_ORIGINAL = 140

    private val cachedCatalog = AtomicReference<ParsedOrdinaryBank?>(null)
    private val startupLock = Any()
    private val startupGates = WeakHashMap<AppDatabase, CompletableDeferred<Unit>>()

    fun registerStartup(database: AppDatabase): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        synchronized(startupLock) {
            startupGates[database] = gate
        }
        return gate
    }

    suspend fun awaitStartupIfRegistered(database: AppDatabase) {
        val gate = synchronized(startupLock) { startupGates[database] } ?: return
        gate.await()
    }

    suspend fun importOnStartup(
        database: AppDatabase,
        assets: AssetManager,
        clock: () -> Long = System::currentTimeMillis,
    ): OrdinaryBankImportReport = importIfNeeded(
        database = database,
        readAsset = { path -> assets.open(path).use { it.readBytes() } },
        clock = clock,
    )

    suspend fun importIfNeeded(
        database: AppDatabase,
        readAsset: (String) -> ByteArray,
        clock: () -> Long = System::currentTimeMillis,
    ): OrdinaryBankImportReport {
        val bytes = readAsset(ASSET_PATH)
        val parsed = parseCatalog(bytes)
        val questionDao = database.questionDao()
        val jobDao = database.readerJobDao()
        val chapterDao = database.chapterDao()

        return database.withTransaction {
            val existingPackage = jobDao.findPackage(PACKAGE_ID)
            val existingBindings = questionDao.findBindingsByPackage(PACKAGE_ID)
                .associate { it.questionKey to it.questionId }
            val enabledBound = if (existingBindings.size == EXPECTED_TOTAL) {
                questionDao.countEnabledBoundQuestions(PACKAGE_ID)
            } else {
                0
            }
            if (existingPackage?.status == "APPLIED" &&
                existingPackage.manifestHash == parsed.manifestHash &&
                existingBindings.size == EXPECTED_TOTAL &&
                enabledBound == EXPECTED_TOTAL
            ) {
                return@withTransaction OrdinaryBankImportReport(
                    totalCount = parsed.items.size,
                    insertedCount = 0,
                    updatedCount = 0,
                    skipped = true,
                    countsByType = parsed.countsByType,
                    packageHash = parsed.manifestHash,
                )
            }

            val now = clock()
            val chaptersByKey = chapterDao.getAll().associateBy { it.contentKey }
            if (existingPackage == null) {
                jobDao.insertPackage(
                    ContentPackageEntity(
                        packageId = PACKAGE_ID,
                        version = 1,
                        manifestHash = parsed.manifestHash,
                        status = "APPLYING",
                        appliedAt = null,
                    ),
                )
            } else {
                jobDao.updatePackage(
                    packageId = PACKAGE_ID,
                    version = maxOf(1, existingPackage.version),
                    manifestHash = parsed.manifestHash,
                    status = "APPLYING",
                    appliedAt = now,
                )
            }

            var inserted = 0
            var updated = 0
            for (item in parsed.items) {
                val chapterId = item.mappedChapterKey?.let { chaptersByKey[it]?.id }
                val boundQuestionId = existingBindings[item.provenance.questionKey]
                val existingQuestion = if (boundQuestionId != null) questionDao.findById(boundQuestionId) else null
                if (existingQuestion != null) {
                    val changed = existingQuestion.type != item.dbType ||
                        existingQuestion.chapterId != chapterId ||
                        existingQuestion.stem != item.stem ||
                        existingQuestion.options != item.optionsJson ||
                        existingQuestion.answer != item.answerValue ||
                        existingQuestion.synonyms != item.synonymsValue ||
                        existingQuestion.knowledgePoint != item.knowledgePoint ||
                        existingQuestion.analysis != item.analysis ||
                        existingQuestion.score != item.score ||
                        !existingQuestion.enabled ||
                        existingQuestion.origin != "BUILTIN" ||
                        existingQuestion.ownerUserId != null
                    if (changed) {
                        val rows = questionDao.updateQuestionInPlace(
                            id = existingQuestion.id,
                            type = item.dbType,
                            chapterId = chapterId,
                            stem = item.stem,
                            options = item.optionsJson,
                            answer = item.answerValue,
                            synonyms = item.synonymsValue,
                            knowledgePoint = item.knowledgePoint,
                            analysis = item.analysis,
                            score = item.score,
                            enabled = true,
                            origin = "BUILTIN",
                            sourceDocumentId = existingQuestion.sourceDocumentId,
                            sourceLocatorJson = existingQuestion.sourceLocatorJson,
                            sourceExcerpt = existingQuestion.sourceExcerpt,
                            sourceRevision = existingQuestion.sourceRevision,
                        )
                        check(rows == 1) { "Failed to update bound question ${existingQuestion.id}" }
                        updated++
                    }
                } else {
                    val newId = questionDao.insertQuestion(item.toQuestionEntity(chapterId, now))
                    check(newId > 0) { "Failed to insert question ${item.provenance.questionKey}" }
                    if (boundQuestionId != null) {
                        check(questionDao.updateBinding(PACKAGE_ID, item.provenance.questionKey, newId) == 1)
                    } else {
                        questionDao.insertBinding(
                            ContentQuestionBindingEntity(
                                packageId = PACKAGE_ID,
                                questionKey = item.provenance.questionKey,
                                questionId = newId,
                            ),
                        )
                    }
                    inserted++
                }
            }

            check(
                jobDao.updatePackage(
                    packageId = PACKAGE_ID,
                    version = maxOf(1, existingPackage?.version ?: 1),
                    manifestHash = parsed.manifestHash,
                    status = "APPLIED",
                    appliedAt = now,
                ) == 1,
            )
            OrdinaryBankImportReport(
                totalCount = parsed.items.size,
                insertedCount = inserted,
                updatedCount = updated,
                skipped = false,
                countsByType = parsed.countsByType,
                packageHash = parsed.manifestHash,
            )
        }
    }

    suspend fun loadProvenanceByQuestionId(
        database: AppDatabase,
        readAsset: (String) -> ByteArray,
    ): Map<Long, OrdinaryQuestionProvenance> {
        val catalog = runCatching { getOrParseCatalog(readAsset) }.getOrNull() ?: return emptyMap()
        val bindings = runCatching { database.questionDao().findBindingsByPackage(PACKAGE_ID) }.getOrNull().orEmpty()
        val byKey = catalog.byQuestionKey
        return buildMap(bindings.size) {
            for (row in bindings) {
                val meta = byKey[row.questionKey] ?: continue
                put(row.questionId, meta)
            }
        }
    }

    fun getOrParseCatalog(readAsset: (String) -> ByteArray): ParsedOrdinaryBank {
        cachedCatalog.get()?.let { return it }
        val parsed = parseCatalog(readAsset(ASSET_PATH))
        cachedCatalog.compareAndSet(null, parsed)
        return cachedCatalog.get() ?: parsed
    }

    fun parseCatalog(bytes: ByteArray): ParsedOrdinaryBank {
        val hash = sha256(bytes)
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        require(root.intOrNull("schemaVersion") == 1) { "Unsupported bank schemaVersion" }
        val bankId = root.string("bankId")
        require(bankId == PACKAGE_ID) { "Unexpected bankId: $bankId" }
        val array = root["questions"]?.jsonArray ?: error("Missing questions array")
        require(array.size == EXPECTED_TOTAL) { "Expected $EXPECTED_TOTAL questions, found ${array.size}" }

        val items = array.map { parseItem(it.jsonObject) }
        require(items.map { it.provenance.designId }.distinct().size == EXPECTED_TOTAL) { "Duplicate design IDs" }
        require(items.map { it.stem.trim() }.distinct().size == EXPECTED_TOTAL) { "Duplicate question stems" }

        val counts = items.groupingBy { it.dbType }.eachCount()
        require(counts["CHOICE"] == EXPECTED_CHOICE) { "Expected $EXPECTED_CHOICE CHOICE, got ${counts["CHOICE"]}" }
        require(counts["JUDGE"] == EXPECTED_JUDGE) { "Expected $EXPECTED_JUDGE JUDGE, got ${counts["JUDGE"]}" }
        require(counts["FILL"] == EXPECTED_FILL) { "Expected $EXPECTED_FILL FILL, got ${counts["FILL"]}" }
        require(counts["ESSAY"] == EXPECTED_ESSAY) { "Expected $EXPECTED_ESSAY ESSAY, got ${counts["ESSAY"]}" }

        val member3Count = items.count { it.provenance.sourceOrigin == "member3" }
        val originalCount = items.count { it.provenance.sourceOrigin == "design-20261123" }
        require(member3Count == EXPECTED_MEMBER3 && originalCount == EXPECTED_ORIGINAL) {
            "Unexpected provenance counts: member3=$member3Count, original=$originalCount"
        }

        return ParsedOrdinaryBank(
            bankId = bankId,
            manifestHash = hash,
            countsByType = counts,
            items = items,
        )
    }

    private fun parseItem(obj: JsonObject): ParsedBankItem {
        val id = obj.intOrNull("id") ?: error("Question missing integer id")
        val rawType = obj.string("type")
        val chapter = obj.string("chapter")
        val stem = obj.string("stem")
        val rawOptions = obj.optionalString("options")
        val rawAnswer = obj.string("answer")
        val rawKeywords = obj.optionalString("keywords")
        val points = obj.intOrNull("points") ?: error("Question $id missing points")
        val knowledge = obj.string("knowledge")
        val explanation = obj.string("explanation")
        val origin = obj.string("origin")
        val sourceId = obj.intOrNull("sourceId")
        val review = obj.optionalString("review").ifBlank { "source" }

        val dbType = when (rawType) {
            "choice" -> "CHOICE"
            "tf" -> "JUDGE"
            "fill" -> "FILL"
            "app" -> "ESSAY"
            else -> error("Unsupported question type '$rawType' at id=$id")
        }
        val expectedPoints = if (dbType in setOf("CHOICE", "JUDGE")) 2 else 4
        require(points == expectedPoints) { "Question $id expected $expectedPoints pts, got $points" }

        var optionsJson: String? = null
        var synonymsValue: String? = null
        val answerValue: String = when (dbType) {
            "CHOICE" -> {
                val parts = rawOptions.split('|').map(String::trim)
                require(parts.size == 4 && parts.all(String::isNotBlank)) { "Invalid choice options for id=$id" }
                optionsJson = buildJsonObject {
                    put("A", parts[0])
                    put("B", parts[1])
                    put("C", parts[2])
                    put("D", parts[3])
                }.toString()
                val letter = rawAnswer.trim().uppercase()
                require(letter in listOf("A", "B", "C", "D")) { "Invalid choice answer '$rawAnswer' for id=$id" }
                letter
            }

            "JUDGE" -> {
                val normalized = rawAnswer.trim()
                require(normalized == "对" || normalized == "错") { "Invalid tf answer '$rawAnswer' for id=$id" }
                normalized
            }

            "FILL" -> {
                val answerParts = rawAnswer.split("||").map(String::trim)
                require(answerParts.isNotEmpty() && answerParts.all(String::isNotBlank)) { "Invalid fill answer for id=$id" }
                val keywordGroups = rawKeywords.split("||")
                val synMatrix = JsonArray(
                    answerParts.indices.map { idx ->
                        val group = keywordGroups.getOrElse(idx) { "" }
                        val alternatives = (listOf(answerParts[idx]) + group.split('#'))
                            .map(String::trim)
                            .filter { it.isNotBlank() && OrdinaryTestRules.normalize(it).isNotEmpty() }
                            .distinct()
                        JsonArray(alternatives.map(::JsonPrimitive))
                    },
                )
                synonymsValue = synMatrix.toString()
                JsonArray(answerParts.map(::JsonPrimitive)).toString()
            }

            "ESSAY" -> {
                val answerParts = rawAnswer.split('|').map(String::trim)
                require(answerParts.isNotEmpty() && answerParts.all(String::isNotBlank)) { "Invalid app answer for id=$id" }
                require(answerParts.distinct().size == answerParts.size) { "Duplicate essay answer points for id=$id" }
                val weights = distributeWeights(expectedPoints, answerParts.size)
                val keywordGroups = rawKeywords.split('|')
                val answerJsonArray = JsonArray(answerParts.map(::JsonPrimitive))
                val rubricArray = JsonArray(
                    answerParts.indices.map { idx ->
                        val accepted = (listOf(answerParts[idx]) + keywordGroups.getOrElse(idx) { "" }.split('#'))
                            .map(String::trim)
                            .filter { it.isNotBlank() && OrdinaryTestRules.normalize(it).isNotEmpty() }
                            .distinct()
                        buildJsonObject {
                            put("key", "p${idx + 1}")
                            put("weight", weights[idx])
                            put("accepted", JsonArray(accepted.map(::JsonPrimitive)))
                            put("negatedBy", JsonArray(emptyList()))
                        }
                    },
                )
                synonymsValue = ContentPackageQuestionGrading.encode(
                    buildJsonObject {
                        put("type", "ESSAY")
                        put("score", expectedPoints)
                        put("answer", answerJsonArray)
                        put("rubric", rubricArray)
                    },
                )
                answerJsonArray.toString()
            }

            else -> error("Unreachable")
        }

        val item = ParsedBankItem(
            provenance = OrdinaryQuestionProvenance(
                designId = id,
                questionKey = "q:$id",
                sourceOrigin = origin,
                sourceId = sourceId,
                reviewStatus = review,
                chapterTitle = chapter,
                points = points,
            ),
            dbType = dbType,
            stem = stem,
            optionsJson = optionsJson,
            answerValue = answerValue,
            synonymsValue = synonymsValue,
            knowledgePoint = knowledge,
            analysis = explanation,
            score = expectedPoints,
            mappedChapterKey = mapChapterKey(chapter),
        )
        require(OrdinaryTestRules.validQuestion(item.toQuestionEntity(chapterId = null, createdAt = 1L).copy(id = 1L))) {
            "Question $id failed OrdinaryTestRules.validQuestion"
        }
        return item
    }

    internal fun distributeWeights(totalScore: Int, count: Int): List<Int> {
        require(totalScore > 0 && count in 1..totalScore)
        val base = totalScore / count
        val remainder = totalScore % count
        return List(count) { index -> base + if (index < remainder) 1 else 0 }
    }

    private fun mapChapterKey(chapterTitle: String): String? = when {
        chapterTitle.startsWith("第二章") || chapterTitle.contains("经络") -> "meridian-overview"
        chapterTitle.startsWith("第三章") || chapterTitle.contains("腧穴总论") -> "acupoint-basics"
        chapterTitle.startsWith("第四章") || chapterTitle.contains("十二经脉") -> "twelve-meridians"
        chapterTitle.startsWith("第六章") || chapterTitle.contains("刺法") -> "needling-basics"
        chapterTitle.startsWith("第七章") || chapterTitle.contains("治疗原则") -> "treatment-principles"
        else -> null
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?: error("Missing string field '$key'")

    private fun JsonObject.optionalString(key: String): String {
        val element: JsonElement = this[key] ?: return ""
        return (element as? JsonPrimitive)?.content.orEmpty()
    }

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
}
