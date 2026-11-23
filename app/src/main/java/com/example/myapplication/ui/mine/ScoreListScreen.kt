package com.example.myapplication.ui.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.local.entity.TestRecordEntity
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.ui.navigation.BackDecision
import com.example.myapplication.ui.navigation.BackPage
import com.example.myapplication.ui.navigation.BackState
import com.example.myapplication.ui.navigation.unifiedBackRequest
import com.example.myapplication.ui.theme.UiColorRole
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

@Composable
fun ScoreListScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val context = LocalContext.current.applicationContext
    val container = (context as LearningApplication).container
    val identity by container.sessionManager.identity.collectAsState(SessionIdentity(null, 0))
    val requestBack = unifiedBackRequest(BackState(page = BackPage.CHILD)) { decision ->
        if (decision == BackDecision.PopPage) onBack()
    }
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        SubPageScaffold(title = "我的成绩", onBack = requestBack, glassBack = true, actionId = "ordinary.scores.back") {
            key(identity) {
                if (identity.userId == null) {
                    ScoreListEmpty("请先登录")
                } else {
                    val factory = remember(context) { ScoresViewModelFactory(context) }
                    val model: ScoresViewModel = viewModel(
                        key = "ordinary-scores:${identity.userId}:${identity.epoch}",
                        factory = factory,
                    )
                    val records by model.records.collectAsState()
                    if (records.isEmpty()) {
                        ScoreListEmpty("暂无测试成绩")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().testTag("ordinary-score-list"),
                            contentPadding = PaddingValues(
                                start = 16.dp, top = GlassPageBarHeight + 12.dp, end = 16.dp, bottom = 16.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(records, key = { it.id }) { record ->
                                ScoreCard(record = record, onClick = { onOpen(record.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreListEmpty(label: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(top = GlassPageBarHeight),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = UiRoles.color(UiColorRole.OnSurfaceVariant))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScoreCard(record: TestRecordEntity, onClick: () -> Unit) {
    val secondary = UiRoles.color(UiColorRole.OnSurfaceVariant)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("ordinary-score-${record.id}"),
        shape = MaterialTheme.shapes.large,
        color = UiRoles.surface(UiSurfaceRole.Card),
        contentColor = UiRoles.foreground,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(formatDateTime(record.startedAt), style = UiRoles.text(MaterialTheme.typography.bodyMedium), color = secondary)
            Text(
                record.totalScore?.let { "$it 分" } ?: "成绩未记录",
                style = UiRoles.text(MaterialTheme.typography.titleLarge),
                fontWeight = FontWeight.Bold,
                color = UiRoles.accent,
            )
            Text("用时 ${formatDuration(record.usedSeconds)}",
                style = UiRoles.text(MaterialTheme.typography.bodySmall), color = secondary)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ScoreChip("选择", record.choiceScore)
                ScoreChip("判断", record.judgeScore)
                ScoreChip("填空", record.fillScore)
                ScoreChip("应用", record.essayScore)
            }
        }
    }
}

@Composable
private fun ScoreChip(label: String, score: Int?) {
    Text(
        text = "$label ${score ?: "—"}",
        style = UiRoles.text(MaterialTheme.typography.bodySmall),
        color = UiRoles.color(UiColorRole.OnSurfaceVariant),
    )
}
