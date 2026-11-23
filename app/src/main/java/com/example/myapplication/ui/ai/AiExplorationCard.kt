package com.example.myapplication.ui.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.theme.UiIcons

@Composable
internal fun AiDocumentBadge(
    title: String,
    modifier: Modifier = Modifier,
    neutral: Boolean = false,
) {
    val ink = if (neutral) MaterialTheme.colorScheme.onBackground.copy(alpha = .8f) else when ((title.hashCode() and Int.MAX_VALUE) % 5) {
        0 -> Color(0xFF9E4FC4)
        1 -> Color(0xFFB9521F)
        2 -> Color(0xFF188AA0)
        3 -> Color(0xFF5675AE)
        else -> Color(0xFF5C8A55)
    }
    val cleanTitle = title.trim().ifEmpty { "文" }
    val glyph = cleanTitle.substring(0, cleanTitle.offsetByCodePoints(0, 1))
    Box(
        modifier.size(18.dp).background(ink.copy(alpha = .14f), if (neutral) CircleShape else RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = ink, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
internal fun AiExplorationCard(
    message: AgentMessageUi,
    waiting: Boolean,
    label: String?,
    summaryText: String? = null,
    onPreview: (AgentSourceUi) -> Unit,
) {
    val hasSteps = message.toolSteps.isNotEmpty() || summaryText != null
    var manualOpen by rememberSaveable(message.presentationKey) { mutableStateOf<Boolean?>(null) }
    val open = manualOpen ?: waiting
    val shape = RoundedCornerShape(12.dp)
    val colors = MaterialTheme.colorScheme
    val ink = colors.onSurface
    val muted = ink.copy(alpha = .66f)
    val divider = ink.copy(alpha = .12f)
    val readSteps = message.toolSteps.filter { it.action == "阅读" }
    val titles = readSteps.distinctBy { it.documentId ?: it.subject }.take(3).map { step ->
        message.sources.firstOrNull { it.documentId == step.documentId }?.documentTitle ?: step.subject
    }
    Column(
        Modifier.fillMaxWidth().testTag("ai-exploration").clip(shape)
            .background(lerp(colors.surface, ink, .035f)).border(1.dp, ink.copy(alpha = if (waiting) .18f else .10f), shape),
    ) {
        AnimatedVisibility(waiting, enter = expandVertically(tween(160)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(160))) {
            AiAnswerStatus(label ?: "正在查找…", running = waiting, showGhost = true, embedded = true)
        }
        if (hasSteps) {
            if (waiting) HorizontalDivider(color = divider)
            val angle by animateFloatAsState(if (open) 90f else 0f, tween(180), label = "exploration chevron")
            Row(
                Modifier.fillMaxWidth().heightIn(min = 36.dp).testTag("ai-exploration-toggle")
                    .clickable(role = Role.Button) { manualOpen = !open }
                    .semantics { stateDescription = if (open) "已展开查找过程" else "已收起查找过程" }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (titles.isEmpty()) {
                    Box(Modifier.size(18.dp).background(ink.copy(alpha = .11f), RoundedCornerShape(5.dp)),
                        contentAlignment = Alignment.Center) {
                        ExplorationSparkles(muted)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        titles.forEach { AiDocumentBadge(it) }
                    }
                }
                Text(summaryText ?: "查找了 ${message.toolSteps.size} 步 · 阅读 ${readSteps.size} 处",
                    Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    color = ink.copy(alpha = .84f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(UiIcons.Chevron, null, Modifier.size(15.dp).rotate(angle), tint = muted)
            }
        }
        AnimatedVisibility(open && message.toolSteps.isNotEmpty(), enter = expandVertically(tween(180)),
            exit = shrinkVertically(tween(180))) {
            Column {
                HorizontalDivider(color = divider)
                Column(Modifier.padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 7.dp)) {
                    message.toolSteps.forEachIndexed { index, step ->
                        key(step.callId) {
                            AiExplorationStep(message.sources, step, divider, ink, muted,
                                connectedAbove = index > 0, connectedBelow = index < message.toolSteps.lastIndex,
                                onPreview = onPreview)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiExplorationStep(
    sources: List<AgentSourceUi>,
    step: AgentToolStepUi,
    divider: Color,
    ink: Color,
    muted: Color,
    connectedAbove: Boolean,
    connectedBelow: Boolean,
    onPreview: (AgentSourceUi) -> Unit,
) {
    val source = remember(step, sources) { AgentExploration.sourceForStep(step, sources) }
    val canExpand = source == null && step.results.isNotEmpty()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val failed = step.status == AgentToolStatus.FAILED
    val resultInk = if (failed) MaterialTheme.colorScheme.error else muted
    val showResultChip = failed || step.action == "检索" || step.action == "搜索"
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 32.dp).drawBehind {
                val railX = 9.dp.toPx()
                if (connectedAbove) drawLine(divider, Offset(railX, 0f), Offset(railX, size.height / 2), 1.5.dp.toPx())
                if (connectedBelow) drawLine(divider, Offset(railX, size.height / 2), Offset(railX, size.height), 1.5.dp.toPx())
            }.clickable(enabled = source != null || canExpand) {
                if (source != null) onPreview(source) else expanded = !expanded
            }.padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (step.action == "阅读") {
                AiDocumentBadge(source?.documentTitle ?: step.subject)
            } else {
                Box(Modifier.size(18.dp).background(ink.copy(alpha = .10f), CircleShape), contentAlignment = Alignment.Center) {
                    if (step.action == "检索") ExplorationSparkles(resultInk)
                    else Icon(UiIcons.Search, null, Modifier.size(12.dp), tint = resultInk)
                }
            }
            Text(step.action, fontSize = 12.sp, color = resultInk, maxLines = 1)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(source?.documentTitle ?: step.subject, Modifier.weight(1f, fill = false),
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (failed) resultInk else ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                step.range?.let {
                    Text(it, Modifier.widthIn(max = 96.dp).background(divider, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                        fontSize = 10.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(step.summary, Modifier.widthIn(max = 105.dp)
                .then(if (showResultChip) Modifier.background(divider, RoundedCornerShape(5.dp)) else Modifier)
                .padding(horizontal = 4.dp, vertical = 1.dp), fontSize = 11.sp, color = resultInk,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (canExpand) Icon(UiIcons.Chevron, null, Modifier.size(12.dp).rotate(if (expanded) 90f else 0f), tint = muted)
        }
        AnimatedVisibility(expanded && canExpand) {
            Column(Modifier.padding(start = 25.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                step.results.forEach { hit ->
                    val linked = sources.singleOrNull { it.documentId == hit.documentId &&
                        (hit.sectionId == null || it.sectionId == hit.sectionId) && it.quote.startsWith(hit.quote) }
                    Column(Modifier.fillMaxWidth().clickable(enabled = linked != null) { linked?.let(onPreview) }) {
                        Text(linked?.documentTitle ?: hit.title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ink)
                        Text(hit.quote, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, color = muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExplorationSparkles(tint: Color) {
    Canvas(Modifier.size(12.dp)) {
        fun sparkle(x: Float, y: Float, radius: Float) {
            val centerX = x * size.width
            val centerY = y * size.height
            val outer = radius * size.minDimension
            val inner = outer * .25f
            drawPath(Path().apply {
                moveTo(centerX, centerY - outer)
                quadraticTo(centerX + inner, centerY - inner, centerX + outer, centerY)
                quadraticTo(centerX + inner, centerY + inner, centerX, centerY + outer)
                quadraticTo(centerX - inner, centerY + inner, centerX - outer, centerY)
                quadraticTo(centerX - inner, centerY - inner, centerX, centerY - outer)
                close()
            }, tint)
        }
        sparkle(.42f, .58f, .38f)
        sparkle(.82f, .23f, .17f)
    }
}

@Composable
internal fun AiSourcesFooter(message: AgentMessageUi, onPreview: (AgentSourceUi) -> Unit) {
    var open by rememberSaveable(message.presentationKey) { mutableStateOf(false) }
    val ink = MaterialTheme.colorScheme.onBackground
    val muted = ink.copy(alpha = .7f)
    val background = MaterialTheme.colorScheme.background
    val angle by animateFloatAsState(if (open) 180f else 0f, tween(200), label = "sources chevron")
    Column {
        Row(
            Modifier.testTag("ai-sources-toggle").heightIn(min = 36.dp).clip(CircleShape)
                .clickable(role = Role.Button) { open = !open }
                .semantics { stateDescription = if (open) "已展开来源" else "已收起来源" }
                .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy((-5).dp)) {
                message.sources.take(4).forEach { source ->
                    Box(Modifier.background(background, CircleShape).padding(2.dp)) {
                        AiDocumentBadge(source.documentTitle, neutral = true)
                    }
                }
            }
            Text("${message.sources.size} 个来源", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = muted)
            Icon(UiIcons.ChevronDown, null, Modifier.size(15.dp).rotate(angle), tint = muted)
        }
        AnimatedVisibility(open, enter = expandVertically(tween(220)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(220)) + fadeOut(tween(220))) {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                message.sources.forEach { source ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPreview(source) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Text(source.ordinal.toString(), fontSize = 12.sp, color = muted)
                        AiDocumentBadge(source.documentTitle, neutral = true)
                        Text(source.documentTitle, Modifier.weight(1f), fontSize = 13.sp, color = ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        source.sectionTitle?.let {
                            Text(it, Modifier.widthIn(max = 100.dp), fontSize = 11.sp, color = muted,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(UiIcons.Chevron, null, Modifier.size(14.dp), tint = muted)
                    }
                }
            }
        }
    }
}
