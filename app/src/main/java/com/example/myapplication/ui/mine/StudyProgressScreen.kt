package com.example.myapplication.ui.mine

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

@Composable
fun StudyProgressScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val factory = remember { StudyProgressViewModelFactory(context.applicationContext) }
    val viewModel: StudyProgressViewModel = viewModel(factory = factory)
    val items by viewModel.items.collectAsState()

    SubPageScaffold(title = "我的课程", onBack = onBack) {
        if (items.isEmpty()) {
            EmptyHint("暂无章节内容")
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items, key = { it.chapter.id }) { item ->
                    ChapterCard(item)
                }
            }
        }
    }
}

@Composable
private fun ChapterCard(item: StudyItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                    text = "第 ${item.chapter.orderIndex} 章  ${item.chapter.title}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (item.completed) "已完成" else "未完成",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.completed) PrimaryGreen else TextSecondary
                )
            }
            if (!item.chapter.summary.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = item.chapter.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            if (item.quizScore != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "随堂测：${item.quizScore}/${item.quizTotal ?: 0} 分",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}
