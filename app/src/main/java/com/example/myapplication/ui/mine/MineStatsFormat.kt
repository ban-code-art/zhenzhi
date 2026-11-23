package com.example.myapplication.ui.mine

/**
 * “我的”页学习日展示项（纯展示模型，不做统计推断）。
 *
 * 只把 [MineUiState] 里已经算好的数字排成四张卡；任何维度没有数据就如实显示 0，
 * 不在 UI 侧补数、不去重、也不与旧随机统计混算（合并去重在 MineViewModel 里做）。
 */
internal enum class MineStatKind {
    /** 学习天数：随机测试、阅读、随堂测和成功自评的活跃日期并集。 */
    LEARNED_DAYS,
    /** 连续打卡天数：按上述合并结果从今天（或昨天）往前数的连续天数。 */
    STREAK_DAYS,
    /** 阅读天数：learning_activity_days 中 reading_ms > 0 的天数。 */
    READING_DAYS,
    /** 随堂测天数：learning_activity_days 中 quiz_count > 0 的天数。 */
    QUIZ_DAYS,
}

internal data class MineStatUi(
    val kind: MineStatKind,
    val label: String,
    val value: String,
)

/**
 * 学习日四宫格：顺序固定为 学习天数 / 连续打卡天数 / 阅读天数 / 随堂测天数。
 *
 * 数值由 MineViewModel 持续观察阅读、随堂作答、随机测试和成功的知识回顾自评。
 */
internal fun mineStatsOf(state: MineUiState): List<MineStatUi> = listOf(
    MineStatUi(MineStatKind.LEARNED_DAYS, "学习天数", state.learnedDays.toString()),
    MineStatUi(MineStatKind.STREAK_DAYS, "连续打卡天数", state.streakDays.toString()),
    MineStatUi(MineStatKind.READING_DAYS, "阅读天数", state.readingDays.toString()),
    MineStatUi(MineStatKind.QUIZ_DAYS, "随堂测天数", state.quizDays.toString()),
)
