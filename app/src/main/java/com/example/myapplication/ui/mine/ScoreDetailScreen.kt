package com.example.myapplication.ui.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ScoreDetailScreen(testId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val factory = remember { ScoresViewModelFactory(context.applicationContext) }
    val viewModel: ScoresViewModel = viewModel(factory = factory)
    val detail by viewModel.detail.collectAsState()

    LaunchedEffect(testId) {
        viewModel.loadDetail(testId)
    }

    SubPageScaffold(title = "成绩详情", onBack = onBack) {
        val current = detail
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = CardWhite),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                text = "得分 ${current.record.totalScore ?: 0} 分",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryGreen
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "时间 ${formatDateTime(current.record.startedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Text(
                                text = "用时 ${formatDuration(current.record.usedSeconds)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                ScoreChip("选择", current.record.choiceScore)
                                ScoreChip("判断", current.record.judgeScore)
                                ScoreChip("填空", current.record.fillScore)
                                ScoreChip("应用", current.record.essayScore)
                            }
                        }
                    }
                }
                items(current.items, key = { it.orderIndex }) { item ->
                    AnswerCard(item)
                }
            }
        }
    }
}

@Composable
private fun AnswerCard(item: ScoreAnswerItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${item.orderIndex}. ${typeLabel(item.type)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Text(
                    text = "${item.score}/${item.maxScore} 分",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (item.score > 0) PrimaryGreen else Red
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = item.stem,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "我的作答：${item.userAnswer?.takeIf { it.isNotBlank() } ?: "未作答"}",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
            Text(
                text = "参考答案：${formatAnswer(item.type, item.correctAnswer)}",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            if (item.analysis.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "解析：${item.analysis}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            if (!item.knowledgePoint.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "知识点：${item.knowledgePoint}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}
