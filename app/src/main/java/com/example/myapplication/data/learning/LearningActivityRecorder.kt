package com.example.myapplication.data.learning

import com.example.myapplication.data.local.dao.LearningActivityDao
import com.example.myapplication.data.local.entity.LearningActivityEventEntity
import com.example.myapplication.data.session.SessionIdentity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Real foreground reading and local practice use the existing idempotent activity ledger. */
class LearningActivityRecorder(
    private val dao: LearningActivityDao,
    private val identityNow: suspend () -> SessionIdentity,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val timeZone: () -> TimeZone = TimeZone::getDefault,
) {
    suspend fun recordReading(identity: SessionIdentity, key: String, startWallMs: Long, durationMs: Long) {
        val user = identity.userId ?: return
        if (durationMs <= 0 || key.isBlank() || identityNow() != identity) return
        val zone = timeZone()
        readingDaySlices(startWallMs, durationMs, zone).forEachIndexed { index, slice ->
            if (identityNow() != identity) return
            dao.recordEvent(LearningActivityEventEntity(
                id = UUID.randomUUID().toString(), userId = user, dedupKey = "reading:$key:$index",
                kind = "READING", localDate = slice.localDate, timezoneId = zone.id,
                readingDeltaMs = slice.durationMs, createdAt = slice.startedAt,
            ))
        }
    }

    suspend fun recordQuiz(identity: SessionIdentity, recordId: String) {
        val user = identity.userId ?: return
        if (recordId.isBlank() || identityNow() != identity) return
        val now = wallClock()
        val zone = timeZone()
        val date = localLearningDate(now, zone)
        dao.recordEvent(LearningActivityEventEntity(
            id = UUID.randomUUID().toString(), userId = user, dedupKey = "source-practice:$recordId:$date",
            kind = "QUIZ", localDate = date, timezoneId = zone.id,
            quizDelta = 1, createdAt = now,
        ))
    }
}

data class ReadingDaySlice(val localDate: String, val startedAt: Long, val durationMs: Long)

fun localLearningDate(wallMs: Long, zone: TimeZone = TimeZone.getDefault()): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }.format(java.util.Date(wallMs))

/** Split an actual reading interval at local midnight without rewriting past dates. */
fun readingDaySlices(startWallMs: Long, durationMs: Long, zone: TimeZone): List<ReadingDaySlice> {
    require(durationMs >= 0 && durationMs <= 24 * 60 * 60 * 1000L)
    val slices = mutableListOf<ReadingDaySlice>()
    var cursor = startWallMs
    var remaining = durationMs
    while (remaining > 0) {
        val midnight = Calendar.getInstance(zone, Locale.US).apply {
            timeInMillis = cursor
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val part = minOf(remaining, (midnight - cursor).coerceAtLeast(1L))
        slices += ReadingDaySlice(localLearningDate(cursor, zone), cursor, part)
        remaining -= part
        cursor += part
    }
    return slices
}

data class LearningDayCounts(val learnedDays: Int, val streakDays: Int, val readingDays: Int, val quizDays: Int)

fun learningDayCounts(
    legacyDays: Collection<String>, readingDays: Collection<String>, quizDays: Collection<String>,
    todayWallMs: Long, zone: TimeZone = TimeZone.getDefault(),
    reviewDays: Collection<String> = emptyList(),
): LearningDayCounts {
    val reading = readingDays.toSet()
    val quizzes = quizDays.toSet()
    val all = (legacyDays + reading + quizzes + reviewDays).toSet()
    val cursor = Calendar.getInstance(zone, Locale.US).apply { timeInMillis = todayWallMs }
    if (localLearningDate(cursor.timeInMillis, zone) !in all) cursor.add(Calendar.DAY_OF_MONTH, -1)
    var streak = 0
    while (localLearningDate(cursor.timeInMillis, zone) in all) {
        streak++
        cursor.add(Calendar.DAY_OF_MONTH, -1)
    }
    return LearningDayCounts(all.size, streak, reading.size, quizzes.size)
}
