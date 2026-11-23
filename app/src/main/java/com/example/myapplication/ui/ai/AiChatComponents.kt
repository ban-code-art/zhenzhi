package com.example.myapplication.ui.ai

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.example.myapplication.R
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.navigation.LocalNavigationPageActive
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import kotlinx.coroutines.currentCoroutineContext
import kotlin.math.sin

@Composable
internal fun AiMessageBubble(
    messageId: String,
    content: String,
    isUser: Boolean,
    modifier: Modifier = Modifier,
    citationLabels: Set<String> = emptySet(),
    citationSources: Map<String, AgentSourceUi> = emptyMap(),
    presentation: AgentAnswerPresentation? = null,
    onCitationClick: (String) -> Unit = {},
    beforeContent: @Composable ColumnScope.() -> Unit = {},
    details: @Composable ColumnScope.() -> Unit = {},
) {
    if (isUser) {
        BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            val shape = RoundedCornerShape(15.dp)
            val colors = MaterialTheme.colorScheme
            val ink = colors.onSurface
            Column(
                Modifier.widthIn(max = maxWidth * .85f)
                    .background(lerp(colors.surface, ink, .065f), shape)
                    .border(1.dp, ink.copy(alpha = .13f), shape)
                    .padding(horizontal = 13.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                beforeContent()
                if (content.isNotBlank()) {
                    Text(content, color = ink, style = MaterialTheme.typography.bodyLarge)
                }
                details()
            }
        }
    } else {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            beforeContent()
            if (presentation != null) {
                if (!presentation.blocks.isNullOrEmpty()) {
                    AgentPresentedMarkdown(messageId, presentation, citationLabels = citationLabels,
                        citationSources = citationSources, onPageBackground = true, onCitationClick = onCitationClick)
                }
            } else if (content.isNotBlank()) {
                AgentMarkdown(messageId = messageId, markdown = content,
                    citationLabels = citationLabels, citationSources = citationSources, onPageBackground = true,
                    onCitationClick = onCitationClick)
            }
            details()
        }
    }
}

@Composable
internal fun AiAnswerStatus(
    label: String,
    running: Boolean,
    modifier: Modifier = Modifier,
    showGhost: Boolean = running,
    embedded: Boolean = false,
) {
    val shape = RoundedCornerShape(12.dp)
    val colors = MaterialTheme.colorScheme
    val ink = colors.onSurface
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = running && LocalNavigationPageActive.current && lifecycle == Lifecycle.State.RESUMED
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active) {
        phase = 0f
        if (active && currentCoroutineContext()[MotionDurationScale]?.scaleFactor != 0f) {
            var start = 0L
            while (true) {
                withInfiniteAnimationFrameNanos { now ->
                    if (start == 0L) start = now
                    phase = (now - start) / 1_000_000_000f * 4.6f
                }
            }
        }
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = 46.dp)
            .then(if (embedded) Modifier.background(ink.copy(alpha = .04f))
            else Modifier.background(lerp(colors.surface, ink, .04f), shape)
                .border(1.dp, ink.copy(alpha = .16f), shape))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("ai-answer-status").semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (showGhost) {
            AiGhostAvatar(running = running)
        } else {
            Icon(UiIcons.Records, contentDescription = null, tint = UiRoles.accent, modifier = Modifier.size(24.dp))
        }
        Text(label, Modifier.weight(1f), color = ink.copy(alpha = .88f), fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (running) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) { index ->
                    Box(Modifier.size(3.5.dp).graphicsLayer {
                        val wave = .5f + .5f * sin(phase - index * .65f)
                        alpha = .25f + .65f * wave
                        translationY = (-1.4f * wave).dp.toPx()
                    }.background(ink.copy(alpha = .58f), CircleShape))
                }
            }
        }
    }
}

@Composable
internal fun AiComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    canSend: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onStop: (() -> Unit)? = null,
    placeholder: String = "问 AI……",
    inputModifier: Modifier = Modifier,
    sendModifier: Modifier = Modifier,
    stopModifier: Modifier = Modifier,
    actionPrefix: String = "ai",
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(22.dp)
    val input: @Composable (Modifier) -> Unit = { fieldModifier ->
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = fieldModifier.then(inputModifier),
            enabled = enabled,
            maxLines = 4,
            shape = shape,
            interactionSource = interaction,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
            ),
            placeholder = { Text(placeholder) },
        )
    }
    val action: @Composable () -> Unit = {
        if (onStop != null) {
            UiAction(
                label = "停止",
                onClick = onStop,
                modifier = stopModifier,
                foreground = UiRoles.extended.destructive,
                actionId = "$actionPrefix.stop",
            )
        } else {
            UiAction(
                label = "发送",
                icon = Icons.AutoMirrored.Filled.Send,
                onClick = onSend,
                modifier = sendModifier,
                enabled = enabled && canSend,
                filled = true,
                actionId = "$actionPrefix.send",
            )
        }
    }
    BoxWithConstraints(
        modifier.fillMaxWidth().background(UiRoles.surface(UiSurfaceRole.Card), shape)
            .border(1.dp, if (focused) UiRoles.accent else MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(end = 6.dp, bottom = 3.dp),
    ) {
        if (maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.7f) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                input(Modifier.fillMaxWidth())
                action()
            }
        } else {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                input(Modifier.weight(1f))
                Box(Modifier.padding(bottom = 3.dp)) { action() }
            }
        }
    }
}
