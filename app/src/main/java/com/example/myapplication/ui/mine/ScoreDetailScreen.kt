package com.example.myapplication.ui.mine

import androidx.compose.runtime.Composable
import com.example.myapplication.ui.test.TestScreen
import com.example.myapplication.ui.ai.AgentSourceUi

/** 普通成绩详情复用同一试卷快照、解析与收藏入口，成绩仍来自test_answers。 */
@Composable
fun ScoreDetailScreen(
    testId: Long,
    onBack: () -> Unit,
    onOpenSource: (AgentSourceUi) -> Unit = {},
    initialReturnPosition: String = "{}",
    onSourceCheckpoint: (String) -> Unit = {},
) {
    TestScreen(testId = testId, onBack = onBack, onOpenSource = onOpenSource,
        initialReturnPosition = initialReturnPosition, onSourceCheckpoint = onSourceCheckpoint)
}
