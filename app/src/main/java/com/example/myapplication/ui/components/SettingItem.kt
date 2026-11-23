package com.example.myapplication.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles

/** One merged action; decorative icons never own a second click handler. Text grows vertically. */
@Composable
fun SettingItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    showArrow: Boolean = true,
    iconTint: Color = UiRoles.iconTint,
    iconContainerColor: Color = UiRoles.iconContainer,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    require(title.isNotBlank()) { "setting title must not be blank" }
    val details = listOfNotNull(summary, value).filter(String::isNotBlank)
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .semantics { contentDescription = (listOf(title) + details).joinToString("，") }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(16.dp)).background(iconContainerColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = iconTint)
            }
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 10.dp).clearAndSetSemantics {}) {
            Text(title, style = UiRoles.text(UiRoles.rowText), color = titleColor)
            details.forEach { detail ->
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (showArrow) {
            Box(Modifier.width(36.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(
                    UiIcons.Chevron,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
