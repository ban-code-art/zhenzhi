package com.example.myapplication.ui.mine

sealed interface MineDestination {
    data object EditProfile : MineDestination
    data object ChangePassword : MineDestination
    data object Scores : MineDestination
    data class ScoreDetail(val testId: Long) : MineDestination
    data object StudyProgress : MineDestination
    data object Favorites : MineDestination
    data object WrongQuestions : MineDestination
    data object Notifications : MineDestination
    data object Settings : MineDestination
}
