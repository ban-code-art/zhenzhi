package com.example.myapplication.ui.mine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.data.local.entity.TestRecordEntity

@Composable
fun ScoreListScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val factory = remember { ScoresViewModelFactory(context.applicationContext) }
    val viewModel: ScoresViewModel = viewModel(factory = factory)
    val records by viewModel.records.collectAsState()

    SubPageScaffold(title = "我的成绩", onBack = onBack) {
        if (records.isEmpty()) {
            EmptyHint("暂无测试成绩")
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(records, key = { it.id }) { record ->
                    ScoreCard(record = record, onClick = { onOpen(record.id) })
                }
            }
        }
    }
}

@Composable
private fun ScoreCard(record: TestRecordEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDateTime(record.startedAt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Text(
                    text = "${record.totalScore ?: 0} 分",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryGreen
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "用时 ${formatDuration(record.usedSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ScoreChip("选择", record.choiceScore)
                ScoreChip("判断", record.judgeScore)
                ScoreChip("填空", record.fillScore)
                ScoreChip("应用", record.essayScore)
            }
        }
    }
}

@Composable
internal fun ScoreChip(label: String, score: Int?) {
    Text(
        text = "$label ${score ?: 0}",
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary
    )
}
