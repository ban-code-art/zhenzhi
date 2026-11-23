package com.example.myapplication.ui.knowledge.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.learning.StudyCardAtmosphere
import com.example.myapplication.ui.components.learning.rememberStudyCardIridescentStyle
import com.example.myapplication.ui.components.learning.studyCardReadability
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

private val BookShape = RoundedCornerShape(18.dp)

@Composable
internal fun HomeStackCard(
    courseCount: Int,
    importedCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    showAtmosphere: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val layers = (courseCount + importedCount - 1).coerceIn(0, 2)
    val summary = listOfNotNull(
        courseCount.takeIf { it > 0 }?.let { "$it 本教材" },
        importedCount.takeIf { it > 0 }?.let { "$it 本导入书" },
    ).joinToString(" · ")

    StudyCardAtmosphere(
        layers = layers,
        onClick = onClick,
        modifier = modifier.homeDeckInteraction(interactive),
        interactive = interactive,
        localParticles = showAtmosphere,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 126.dp).padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f).studyCardReadability(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "学习卡片",
                    style = UiRoles.text(MaterialTheme.typography.titleLarge),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    summary,
                    style = UiRoles.text(MaterialTheme.typography.bodySmall),
                    color = colors.onSurfaceVariant,
                )
            }
            if (showAtmosphere) {
                Box(Modifier.studyCardReadability()) { StackMark(layers) }
            } else {
                Spacer(Modifier.size(64.dp, 52.dp))
            }
        }
    }
}

@Composable
internal fun HomeDeckBookCard(
    title: String,
    metadata: String,
    detail: String?,
    action: String,
    canOpen: Boolean,
    tag: String,
    actionTag: String?,
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier,
    handle: (@Composable () -> Unit)? = null,
    interactive: Boolean = true,
    contentAlpha: (() -> Float)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val style = rememberStudyCardIridescentStyle()
    val card = UiRoles.surface(UiSurfaceRole.Card)
    val accent = UiRoles.accent
    val spine = lerp(card, accent, .52f)
    val spineTip = lerp(spine, style.rimHighlight, .35f)
    val foreground = UiRoles.foreground
    val cardColors = CardDefaults.cardColors(
        containerColor = card,
        contentColor = foreground,
        disabledContainerColor = card,
        disabledContentColor = foreground,
    )
    val outline = BorderStroke(
        1.dp,
        Brush.linearGradient(
            listOf(style.miniRimA, colors.outlineVariant, style.miniRimB),
        ),
    )
    val elevation = CardDefaults.cardElevation(
        defaultElevation = 2.dp,
        pressedElevation = 2.dp,
        focusedElevation = 2.dp,
        hoveredElevation = 2.dp,
        draggedElevation = 2.dp,
        disabledElevation = 2.dp,
    )
    val canInteract = interactive && canOpen && onOpen != null
    val allowWrapping = LocalDensity.current.fontScale >= 1.3f
    Card(
        onClick = { if (interactive && canOpen) onOpen?.invoke() },
        enabled = canInteract,
        modifier = modifier.homeDeckInteraction(interactive)
            .then(if (interactive) Modifier.testTag(tag) else Modifier),
        shape = BookShape,
        colors = cardColors,
        elevation = elevation,
        border = outline,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .graphicsLayer { alpha = (contentAlpha?.invoke() ?: 1f).coerceIn(0f, 1f) }
                .drawBehind {
                    drawRect(
                        brush = Brush.linearGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                .38f to style.miniWashA,
                                .74f to style.miniWashB,
                                1f to Color.Transparent,
                            ),
                            start = Offset(size.width * .15f, 0f),
                            end = Offset(size.width, size.height),
                        ),
                    )
                    val width = 3.dp.toPx()
                    val inset = 12.dp.toPx()
                    val leading = if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - width
                    val height = (size.height - inset * 2).coerceAtLeast(0f)
                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(spineTip, spine)),
                        topLeft = Offset(leading, inset),
                        size = Size(width, height),
                        cornerRadius = CornerRadius(width / 2f),
                    )
                }
                .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 34.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = UiRoles.text(MaterialTheme.typography.titleMedium),
                    color = UiRoles.foreground,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (allowWrapping) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                handle?.invoke()
            }
            Text(metadata, style = UiRoles.text(MaterialTheme.typography.bodySmall), color = colors.onSurfaceVariant)
            detail?.let {
                Text(
                    it,
                    style = UiRoles.text(MaterialTheme.typography.bodySmall),
                    color = colors.onSurfaceVariant,
                    maxLines = if (allowWrapping) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val taggedAction = if (canInteract && actionTag != null) Modifier.testTag(actionTag) else Modifier
            Text(
                action,
                modifier = taggedAction.padding(top = 3.dp),
                style = UiRoles.text(MaterialTheme.typography.labelLarge),
                color = if (canOpen) accent else colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun HomeDeckResume(
    title: String,
    readPoint: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    Surface(
        onClick = { if (interactive) onClick() },
        enabled = interactive,
        modifier = modifier.homeDeckInteraction(interactive).fillMaxWidth(),
        shape = BookShape,
        color = UiRoles.surface(UiSurfaceRole.Card),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("继续阅读", style = UiRoles.text(MaterialTheme.typography.labelLarge), color = UiRoles.accent)
                Text(
                    title,
                    style = UiRoles.text(MaterialTheme.typography.titleMedium),
                    color = UiRoles.foreground,
                    fontWeight = FontWeight.SemiBold,
                )
                readPoint?.let {
                    Text(
                        "上次读到 · $it",
                        style = UiRoles.text(MaterialTheme.typography.bodySmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ForwardArrow()
        }
    }
}

@Composable
internal fun HomeDeckShortcut(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    UiAction(
        label = label,
        onClick = { if (interactive) onClick() },
        enabled = interactive,
        keepEnabledStyle = true,
        modifier = modifier.homeDeckInteraction(interactive),
        visualModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        foregroundModifier = Modifier.fillMaxWidth(),
        trailingIcon = UiIcons.Chevron,
        trailingTint = UiRoles.accent,
        fillLabelWidth = true,
        actionId = "home.shortcut.$label",
    )
}

private fun Modifier.homeDeckInteraction(interactive: Boolean): Modifier =
    if (interactive) this else Modifier
        .clearAndSetSemantics {}
        .focusProperties { canFocus = false }
        .then(this)

@Composable
private fun ForwardArrow() {
    Icon(
        UiIcons.Chevron,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = UiRoles.accent,
    )
}

@Composable
private fun StackMark(layers: Int) {
    val tint = UiRoles.accent
    val base = UiRoles.surface(UiSurfaceRole.Card)
    Surface(color = base, shape = RoundedCornerShape(14.dp), modifier = Modifier.size(46.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(26.dp)) {
                val edge = size.width
                val stroke = Stroke(width = 1.5.dp.toPx())
                val corners = CornerRadius(3.dp.toPx())
                for (index in layers downTo 0) {
                    val offset = edge * index * .11f
                    val startX = if (layoutDirection == LayoutDirection.Ltr) offset else edge * .22f - offset
                    val origin = Offset(startX + edge * .07f, edge * .12f + offset)
                    val bounds = Size(edge * .65f, edge * .51f)
                    drawRoundRect(base, topLeft = origin, size = bounds, cornerRadius = corners)
                    drawRoundRect(tint, topLeft = origin, size = bounds, cornerRadius = corners, style = stroke)
                }
            }
        }
    }
}
