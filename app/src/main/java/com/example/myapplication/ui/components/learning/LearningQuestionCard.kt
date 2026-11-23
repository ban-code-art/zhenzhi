package com.example.myapplication.ui.components.learning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

data class LearningOption(val key: String, val text: String)

enum class LearningInputKind { CHOICE, TEXT }

data class LearningQuestion(
    val identity: String,
    val ordinal: Int,
    val stem: String,
    val options: List<LearningOption> = emptyList(),
    val inputKind: LearningInputKind = LearningInputKind.TEXT,
    val maxScore: Int = 0,
)

@Composable
fun LearningQuestionCard(
    question: LearningQuestion,
    answer: String,
    onAnswerChange: (String) -> Unit,
    enabled: Boolean = true,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(question.stem, style = UiRoles.text(MaterialTheme.typography.bodyLarge))
        if (question.inputKind == LearningInputKind.CHOICE) {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                question.options.forEach { option ->
                    LearningChoiceRow(
                        label = "${option.key}. ${option.text}",
                        selected = answer == option.key,
                        onSelect = { onAnswerChange(option.key) },
                        enabled = enabled,
                    )
                }
            }
        } else {
            OutlinedTextField(
                value = answer,
                onValueChange = onAnswerChange,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                label = { Text("我的作答") },
            )
        }
    }
}

@Composable
internal fun LearningChoiceRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else UiRoles.surface(UiSurfaceRole.Block),
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = { if (!selected) onSelect() },
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, enabled = enabled, onClick = null)
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = UiRoles.text(MaterialTheme.typography.bodyLarge),
                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else UiRoles.foreground,
            )
        }
    }
}
