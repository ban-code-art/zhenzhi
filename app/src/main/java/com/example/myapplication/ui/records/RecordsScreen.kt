package com.example.myapplication.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.components.LocalMainDockBottomInset
import com.example.myapplication.ui.components.SettingItem
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

@Composable
fun RecordsScreen(
    onOpenQuizHistory: () -> Unit,
    onOpenScores: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenWrongQuestions: () -> Unit,
) {
    val dockBottom = LocalMainDockBottomInset.current
    Surface(
        modifier = Modifier.fillMaxSize().testTag("records-screen"),
        color = UiRoles.surface(UiSurfaceRole.Page),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp + dockBottom),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    "记录题目",
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().semantics { heading() },
                    style = UiRoles.text(UiRoles.pageTitle),
                    color = UiRoles.foreground,
                )
            }
            item {
                Surface(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = UiRoles.surface(UiSurfaceRole.Card),
                ) {
                    Column {
                        SettingItem("随堂测记录", onOpenQuizHistory, Modifier.testTag("records-quiz-history"),
                            icon = UiIcons.Records)
                        HorizontalDivider()
                        SettingItem("学习记录", onOpenScores, Modifier.testTag("records-scores"),
                            icon = UiIcons.Records)
                        HorizontalDivider()
                        SettingItem("收藏题目", onOpenFavorites, Modifier.testTag("records-favorites"),
                            icon = UiIcons.Bookmark)
                        HorizontalDivider()
                        SettingItem("错题本", onOpenWrongQuestions, Modifier.testTag("records-wrong-questions"),
                            icon = UiIcons.WrongQuestions)
                    }
                }
            }
        }
    }
}
