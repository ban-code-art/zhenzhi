package com.example.myapplication.ui.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

@Composable
fun StudyProgressScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val factory = remember(context) { StudyProgressViewModelFactory(context) }
    val viewModel: StudyProgressViewModel = viewModel(factory = factory)
    val items by viewModel.items.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val mineFactory = remember(context) { MineViewModelFactory(context) }
    val mineViewModel: MineViewModel = viewModel(factory = mineFactory)
    val mineState by mineViewModel.state.collectAsState()

    Box(Modifier.fillMaxSize().background(UiRoles.surface(UiSurfaceRole.Page)).systemBarsPadding()) {
        SubPageScaffold(
            title = "我的课程", onBack = onBack, glassBack = true, actionId = "mine.progress.back",
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("study-progress-list"),
                contentPadding = PaddingValues(
                    start = 16.dp, top = GlassPageBarHeight + 8.dp, end = 16.dp, bottom = 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "learning-days") { MineLearningDaysCard(mineState) }
                item(key = "chapters-heading") {
                    Text(
                        "学习进度", style = UiRoles.text(MaterialTheme.typography.titleMedium),
                        color = UiRoles.foreground, modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                if (loading || error != null || items.isEmpty()) {
                    item(key = "chapter-status") {
                        Surface(
                            shape = RoundedCornerShape(24.dp), color = UiRoles.surface(UiSurfaceRole.Card),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = when {
                                        loading -> "正在读取学习进度…"
                                        error != null -> error!!
                                        else -> "暂无章节内容"
                                    },
                                    style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                                    color = if (error != null) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("study-progress-status"),
                                )
                                if (error != null) {
                                    UiAction("重试", onClick = viewModel::retry, actionId = "mine.progress.retry")
                                }
                            }
                        }
                    }
                } else {
                    items(items, key = { it.chapter.id }) { item -> ChapterCard(item) }
                }
            }
        }
    }
}

@Composable
private fun ChapterCard(item: StudyItem) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("study-chapter-${item.chapter.id}"),
        shape = RoundedCornerShape(24.dp), color = UiRoles.surface(UiSurfaceRole.Card),
    ) {
        Column(Modifier.padding(20.dp)) {
            Surface(
                color = if (item.completed) UiRoles.iconContainer else UiRoles.surface(UiSurfaceRole.Block),
                contentColor = if (item.completed) UiRoles.iconTint else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    if (item.completed) "已完成" else "未完成",
                    style = UiRoles.text(MaterialTheme.typography.labelMedium),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "第 ${item.chapter.orderIndex} 章  ${item.chapter.title}",
                style = UiRoles.text(MaterialTheme.typography.titleMedium),
                fontWeight = FontWeight.SemiBold, color = UiRoles.foreground,
            )
            if (!item.chapter.summary.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    item.chapter.summary, style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.quizScore != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "随堂测：${item.quizScore}/${item.quizTotal ?: 0} 分",
                    style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
