package com.example.myapplication.ui.knowledge.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.components.ColorSwatchOption
import com.example.myapplication.ui.components.ColorSwatchRow

/** Stateless font-color choice; callers own preview, persistence and format capability checks. */
@Composable
fun ReaderFontColorPicker(
    value: ReaderFontColor,
    theme: ReaderTheme,
    onChange: (ReaderFontColor) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledReason: String? = null,
    automaticHex: String = ReaderFontColor.Automatic.resolve(theme),
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("字体颜色", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface)
        ColorSwatchRow(
            options = readerFontColorOptions(value, theme, automaticHex),
            selectedKey = value.encode(),
            enabled = enabled,
            onSelected = { key ->
                // Keep the callback guard here as well as in the reusable row's semantics.
                if (enabled) ReaderFontColor.parse(key)?.let(onChange)
            },
        )
        if (!enabled) {
            Text(disabledReason?.takeIf { it.isNotBlank() } ?: "当前阅读内容不支持字体颜色设置",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** AUTO and an identical explicit RGB choice remain separate, stable selection keys. */
internal fun readerFontColorOptions(
    value: ReaderFontColor,
    theme: ReaderTheme,
    automaticHex: String = ReaderFontColor.Automatic.resolve(theme),
): List<ColorSwatchOption> {
    val presets = if (theme == ReaderTheme.NIGHT) listOf(
        "#E6EEE9" to "浅绿", "#E0E0E0" to "浅灰", "#E7C9AE" to "浅棕", "#B9D0EE" to "浅蓝",
    ) else listOf(
        "#2F3B36" to "墨绿", "#303030" to "深灰", "#6B4F3A" to "棕色", "#29445B" to "藏蓝",
    )
    return buildList {
        add(ColorSwatchOption("AUTO", automaticHex, "自动"))
        presets.forEach { (hex, name) -> add(ColorSwatchOption(hex, hex, name)) }
        // Retain external/custom intent when it is absent from the current theme's presets.
        if (none { it.key == value.encode() }) {
            add(ColorSwatchOption(value.encode(), value.resolve(theme), "当前自定义色 ${value.encode()}"))
        }
    }
}
