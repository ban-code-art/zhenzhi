package com.example.myapplication.data.quiz

import kotlinx.serialization.json.*
import com.example.myapplication.domain.reader.sha256Hex

/** Explicit JSON contract; no serialization compiler plugin or official paper schema is involved. */
internal object SourcePracticeArchiveCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(record: SourcePracticeRecord): String = buildJsonObject {
        put("schemaVersion", 1)
        put("record", buildJsonObject {
            put("id", record.id); put("ownerUserId", record.ownerUserId)
            put("practice", practice(record.practice)); put("scope", scope(record.scope))
            put("progress", buildJsonObject {
                put("responses", strings(record.progress.responses))
                put("submitted", JsonArray(record.progress.submitted.map(::JsonPrimitive)))
                put("selfReviews", JsonArray(record.progress.selfReviews.map(::JsonPrimitive)))
                put("index", record.progress.index); put("summary", record.progress.summary)
            })
            put("favoriteQuestionIds", strings(record.favoriteQuestionIds.sorted()))
            put("createdAt", record.createdAt); put("updatedAt", record.updatedAt); put("batchNumber", record.batchNumber)
        })
    }.toString()

    fun portable(record: SourcePracticeRecord): Pair<String, ByteArray> {
        val stripped = Json.parseToJsonElement(encode(record)).jsonObject
        val item = stripped.getValue("record").jsonObject
        val originalScope = item.getValue("scope").jsonObject
        val bindings = originalScope.getValue("relatedSources").jsonArray.map { binding ->
            JsonObject(binding.jsonObject.filterKeys { it !in privateBindingFields })
        }
        val scope = JsonObject(
            originalScope.filterKeys { it !in privateBindingFields && it != "documentOwnerUserId" } +
                ("relatedSources" to JsonArray(bindings)),
        )
        val body = buildJsonObject {
            put("schemaVersion", 1)
            put("record", JsonObject(item.filterKeys { it != "id" && it != "ownerUserId" } + ("scope" to scope)))
        }.toString().encodeToByteArray()
        return sha256Hex(body.decodeToString()) to body
    }

    fun readPortable(id: String, bytes: ByteArray): SourcePracticeRecord {
        require(id.matches(Regex("[0-9a-f]{64}")) && bytes.size in 1..(8 * 1024 * 1024))
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        require(sha256Hex(text) == id)
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.keys == setOf("schemaVersion", "record") && root.int("schemaVersion") == 1)
        val item = root.objectValue("record")
        require("id" !in item && "ownerUserId" !in item)
        val local = buildJsonObject {
            put("schemaVersion", 1)
            put("record", JsonObject(item + mapOf("id" to JsonPrimitive(id), "ownerUserId" to JsonPrimitive(1L))))
        }
        val record = decode(local.toString())
        require(portable(record).second.contentEquals(bytes))
        return record
    }

    private val privateBindingFields = setOf("ownerUserId", "accessEpoch", "metadataRevision")

    fun decode(value: String): SourcePracticeRecord {
        try {
            val root = json.parseToJsonElement(value).jsonObject
            require(root.int("schemaVersion") == 1)
            val item = root.objectValue("record")
            val p = item.objectValue("practice")
            val practice = SourcePractice(p.string("id"), p.string("title"), p.string("sourceRevision"),
                SourcePracticeOrigin.valueOf(p.string("origin")), p.array("questions").map { element ->
                    val q = element.jsonObject
                    SourcePracticeQuestion(q.string("id"), SourcePracticeKind.valueOf(q.string("kind")), q.string("stem"),
                        q.strings("options"), q.strings("answers"), q.string("analysis"), q.strings("sourceQuotes"),
                        q.string("sourceLabel"), q.strings("sectionIds"), q.int("score"))
                }, p.string("coverageNote"), p.int("excludedQuestionCount"))
            val s = item.objectValue("scope")
            val scope = SourcePracticeScope(s.optionalString("documentId"), s.strings("sectionIds"),
                s.optionalString("bundledBookKey"), s.optionalString("bundledChapterKey"), s.string("title"),
                s.optionalLong("documentOwnerUserId"), s.optionalLong("accessEpoch"), s.optionalLong("metadataRevision"),
                s.optionalString("extractorVersion"), s.array("relatedSources").map {
                    val b = it.jsonObject
                    SourcePracticeDocumentBinding(b.string("documentId"), b.string("sourceRevision"), b.strings("sectionIds"),
                        b.optionalLong("ownerUserId"), b.optionalLong("accessEpoch"), b.optionalLong("metadataRevision"),
                        b.optionalString("extractorVersion"))
                }, s.string("sourceRevision"))
            val state = item.objectValue("progress")
            val progress = SourcePracticeProgress(state.strings("responses"), state.array("submitted").map { it.jsonPrimitive.boolean },
                state.array("selfReviews").map { it.jsonPrimitive.int }, state.int("index"), state.boolean("summary"))
            val record = SourcePracticeRecord(item.string("id"), item.long("ownerUserId"), practice, scope, progress,
                item.strings("favoriteQuestionIds").toSet(), item.long("createdAt"), item.long("updatedAt"), item.int("batchNumber"))
            validate(record)
            return record
        } catch (error: SourcePracticeArchiveException) { throw error }
        catch (error: Exception) { throw SourcePracticeArchiveException("这份随堂记录不完整，暂时无法恢复。", error) }
    }

    fun validate(record: SourcePracticeRecord) {
        val p = record.practice
        if (!record.id.matches(Regex("[a-f0-9]{64}")) || record.ownerUserId <= 0 || p.id.isBlank() || p.sourceRevision.isBlank() ||
            p.questions.size > 512 || p.questions.map { it.id }.distinct().size != p.questions.size ||
            p.excludedQuestionCount < 0 || p.questions.any { question ->
                question.id.isBlank() || question.stem.isBlank() || question.answers.isEmpty() || question.answers.any(String::isBlank) ||
                    question.sourceQuotes.isEmpty() || question.sourceQuotes.any(String::isBlank) || question.score <= 0 ||
                    (question.kind == SourcePracticeKind.CHOICE && (question.options.size < 2 || question.options.any(String::isBlank) ||
                        question.options.distinct().size != question.options.size || question.answers.any { it !in question.options }))
            } ||
            record.createdAt <= 0 || record.updatedAt < record.createdAt || record.batchNumber < 1 ||
            (record.scope.documentOwnerUserId != null && record.scope.documentOwnerUserId != record.ownerUserId) ||
            record.scope.relatedSources.any { it.ownerUserId != null && it.ownerUserId != record.ownerUserId } ||
            record.scope.normalized(p) != record.scope || record.progress.normalized(p) != record.progress ||
            record.favoriteQuestionIds.any { favorite -> p.questions.none { it.id == favorite } })
            throw SourcePracticeArchiveException("这份随堂记录的题目或来源校验失败。")
    }

    internal fun scope(value: SourcePracticeScope): JsonObject = buildJsonObject {
        putNullable("documentId", value.documentId); put("sectionIds", strings(value.sectionIds))
        putNullable("bundledBookKey", value.bundledBookKey); putNullable("bundledChapterKey", value.bundledChapterKey)
        put("title", value.title); putNullable("documentOwnerUserId", value.documentOwnerUserId)
        putNullable("accessEpoch", value.accessEpoch); putNullable("metadataRevision", value.metadataRevision)
        putNullable("extractorVersion", value.extractorVersion); put("sourceRevision", value.sourceRevision)
        put("relatedSources", JsonArray(value.relatedSources.map { binding -> buildJsonObject {
            put("documentId", binding.documentId); put("sourceRevision", binding.sourceRevision)
            put("sectionIds", strings(binding.sectionIds)); putNullable("ownerUserId", binding.ownerUserId)
            putNullable("accessEpoch", binding.accessEpoch); putNullable("metadataRevision", binding.metadataRevision)
            putNullable("extractorVersion", binding.extractorVersion)
        } }))
    }

    internal fun practice(value: SourcePractice): JsonObject = buildJsonObject {
        put("id", value.id); put("title", value.title); put("sourceRevision", value.sourceRevision); put("origin", value.origin.name)
        put("coverageNote", value.coverageNote); put("excludedQuestionCount", value.excludedQuestionCount)
        put("questions", JsonArray(value.questions.map { q -> buildJsonObject {
            put("id", q.id); put("kind", q.kind.name); put("stem", q.stem); put("options", strings(q.options))
            put("answers", strings(q.answers)); put("analysis", q.analysis); put("sourceQuotes", strings(q.sourceQuotes))
            put("sourceLabel", q.sourceLabel); put("sectionIds", strings(q.sectionIds)); put("score", q.score)
        } }))
    }

    private fun strings(values: Iterable<String>) = JsonArray(values.map(::JsonPrimitive))
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.let { require(it.isString); it.content }
    private fun JsonObject.optionalString(key: String) = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.let { require(it.isString); it.content }
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.optionalLong(key: String) = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.long
    private fun JsonObject.boolean(key: String) = getValue(key).jsonPrimitive.boolean
    private fun JsonObject.array(key: String) = getValue(key).jsonArray
    private fun JsonObject.strings(key: String) = array(key).map { element ->
        element.jsonPrimitive.let { require(it.isString); it.content }
    }
    private fun JsonObject.objectValue(key: String) = getValue(key).jsonObject
    private fun JsonObjectBuilder.putNullable(key: String, value: String?) { put(key, value?.let(::JsonPrimitive) ?: JsonNull) }
    private fun JsonObjectBuilder.putNullable(key: String, value: Long?) { put(key, value?.let(::JsonPrimitive) ?: JsonNull) }
}
