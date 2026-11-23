package com.example.myapplication.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Opaque display colors identified by stable keys; deliberately independent of AnnotationColor. */
data class ColorSwatchOption(val key: String, val hex: String, val displayName: String) {
    init {
        require(key.isNotBlank()) { "color key must not be blank" }
        require(displayName.isNotBlank()) { "color name must not be blank" }
        require(Regex("#[0-9a-fA-F]{6}").matches(hex)) { "color must be opaque #RRGGBB" }
    }
}

/** Stateless selection with wrapping names and one radio action per option. Unknown keys select none. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorSwatchRow(
    options: List<ColorSwatchOption>,
    selectedKey: String?,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    require(options.map { it.key }.distinct().size == options.size) { "color keys must be unique" }
    FlowRow(
        modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (option in options) {
            val selected = option.key == selectedKey
            Column(
                Modifier.widthIn(min = 64.dp, max = 128.dp).heightIn(min = 64.dp)
                    .clearAndSetSemantics {
                        contentDescription = option.displayName
                        this.selected = selected
                        role = Role.RadioButton
                        stateDescription = if (selected) "已选中" else "未选中"
                        if (enabled) onClick { onSelected(option.key); true } else disabled()
                    }
                    .selectable(selected = selected, enabled = enabled, role = Role.RadioButton,
                        onClick = { onSelected(option.key) })
                    .alpha(if (enabled) 1f else 0.38f)
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.size(36.dp)
                    .border(if (selected) 2.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(28.dp).background(Color(0xFF000000L or option.hex.drop(1).toLong(16)), CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                    if (selected) {
                        Box(Modifier.align(Alignment.BottomEnd).size(20.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
                Text(option.displayName, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            }
        }
    }
}
