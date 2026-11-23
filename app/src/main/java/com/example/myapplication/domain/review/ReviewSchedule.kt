package com.example.myapplication.domain.review

enum class ReviewRating { FORGOT, VAGUE, REMEMBERED }

data class ReviewScheduleResult(val stage: Int, val dueAtUtc: Long, val reviewedAtUtc: Long)

object ReviewSchedule {
    const val FIXED_INTERVAL_V1 = "FIXED_INTERVAL_V1"
    const val INITIAL_STAGE = -1

    private const val DAY_MS = 86_400_000L
    private const val TEN_MINUTES_MS = 600_000L
    private val rememberedDays = longArrayOf(1, 3, 7, 14, 30)

    fun next(stage: Int, lastReviewedAtUtc: Long?, now: Long, rating: ReviewRating): ReviewScheduleResult {
        require(stage in INITIAL_STAGE..rememberedDays.lastIndex)
        val reviewedAt = maxOf(now, lastReviewedAtUtc ?: now)
        val nextStage = when (rating) {
            ReviewRating.FORGOT -> INITIAL_STAGE
            ReviewRating.VAGUE -> stage
            ReviewRating.REMEMBERED -> (stage + 1).coerceAtMost(rememberedDays.lastIndex)
        }
        val interval = when (rating) {
            ReviewRating.FORGOT -> TEN_MINUTES_MS
            ReviewRating.VAGUE -> DAY_MS
            ReviewRating.REMEMBERED -> rememberedDays[nextStage] * DAY_MS
        }
        return ReviewScheduleResult(nextStage, Math.addExact(reviewedAt, interval), reviewedAt)
    }
}
