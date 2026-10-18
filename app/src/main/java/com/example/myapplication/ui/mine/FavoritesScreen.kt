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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun FavoritesScreen(title: String, source: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val factory = remember(source) { FavoritesViewModelFactory(context.applicationContext, source) }
    val viewModel: FavoritesViewModel = viewModel(key = "favorites_$source", factory = factory)
    val items by viewModel.items.collectAsState()
    var selected by remember { mutableStateOf<FavoriteItem?>(null) }

    SubPageScaffold(title = title, onBack = onBack) {
        if (items.isEmpty()) {
            EmptyHint(if (source == "WRONG") "暂无错题" else "暂无收藏")
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items, key = { it.question.id }) { item ->
                    FavoriteCard(item = item, onClick = { selected = item })
                }
            }
        }
    }

    selected?.let { item ->
        AlertDialog(
            onDismissRequest = { selected = null },
            confirmButton = {
                TextButton(onClick = { selected = null }) { Text("知道了") }
            },
            title = { Text(typeLabel(item.question.type)) },
            text = {
                Column {
                    Text(item.question.stem, color = TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "答案：${formatAnswer(item.question.type, item.question.answer)}",
                        color = TextSecondary
                    )
                    if (item.question.analysis.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text("解析：${item.question.analysis}", color = TextSecondary)
                    }
                    if (!item.question.knowledgePoint.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text("知识点：${item.question.knowledgePoint}", color = TextSecondary)
                    }
                }
            }
        )
    }
}

@Composable
private fun FavoriteCard(item: FavoriteItem, onClick: () -> Unit) {
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
                    text = typeLabel(item.question.type),
                    style = MaterialTheme.typography.bodySmall,
                    color = PrimaryGreen,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = formatDateTime(item.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = item.question.stem,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
        }
    }
}
