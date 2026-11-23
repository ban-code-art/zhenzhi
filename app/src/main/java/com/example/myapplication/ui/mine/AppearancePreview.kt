package com.example.myapplication.ui.mine

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.appearance.AppearanceDockMotion
import com.example.myapplication.data.appearance.AppearanceFont
import com.example.myapplication.data.appearance.AppearanceGlass
import com.example.myapplication.data.appearance.AppearanceMode
import com.example.myapplication.data.appearance.AppearanceNavigation
import com.example.myapplication.data.appearance.AppearancePreferences
import com.example.myapplication.data.appearance.AppearanceTabMotion
import com.example.myapplication.ui.components.UiGlassQuality
import com.example.myapplication.ui.components.UiGlassQualityReason
import com.example.myapplication.ui.components.UiGlassQualitySnapshot
import com.example.myapplication.ui.theme.MyApplicationPreviewTheme
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import com.example.myapplication.ui.theme.discoverAvailableSystemFonts
import com.example.myapplication.ui.theme.resolveAppearanceFontFamily
import kotlin.math.abs
import kotlin.math.roundToInt

private val PreviewTabs = listOf(
    "书架" to UiIcons.Books,
    "测试" to UiIcons.Quiz,
    "百科" to UiIcons.Encyclopedia,
    "我的" to UiIcons.Person,
)

@Composable
internal fun ThemeLivePreview(
    preferences: AppearancePreferences,
    modifier: Modifier = Modifier,
) {
    var previewTab by remember { mutableIntStateOf(0) }
    val direction = LocalLayoutDirection.current

    Surface(
        modifier = modifier
            .width(168.dp)
            .aspectRatio(9f / 17f)
            .testTag("appearance-preview-phone"),
        shape = RoundedCornerShape(28.dp),
        color = UiRoles.surface(UiSurfaceRole.Block),
    ) {
        MyApplicationPreviewTheme(preferences = preferences) {
            val pageBg = UiRoles.surface(UiSurfaceRole.Page)
            val cardBg = UiRoles.surface(UiSurfaceRole.Card)
            val blockBg = UiRoles.surface(UiSurfaceRole.Block)
            val cs = MaterialTheme.colorScheme
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(pageBg)
                    .border(1.dp, cs.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(24.dp)),
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            PreviewTabs[previewTab].first,
                            style = UiRoles.text(MaterialTheme.typography.titleSmall).copy(
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = UiRoles.pageForeground,
                        )
                        Box(Modifier.size(14.dp).clip(CircleShape).background(cs.primaryContainer))
                    }
                    AnimatedContent(
                        targetState = previewTab,
                        transitionSpec = {
                            if (preferences.tabMotion == AppearanceTabMotion.Slide) {
                                val forward = (targetState > initialState) == (direction == LayoutDirection.Ltr)
                                val sign = if (forward) 1 else -1
                                slideInHorizontally(tween(240)) { it * sign } togetherWith
                                    slideOutHorizontally(tween(240)) { -it * sign }
                            } else {
                                fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                            }
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                        label = "mini-preview-tab",
                    ) { idx ->
                        Column(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            repeat(3) { cardIdx ->
                                Surface(
                                    color = cardBg,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        Modifier.padding(7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Box(
                                            Modifier
                                                .size(16.dp)
                                                .clip(RoundedCornerShape(5.dp))
                                                .background(if ((cardIdx + idx) % 2 == 0) cs.primaryContainer else blockBg),
                                        )
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Box(Modifier.fillMaxWidth(0.7f).height(5.dp).clip(CircleShape).background(cs.onSurface.copy(alpha = 0.72f)))
                                            Box(Modifier.fillMaxWidth(0.45f).height(4.dp).clip(CircleShape).background(cs.onSurfaceVariant.copy(alpha = 0.45f)))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (preferences.navigation == AppearanceNavigation.Fixed) {
                        Surface(
                            color = UiRoles.surface(UiSurfaceRole.Chrome),
                            modifier = Modifier.fillMaxWidth().testTag("appearance-preview-fixed-bar"),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                PreviewTabs.forEachIndexed { index, (label, icon) ->
                                    val selected = index == previewTab
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { previewTab = index }
                                            .testTag("appearance-preview-tab-$index")
                                            .padding(vertical = 2.dp)
                                            .semantics { contentDescription = "预览$label" },
                                    ) {
                                        Icon(
                                            icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(12.dp),
                                            tint = if (selected) cs.primary else cs.onSurfaceVariant,
                                        )
                                        Text(
                                            label,
                                            style = UiRoles.text(MaterialTheme.typography.labelSmall).copy(
                                                fontSize = 7.sp,
                                                lineHeight = 9.sp,
                                            ),
                                            maxLines = 1,
                                            color = if (selected) cs.primary else cs.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Box(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 7.dp)) {
                            MiniFloatingDock(
                                selectedIndex = previewTab,
                                dockMotion = preferences.dockMotion,
                                glass = preferences.glass,
                                onSelect = { previewTab = it },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniFloatingDock(
    selectedIndex: Int,
    dockMotion: AppearanceDockMotion,
    glass: AppearanceGlass,
    onSelect: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val dark = UiRoles.dark
    val pillColor = when {
        glass == AppearanceGlass.None -> UiRoles.surface(UiSurfaceRole.Card)
        dark -> cs.surfaceContainerHigh.copy(alpha = 0.78f)
        else -> cs.surfaceBright.copy(alpha = 0.86f)
    }
    val rimColor = if (glass == AppearanceGlass.None) cs.outlineVariant.copy(alpha = 0.5f)
    else Color.White.copy(alpha = if (dark) 0.28f else 0.75f)
    val indicatorColor = if (glass == AppearanceGlass.None) cs.primary.copy(alpha = 0.16f)
    else cs.primaryContainer.copy(alpha = 0.88f)

    Surface(
        color = pillColor,
        shape = CircleShape,
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .border(1.dp, rimColor, CircleShape)
            .testTag("appearance-preview-floating-dock"),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(3.dp)) {
            val slotWidth = maxWidth / PreviewTabs.size
            val density = LocalDensity.current
            val animatedOffset = remember { Animatable(selectedIndex.toFloat()) }
            val isFade = dockMotion == AppearanceDockMotion.Fade
            val fadeAlpha by animateFloatAsState(
                targetValue = 1f,
                animationSpec = tween(200),
                label = "mini-dock-fade",
            )
            LaunchedEffect(selectedIndex, dockMotion) {
                if (isFade) {
                    animatedOffset.snapTo(selectedIndex.toFloat())
                } else {
                    animatedOffset.animateTo(
                        targetValue = selectedIndex.toFloat(),
                        animationSpec = spring(
                            dampingRatio = 0.78f,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    )
                }
            }
            if (!isFade) {
                val offsetPx = with(density) { (slotWidth * animatedOffset.value).roundToPx() }
                Box(
                    Modifier
                        .offset { IntOffset(offsetPx, 0) }
                        .width(slotWidth)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(indicatorColor)
                        .border(0.75.dp, Color.White.copy(alpha = 0.55f), CircleShape),
                )
            }
            Row(Modifier.fillMaxSize()) {
                PreviewTabs.forEachIndexed { index, (label, icon) ->
                    val selected = index == selectedIndex
                    val chipAlpha by animateFloatAsState(
                        targetValue = if (isFade && selected) fadeAlpha else 0f,
                        animationSpec = tween(220),
                        label = "mini-dock-item-fade",
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(if (isFade) indicatorColor.copy(alpha = chipAlpha * 0.9f) else Color.Transparent)
                            .clickable { onSelect(index) }
                            .testTag("appearance-preview-tab-$index")
                            .semantics { contentDescription = "预览$label" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = if (selected) cs.primary else cs.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AppearanceOptionThumbnail(
    kind: String,
    selected: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg, barColor) = when (kind) {
        "mode-Light" -> Triple(Color(0xFFF3F6FA), Color(0xFF1F2329), cs.primary)
        "mode-Dark" -> Triple(Color(0xFF1A1D21), Color(0xFFE6EAF0), cs.primary)
        "black-true" -> Triple(Color.Black, Color(0xFFE6EAF0), cs.primary)
        "black-false" -> Triple(Color(0xFF1A1D21), Color(0xFFE6EAF0), cs.primary)
        else -> Triple(UiRoles.surface(UiSurfaceRole.Block), cs.onSurfaceVariant, cs.primary)
    }
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) cs.primary else cs.outlineVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(4.dp),
    ) {
        when (kind) {
            "mode-System" -> {
                Row(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp))) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFF3F6FA)))
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF1A1D21)))
                }
            }
            "nav-Floating" -> {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(0.86f)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(barColor.copy(alpha = 0.85f)),
                    )
                }
            }
            "nav-Fixed" -> {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(6.dp)
                            .background(barColor.copy(alpha = 0.85f)),
                    )
                }
            }
            else -> {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.fillMaxWidth(0.6f).height(4.dp).clip(CircleShape).background(barColor))
                    Box(Modifier.fillMaxWidth(0.85f).height(3.dp).clip(CircleShape).background(fg.copy(alpha = 0.45f)))
                    Box(Modifier.fillMaxWidth(0.55f).height(3.dp).clip(CircleShape).background(fg.copy(alpha = 0.35f)))
                }
            }
        }
    }
}

@Composable
internal fun AppearanceScaleSlider(
    value: Float,
    enabled: Boolean,
    onCommitScale: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draftScale by remember { mutableStateOf<Float?>(null) }
    val displayScale = (draftScale ?: value).coerceIn(0.8f, 1.4f)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOnCommit by rememberUpdatedState(onCommitScale)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val cs = MaterialTheme.colorScheme
    val thumbPressed = draftScale != null
    val thumbWidth by animateDpAsState(if (thumbPressed) 2.dp else 4.dp, tween(100), label = "scale-thumb")

    fun scaleAt(xPx: Float, widthPx: Float): Float {
        val ratio = (xPx / widthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val logicalRatio = if (isRtl) 1f - ratio else ratio
        return snapTextScaleStep(0.8f + logicalRatio * 0.6f)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .semantics(mergeDescendants = true) {
                if (!enabled) disabled()
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = displayScale,
                    range = 0.8f..1.4f,
                    steps = 11,
                )
                if (enabled) {
                    setProgress { requested ->
                        val snapped = snapTextScaleStep(requested)
                        draftScale = null
                        latestOnCommit(snapped)
                        true
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("A", style = UiRoles.text(MaterialTheme.typography.bodySmall), color = TextSecondary)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .onPreviewKeyEvent { keyEvent ->
                    if (!latestEnabled || keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val step = when (keyEvent.key) {
                        Key.DirectionRight -> if (isRtl) -0.05f else 0.05f
                        Key.DirectionLeft -> if (isRtl) 0.05f else -0.05f
                        else -> return@onPreviewKeyEvent false
                    }
                    val snapped = snapTextScaleStep(displayScale + step)
                    draftScale = null
                    latestOnCommit(snapped)
                    true
                }
                .pointerInput(isRtl) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!latestEnabled) return@awaitEachGesture
                        var activeDrag = false
                        var pendingCommit: Float? = null
                        try {
                            val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                                activeDrag = true
                                change.consume()
                                val snapped = scaleAt(change.position.x, size.width.toFloat())
                                draftScale = snapped
                                pendingCommit = snapped
                            }
                            if (start != null && activeDrag) {
                                horizontalDrag(start.id) { change ->
                                    val snapped = scaleAt(change.position.x, size.width.toFloat())
                                    draftScale = snapped
                                    pendingCommit = snapped
                                    change.consume()
                                }
                            } else if (!activeDrag) {
                                val lastChange = currentEvent.changes.firstOrNull { it.id == down.id }
                                if (lastChange != null && !lastChange.isConsumed && !lastChange.pressed) {
                                    pendingCommit = scaleAt(lastChange.position.x, size.width.toFloat())
                                }
                            }
                        } finally {
                            draftScale = null
                            pendingCommit?.let { if (latestEnabled) latestOnCommit(it) }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val trackHeight = 16.dp.toPx()
                val thumbHeight = 44.dp.toPx()
                val thumbW = thumbWidth.toPx()
                val gap = 6.dp.toPx()
                val fraction = ((displayScale - 0.8f) / 0.6f).coerceIn(0f, 1f)
                val visualFraction = if (isRtl) 1f - fraction else fraction
                val thumbX = (visualFraction * size.width).coerceIn(thumbW / 2f, size.width - thumbW / 2f)
                val trackTop = (size.height - trackHeight) / 2f
                val leftEnd = (thumbX - thumbW / 2f - gap).coerceAtLeast(0f)
                val rightStart = (thumbX + thumbW / 2f + gap).coerceAtMost(size.width)
                val activeColor = cs.primary
                val inactiveColor = cs.surfaceContainerHighest

                if (leftEnd > 2f) {
                    drawRoundRect(
                        color = if (!isRtl) activeColor else inactiveColor,
                        topLeft = Offset(0f, trackTop),
                        size = Size(leftEnd, trackHeight),
                        cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f),
                    )
                }
                if (size.width - rightStart > 2f) {
                    drawRoundRect(
                        color = if (!isRtl) inactiveColor else activeColor,
                        topLeft = Offset(rightStart, trackTop),
                        size = Size(size.width - rightStart, trackHeight),
                        cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f),
                    )
                }
                drawRoundRect(
                    color = cs.primary,
                    topLeft = Offset(thumbX - thumbW / 2f, (size.height - thumbHeight) / 2f),
                    size = Size(thumbW, thumbHeight),
                    cornerRadius = CornerRadius(thumbW / 2f, thumbW / 2f),
                )
            }
        }
        Text("A", style = UiRoles.text(MaterialTheme.typography.titleMedium), color = TextPrimary)
    }
}

internal fun describeWindowGlassSnapshot(snapshot: UiGlassQualitySnapshot): String {
    val desired = when (snapshot.desired) {
        UiGlassQuality.Ultra -> "极致"
        UiGlassQuality.Standard -> "标准"
        UiGlassQuality.None -> "无"
        UiGlassQuality.Unknown -> "未知"
    }
    val effective = when (snapshot.effective) {
        UiGlassQuality.Ultra -> "极致"
        UiGlassQuality.Standard -> "标准"
        UiGlassQuality.None -> "无"
        UiGlassQuality.Unknown -> "待采样"
    }
    val reason = when (snapshot.reason) {
        UiGlassQualityReason.Disabled -> "已关闭"
        UiGlassQualityReason.Requested -> "按期望启用"
        UiGlassQualityReason.AwaitingCapture -> "等待背景采样"
        UiGlassQualityReason.AwaitingRenderer -> "等待着色器就绪"
        UiGlassQualityReason.HardwareAccelerationUnavailable -> "硬件加速不可用"
        UiGlassQualityReason.ApiTooOld -> "系统版本低于 API 31"
        UiGlassQualityReason.ApiBlurOnly -> "API 31–32 降级为标准模糊"
        UiGlassQualityReason.NoBackdrop -> "当前窗口无背景捕获源"
        UiGlassQualityReason.CrossWindowCapture -> "跨窗口不可采样"
        UiGlassQualityReason.ShaderUnavailable -> "AGSL 着色器不可用"
    }
    return "期望：$desired ｜ 当前窗口有效：$effective（$reason）"
}

internal data class FontPickerEntry(
    val font: AppearanceFont,
    val customFont: String?,
    val label: String,
)

@Composable
internal fun FontSelectionScreen(
    currentFont: AppearanceFont,
    currentCustomFont: String?,
    onSelect: (AppearanceFont, String?) -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val systemFonts = remember { discoverAvailableSystemFonts() }
    val allEntries = remember(systemFonts) {
        buildList {
            add(FontPickerEntry(AppearanceFont.System, null, "默认（跟随系统）"))
            add(FontPickerEntry(AppearanceFont.Sans, null, "无衬线（Sans Serif）"))
            add(FontPickerEntry(AppearanceFont.Serif, null, "衬线（Serif）"))
            add(FontPickerEntry(AppearanceFont.Monospace, null, "等宽（Monospace）"))
            systemFonts.forEach { sys ->
                add(FontPickerEntry(AppearanceFont.System, sys.id, sys.displayName))
            }
        }
    }
    val filtered = remember(allEntries, query) {
        val needle = query.trim()
        if (needle.isEmpty()) allEntries
        else allEntries.filter { it.label.contains(needle, ignoreCase = true) }
    }

    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        SubPageScaffold(
            title = "字体",
            onBack = onBack,
            glassBack = true,
            actionId = "appearance.font.back",
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(start = 16.dp, top = GlassPageBarHeight + 8.dp, end = 16.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("搜索可用字体") },
                    modifier = Modifier.fillMaxWidth().testTag("appearance-font-search"),
                )
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = UiRoles.surface(UiSurfaceRole.Card),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    LazyColumn(Modifier.fillMaxSize().testTag("appearance-font-list")) {
                        items(filtered, key = { "${it.font.name}:${it.customFont.orEmpty()}" }) { entry ->
                            val selected = entry.font == currentFont && entry.customFont == currentCustomFont
                            val previewFamily = remember(entry) {
                                resolveAppearanceFontFamily(entry.font, entry.customFont)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .then(
                                        if (entry.customFont == null) Modifier.testTag("appearance-font-option-${entry.font.name}")
                                        else Modifier,
                                    )
                                    .selectable(
                                        selected = selected,
                                        role = Role.RadioButton,
                                        onClick = {
                                            if (!selected) onSelect(entry.font, entry.customFont)
                                        },
                                    )
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = entry.label,
                                        style = UiRoles.text(UiRoles.rowText),
                                        color = TextPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "针灸经络 123 Aa",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = previewFamily),
                                        color = TextSecondary,
                                    )
                                }
                                if (selected) {
                                    Icon(
                                        UiIcons.Check,
                                        contentDescription = "已选",
                                        modifier = Modifier.size(20.dp),
                                        tint = UiRoles.accent,
                                    )
                                }
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                thickness = 0.5.dp,
                                color = HairlineDivider,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
