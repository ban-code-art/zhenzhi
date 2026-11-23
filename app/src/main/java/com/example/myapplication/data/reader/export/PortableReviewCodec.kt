package com.example.myapplication.data.reader.export

import com.example.myapplication.domain.review.ReviewRating
import com.example.myapplication.domain.review.ReviewSchedule
import kotlinx.serialization.json.*

internal data class PortableReviewSource(val kind: String, val id: String? = null, val question: String? = null)

internal data class PortableReviewItem(
    val portableItemId: String,
    val source: PortableReviewSource,
    val bookKey: String?,
    val tags: List<String>,
    val sourceTitle: String,
    val sourceRevision: String,
    val prompt: String,
    val answer: String,
    val priority: Int,
    val paused: Boolean,
    val stage: Int,
    val dueAtUtc: Long,
    val lastReviewedAtUtc: Long?,
    val itemRevision: Long,
    val createdAtUtc: Long,
    val updatedAtUtc: Long,
)

internal data class PortableReviewSession(
    val portableSessionId: String,
    val bookKey: String?,
    val startedAtUtc: Long,
    val finishedAtUtc: Long?,
    val completedCount: Int,
    val skippedCount: Int,
    val unavailableCount: Int,
)

internal data class PortableReviewEvent(
    val portableEventId: String,
    val portableSessionId: String,
    val cardIdentity: String,
    val ordinal: Int?,
    val rating: String,
    val beforeStage: Int,
    val afterStage: Int,
    val beforeDueAtUtc: Long,
    val afterDueAtUtc: Long,
    val snapshotRevision: Long,
    val occurredAtUtc: Long,
    val localDate: String,
    val timezoneId: String,
    val activeMs: Long,
)

internal data class PortableReviewState(
    val items: List<PortableReviewItem> = emptyList(),
    val sessions: List<PortableReviewSession> = emptyList(),
    val events: List<PortableReviewEvent> = emptyList(),
)

/** Only portable fields are represented here. Runtime tickets, queue, draft, account and local row IDs have no slots. */
internal object PortableReviewCodec {
    const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_ITEMS = 8_000
    private const val MAX_SESSIONS = 16_000
    private const val MAX_EVENTS = 24_000
    private const val MAX_TEXT = 8_000
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val referenceId = Regex("[a-zA-Z0-9:._-]{1,256}")
    private val meridianPointCode = Regex("[A-Z]{2,4}[0-9]{1,2}")
    private val localDate = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    private val timeZone = Regex("[a-zA-Z0-9+_:./-]{1,128}")
    private val rating = ReviewRating.entries.map { it.name }.toSet()

    fun write(value: PortableReviewState): ByteArray {
        checkLimits(value)
        val payload = buildJsonObject {
            put("schemaVersion", 1)
            put("schedulePolicy", ReviewSchedule.FIXED_INTERVAL_V1)
            put("items", JsonArray(value.items.map(::itemJson)))
            put("sessions", JsonArray(value.sessions.map(::sessionJson)))
            put("events", JsonArray(value.events.map(::eventJson)))
        }.toString().toByteArray(Charsets.UTF_8)
        read(payload)
        return payload
    }

    fun read(bytes: ByteArray): PortableReviewState {
        val root = BackupV2Json.obj(BackupV2Json.parse(bytes, MAX_BYTES), setOf("schemaVersion", "schedulePolicy", "items", "sessions", "events"))
        v2require(root.number("schemaVersion") == 1L && root.string("schedulePolicy") == ReviewSchedule.FIXED_INTERVAL_V1,
            "UNSUPPORTED_VERSION", "review state schema or schedule policy")
        val parsed = PortableReviewState(
            v2List(root.rows("items", MAX_ITEMS).map(::readItem)),
            v2List(root.rows("sessions", MAX_SESSIONS).map(::readSession)),
            v2List(root.rows("events", MAX_EVENTS).map(::readEvent)),
        )
        checkLimits(parsed)
        return parsed
    }

    private fun checkLimits(value: PortableReviewState) {
        v2require(value.items.size <= MAX_ITEMS && value.sessions.size <= MAX_SESSIONS && value.events.size <= MAX_EVENTS,
            "LIMIT_EXCEEDED", "review collection count")
        v2require(value.items.map { it.portableItemId }.toSet().size == value.items.size &&
            value.sessions.map { it.portableSessionId }.toSet().size == value.sessions.size &&
            value.events.map { it.portableEventId }.toSet().size == value.events.size,
            "DUPLICATE_ID", "portable review identity")
    }

    private fun readItem(element: JsonElement): PortableReviewItem {
        val row = BackupV2Json.obj(element, setOf(
            "portableItemId", "source", "bookKey", "tags", "sourceTitle", "sourceRevision", "prompt", "answer",
            "priority", "paused", "stage", "dueAtUtc", "lastReviewedAtUtc", "itemRevision", "createdAtUtc", "updatedAtUtc",
        ))
        val source = BackupV2Json.obj(row.getValue("source"), setOf("kind", "id", "question"))
        val reference = PortableReviewSource(source.string("kind"), source.maybeString("id"), source.maybeString("question"))
        val matches = when (reference.kind) {
            "annotation", "notebook" -> reference.id?.matches(referenceId) == true && reference.question == null
            "question" -> reference.id?.matches(Regex("[1-9][0-9]{0,18}")) == true &&
                reference.id.toLongOrNull() != null && reference.question == null
            "practice" -> reference.id?.matches(BackupV3Archive.practiceId) == true && reference.question?.matches(referenceId) == true
            "meridian_acupoint" -> reference.id?.matches(meridianPointCode) == true && reference.question == null
            "standalone", "missing" -> reference.id == null && reference.question == null
            else -> false
        }
        v2require(matches, "INVALID_REVIEW_SOURCE", "invalid review reference")
        val tags = BackupV2Json.strings(row["tags"])
        v2require(tags.size <= 16 && tags.toSet().size == tags.size && tags.all { it.isNotBlank() && it.length <= 64 },
            "INVALID_REVIEW_STATE", "review tags")
        val bookKey = row.bookKey()
        v2require(reference.kind != "standalone" || bookKey == null, "INVALID_REVIEW_SOURCE", "standalone card scope")
        val created = row.bounded("createdAtUtc")
        val updated = row.bounded("updatedAtUtc")
        v2require(updated >= created, "INVALID_REVIEW_STATE", "review update time")
        return PortableReviewItem(row.portable("portableItemId"), reference, bookKey, v2List(tags),
            row.text("sourceTitle", 500, allowBlank = true), row.text("sourceRevision", 1024), row.text("prompt", 500),
            row.text("answer", MAX_TEXT), row.bounded("priority", 0L..2L).toInt(), BackupV2Json.bool(row["paused"]),
            row.stage("stage"), row.bounded("dueAtUtc"), row.maybeNumber("lastReviewedAtUtc"),
            row.bounded("itemRevision", 1L..BackupV2Limits.SAFE_INTEGER), created, updated)
    }

    private fun readSession(element: JsonElement): PortableReviewSession {
        val row = BackupV2Json.obj(element, setOf(
            "portableSessionId", "bookKey", "startedAtUtc", "finishedAtUtc", "completedCount", "skippedCount", "unavailableCount",
        ))
        val started = row.bounded("startedAtUtc")
        val finished = row.maybeNumber("finishedAtUtc")
        return PortableReviewSession(row.portable("portableSessionId"), row.bookKey(), started, finished,
            row.count("completedCount"), row.count("skippedCount"), row.count("unavailableCount"))
    }

    private fun readEvent(element: JsonElement): PortableReviewEvent {
        val row = BackupV2Json.obj(element, setOf(
            "portableEventId", "portableSessionId", "cardIdentity", "ordinal", "rating", "beforeStage", "afterStage",
            "beforeDueAtUtc", "afterDueAtUtc", "snapshotRevision", "occurredAtUtc", "localDate", "timezoneId", "activeMs",
        ))
        val score = row.string("rating")
        val date = row.string("localDate")
        val zone = row.string("timezoneId")
        v2require(score in rating && date.matches(localDate) && zone.matches(timeZone), "INVALID_REVIEW_EVENT", "review event fields")
        val ordinal = row.maybeNumber("ordinal")?.also {
            v2require(it in 0L..Int.MAX_VALUE.toLong(), "INVALID_REVIEW_EVENT", "ordinal")
        }?.toInt()
        return PortableReviewEvent(row.portable("portableEventId"), row.portable("portableSessionId"), row.portable("cardIdentity"),
            ordinal, score, row.stage("beforeStage"), row.stage("afterStage"), row.bounded("beforeDueAtUtc"),
            row.bounded("afterDueAtUtc"), row.bounded("snapshotRevision", 1L..BackupV2Limits.SAFE_INTEGER),
            row.bounded("occurredAtUtc"), date, zone, row.bounded("activeMs"))
    }

    private fun itemJson(item: PortableReviewItem) = buildJsonObject {
        put("portableItemId", item.portableItemId)
        putJsonObject("source") {
            put("kind", item.source.kind)
            put("id", item.source.id)
            put("question", item.source.question)
        }
        put("bookKey", item.bookKey)
        put("tags", JsonArray(item.tags.map(::JsonPrimitive)))
        put("sourceTitle", item.sourceTitle)
        put("sourceRevision", item.sourceRevision)
        put("prompt", item.prompt)
        put("answer", item.answer)
        put("priority", item.priority)
        put("paused", item.paused)
        put("stage", item.stage)
        put("dueAtUtc", item.dueAtUtc)
        put("lastReviewedAtUtc", item.lastReviewedAtUtc)
        put("itemRevision", item.itemRevision)
        put("createdAtUtc", item.createdAtUtc)
        put("updatedAtUtc", item.updatedAtUtc)
    }

    private fun sessionJson(session: PortableReviewSession) = buildJsonObject {
        put("portableSessionId", session.portableSessionId)
        put("bookKey", session.bookKey)
        put("startedAtUtc", session.startedAtUtc)
        put("finishedAtUtc", session.finishedAtUtc)
        put("completedCount", session.completedCount)
        put("skippedCount", session.skippedCount)
        put("unavailableCount", session.unavailableCount)
    }

    private fun eventJson(event: PortableReviewEvent) = buildJsonObject {
        put("portableEventId", event.portableEventId)
        put("portableSessionId", event.portableSessionId)
        put("cardIdentity", event.cardIdentity)
        put("ordinal", event.ordinal)
        put("rating", event.rating)
        put("beforeStage", event.beforeStage)
        put("afterStage", event.afterStage)
        put("beforeDueAtUtc", event.beforeDueAtUtc)
        put("afterDueAtUtc", event.afterDueAtUtc)
        put("snapshotRevision", event.snapshotRevision)
        put("occurredAtUtc", event.occurredAtUtc)
        put("localDate", event.localDate)
        put("timezoneId", event.timezoneId)
        put("activeMs", event.activeMs)
    }

    private fun JsonObject.rows(key: String, limit: Int) = BackupV2Json.array(this[key]).also {
        v2require(it.size <= limit, "LIMIT_EXCEEDED", key)
    }
    private fun JsonObject.string(key: String) = BackupV2Json.string(this[key])
    private fun JsonObject.maybeString(key: String) = if (this[key] == JsonNull) null else string(key)
    private fun JsonObject.number(key: String) = BackupV2Json.integer(this[key])
    private fun JsonObject.maybeNumber(key: String) = if (this[key] == JsonNull) null else bounded(key)
    private fun JsonObject.bounded(key: String, range: LongRange = 0L..BackupV2Limits.SAFE_INTEGER) = number(key).also {
        v2require(it in range, "INVALID_REVIEW_STATE", key)
    }
    private fun JsonObject.stage(key: String) = bounded(key, -1L..4L).toInt()
    private fun JsonObject.count(key: String) = bounded(key, 0L..Int.MAX_VALUE.toLong()).toInt()
    private fun JsonObject.portable(key: String) = string(key).also {
        v2require(it.matches(uuid), "INVALID_REVIEW_ID", key)
    }
    private fun JsonObject.bookKey() = maybeString("bookKey").also {
        v2require(it == null || it.matches(referenceId) && (it.startsWith("import:") || it.startsWith("course:")),
            "INVALID_REVIEW_SOURCE", "book scope")
    }
    private fun JsonObject.text(key: String, max: Int, allowBlank: Boolean = false) = string(key).also {
        v2require(it.length <= max && (allowBlank || it.isNotBlank()), "INVALID_REVIEW_STATE", key)
    }
}
