package com.example.myapplication.ui.mine

sealed interface MineDestination {
    data object EditProfile : MineDestination
    data object ChangePassword : MineDestination
    data object Scores : MineDestination
    data class ScoreDetail(val testId: Long) : MineDestination
    data object StudyProgress : MineDestination
    data object QuizHistory : MineDestination
    data object Favorites : MineDestination
    data object WrongQuestions : MineDestination
    data object Notifications : MineDestination
    data object Settings : MineDestination
    data object Appearance : MineDestination
    data object About : MineDestination
    data object KnowledgeSettings : MineDestination
    data object AiConfig : MineDestination
    data object KnowledgeStats : MineDestination
}

/**
 * 该目的地是否由 NavHost 全屏路由承载，而不是 Mine 内部栈。
 *
 * 随堂测记录走 `Routes.QUIZ_HISTORY` 全屏路由（与阅读→生成→作答的返回栈一致），
 * 因此 MainScreen 遇到它时应转交 `onOpenQuizHistory` 去 navigate，而不是压内部栈；
 * 其它 Mine 入口仍由内部栈渲染，行为不变。
 */
fun MineDestination.isHostedByNavHost(): Boolean = this == MineDestination.QuizHistory
