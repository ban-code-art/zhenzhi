package com.example.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.myapplication.data.local.entity.LearningActivityDayEntity
import com.example.myapplication.data.local.entity.LearningActivityEventEntity
import kotlinx.coroutines.flow.Flow

/**
 * 学习活动与学习日汇总（schema v6，契约 §4/§8.1、第四期 §16）。
 *
 * 口径：事件是明细权威，`(user, dedup_key)` 唯一；只有**新插入**的事件才累计学习日，
 * 修分/重复回放等相同 dedupKey 不再增加 `quiz_count`。local_date 按事件发生时设备时区
 * 确定，跨时区不重写历史日期；汇总行 timezone_id 只记最近更新来源。
 */
@Dao
interface LearningActivityDao {
    @Query("SELECT * FROM learning_activity_days WHERE user_id = :userId ORDER BY local_date ASC")
    fun observeDays(userId: Long): Flow<List<LearningActivityDayEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEventIfAbsent(event: LearningActivityEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDayIfAbsent(day: LearningActivityDayEntity): Long

    /**
     * 记录一条学习事件：去重插入，成功插入才把增量写进学习日汇总。
     * 返回 true 表示该事件首次被消费（调用方据此只打卡一次）。
     */
    @Transaction
    suspend fun recordEvent(event: LearningActivityEventEntity): Boolean {
        if (insertEventIfAbsent(event) == -1L) return false
        // 汇总行 INSERT OR IGNORE 后增量 UPDATE：并发/重放下不覆盖已有累计值。
        insertDayIfAbsent(
            LearningActivityDayEntity(
                userId = event.userId,
                localDate = event.localDate,
                timezoneId = event.timezoneId,
                readingMs = 0,
                quizCount = 0,
                updatedAt = event.createdAt,
            ),
        )
        if (event.readingDeltaMs != 0L) {
            addReadingDelta(event.userId, event.localDate, event.timezoneId, event.readingDeltaMs, event.createdAt)
        }
        if (event.quizDelta != 0) {
            addQuizDelta(event.userId, event.localDate, event.timezoneId, event.quizDelta, event.createdAt)
        }
        return true
    }

    @Query("SELECT * FROM learning_activity_events WHERE user_id = :userId AND dedup_key = :dedupKey LIMIT 1")
    suspend fun findEventByDedup(userId: Long, dedupKey: String): LearningActivityEventEntity?

    @Query(
        "SELECT * FROM learning_activity_events WHERE user_id = :userId AND local_date = :localDate " +
            "ORDER BY created_at ASC, id ASC",
    )
    suspend fun listEventsOnDate(userId: Long, localDate: String): List<LearningActivityEventEntity>

    @Query("SELECT * FROM learning_activity_days WHERE user_id = :userId AND local_date = :localDate LIMIT 1")
    suspend fun findDay(userId: Long, localDate: String): LearningActivityDayEntity?

    @Query(
        "SELECT * FROM learning_activity_days WHERE user_id = :userId AND local_date BETWEEN :fromDate AND :toDate " +
            "ORDER BY local_date ASC",
    )
    suspend fun listDays(userId: Long, fromDate: String, toDate: String): List<LearningActivityDayEntity>

    @Query(
        "UPDATE learning_activity_days SET reading_ms = reading_ms + :deltaMs, timezone_id = :timezoneId, " +
            "updated_at = :now WHERE user_id = :userId AND local_date = :localDate",
    )
    suspend fun addReadingDelta(
        userId: Long,
        localDate: String,
        timezoneId: String,
        deltaMs: Long,
        now: Long,
    ): Int

    @Query(
        "UPDATE learning_activity_days SET quiz_count = quiz_count + :delta, timezone_id = :timezoneId, " +
            "updated_at = :now WHERE user_id = :userId AND local_date = :localDate",
    )
    suspend fun addQuizDelta(
        userId: Long,
        localDate: String,
        timezoneId: String,
        delta: Int,
        now: Long,
    ): Int
}
