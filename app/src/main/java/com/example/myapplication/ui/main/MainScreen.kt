package com.example.myapplication.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.myapplication.ui.components.AppBottomBar
import com.example.myapplication.ui.components.BottomNavItem
import com.example.myapplication.ui.knowledge.KnowledgeScreen
import com.example.myapplication.ui.mine.ChangePasswordScreen
import com.example.myapplication.ui.mine.EditProfileScreen
import com.example.myapplication.ui.mine.FavoritesScreen
import com.example.myapplication.ui.mine.MineDestination
import com.example.myapplication.ui.mine.MineScreen
import com.example.myapplication.ui.mine.NotificationsScreen
import com.example.myapplication.ui.mine.ScoreDetailScreen
import com.example.myapplication.ui.mine.ScoreListScreen
import com.example.myapplication.ui.mine.SettingsScreen
import com.example.myapplication.ui.mine.StudyProgressScreen
import com.example.myapplication.ui.records.RecordsScreen
import com.example.myapplication.ui.test.TestScreen

private enum class MainTab(val label: String, val icon: ImageVector) {
    KNOWLEDGE("知识学习", Icons.Filled.List),
    TEST("随机测试", Icons.Filled.PlayArrow),
    RECORDS("记录题目", Icons.Filled.Star),
    MINE("我的", Icons.Filled.Person)
}

@Composable
fun MainScreen(onLogout: () -> Unit) {
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val mineStack = remember { mutableStateListOf<MineDestination>() }
    val navItems = remember {
        MainTab.entries.map { BottomNavItem(it.label, it.icon) }
    }

    BackHandler(enabled = mineStack.isNotEmpty()) {
        mineStack.removeLastOrNull()
    }

    val current = mineStack.lastOrNull()
    if (current != null) {
        MineSubScreen(
            destination = current,
            onBack = { mineStack.removeLastOrNull() },
            onNavigate = { mineStack.add(it) },
            onOpenScoreDetail = { id -> mineStack.add(MineDestination.ScoreDetail(id)) },
            onLogout = onLogout
        )
        return
    }

    Scaffold(
        bottomBar = {
            AppBottomBar(
                items = navItems,
                selectedIndex = selectedIndex,
                onSelect = { selectedIndex = it }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (MainTab.entries[selectedIndex]) {
                MainTab.KNOWLEDGE -> KnowledgeScreen()
                MainTab.TEST -> TestScreen()
                MainTab.RECORDS -> RecordsScreen()
                MainTab.MINE -> MineScreen(
                    onNavigate = { mineStack.add(it) }
                )
            }
        }
    }
}

@Composable
private fun MineSubScreen(
    destination: MineDestination,
    onBack: () -> Unit,
    onNavigate: (MineDestination) -> Unit,
    onOpenScoreDetail: (Long) -> Unit,
    onLogout: () -> Unit
) {
    when (destination) {
        MineDestination.EditProfile -> EditProfileScreen(onBack = onBack)
        MineDestination.ChangePassword -> ChangePasswordScreen(onBack = onBack)
        MineDestination.Scores -> ScoreListScreen(onBack = onBack, onOpen = onOpenScoreDetail)
        is MineDestination.ScoreDetail -> ScoreDetailScreen(
            testId = destination.testId,
            onBack = onBack
        )
        MineDestination.StudyProgress -> StudyProgressScreen(onBack = onBack)
        MineDestination.Favorites -> FavoritesScreen(
            title = "收藏题目",
            source = "MANUAL",
            onBack = onBack
        )
        MineDestination.WrongQuestions -> FavoritesScreen(
            title = "错题本",
            source = "WRONG",
            onBack = onBack
        )
        MineDestination.Notifications -> NotificationsScreen(onBack = onBack)
        MineDestination.Settings -> SettingsScreen(
            onBack = onBack,
            onNavigate = onNavigate,
            onLogout = onLogout
        )
    }
}
