package com.example.myapplication.ui.encyclopedia

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected as semanticsSelected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.encyclopedia.AtlasViewId
import com.example.myapplication.data.encyclopedia.EncyclopediaSnapshot
import com.example.myapplication.data.encyclopedia.EncyclopediaSourceRef
import com.example.myapplication.ui.components.LocalUiBackdrop
import com.example.myapplication.ui.components.LocalUiGlassQuality
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiBackdrop
import com.example.myapplication.ui.components.UiGlassQuality
import com.example.myapplication.ui.components.UiPane
import com.example.myapplication.ui.components.UiPaneRole
import com.example.myapplication.ui.components.UiSheet
import com.example.myapplication.ui.components.dockOverdragPosition
import com.example.myapplication.ui.components.resolveDockDragTarget
import com.example.myapplication.ui.encyclopedia.atlas.ALL_MERIDIANS_CODE
import com.example.myapplication.ui.encyclopedia.atlas.AtlasBox
import com.example.myapplication.ui.knowledge.course.CourseSearchField
import com.example.myapplication.ui.theme.UiColorRole
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import kotlin.math.roundToInt

@Composable
internal fun rememberEncyclopediaAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    val durationScale = androidx.compose.runtime.produceState(initialValue = 1f, context) {
        val sysScale = runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f)
        val composeScale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
        value = if (sysScale <= 0f || composeScale <= 0f) 0f else minOf(sysScale, composeScale)
    }.value
    return durationScale > 0f
}

/**
 * 百科主视图顶部标题、来源回归提示、搜索框与收藏图标按钮。
 */
@Composable
internal fun EncyclopediaMainHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    searchResults: List<EncyclopediaSearchResult>,
    onSelectResult: (EncyclopediaSearchResult) -> Unit,
    onOpenFavorites: () -> Unit,
    hasFavorites: Boolean,
    originSymptomName: String?,
    onBackToOriginSymptom: () -> Unit,
    backdrop: UiBackdrop?,
    modifier: Modifier = Modifier,
) {
    val animate = rememberEncyclopediaAnimationsEnabled()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!originSymptomName.isNullOrBlank()) {
            Surface(
                onClick = onBackToOriginSymptom,
                shape = CircleShape,
                color = UiRoles.color(UiColorRole.PrimaryContainer),
                contentColor = UiRoles.color(UiColorRole.OnPrimaryContainer),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("encyclopedia-origin-back"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(UiIcons.Back, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(
                        text = "返回$originSymptomName",
                        style = UiRoles.actionText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "经学百科",
                    style = UiRoles.text(MaterialTheme.typography.headlineSmall),
                    fontWeight = FontWeight.Bold,
                    color = UiRoles.pageForeground,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = "看经络 · 识穴位 · 学病症",
                    style = UiRoles.text(MaterialTheme.typography.bodySmall),
                    color = UiRoles.pageSupporting,
                )
            }
            UiAction(
                label = "查看收藏穴位",
                icon = if (hasFavorites) UiIcons.FavoriteFilled else UiIcons.Favorite,
                onClick = onOpenFavorites,
                backdrop = backdrop,
                foreground = if (hasFavorites) UiRoles.accent else UiRoles.foreground,
                actionId = "encyclopedia.header.favorites",
                modifier = Modifier.testTag("encyclopedia-favorites-button"),
            )
        }

        CourseSearchField(
            value = query,
            onValueChange = onQueryChange,
            tag = "encyclopedia-search-input",
            placeholder = "搜索经络、穴位、病症",
        )

        AnimatedVisibility(
            visible = query.isNotBlank(),
            enter = if (animate) fadeIn(tween(150)) else fadeIn(tween(0)),
            exit = if (animate) fadeOut(tween(120)) else fadeOut(tween(0)),
        ) {
            EncyclopediaSearchDropdown(
                results = searchResults,
                onSelect = onSelectResult,
                backdrop = backdrop,
            )
        }
    }
}

@Composable
private fun EncyclopediaSearchDropdown(
    results: List<EncyclopediaSearchResult>,
    onSelect: (EncyclopediaSearchResult) -> Unit,
    backdrop: UiBackdrop?,
) {
    val outline = UiRoles.color(UiColorRole.OutlineVariant)
    UiPane(
        role = UiPaneRole.Menu,
        shape = RoundedCornerShape(18.dp),
        backdrop = backdrop,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, outline, RoundedCornerShape(18.dp))
            .testTag("encyclopedia-search-results"),
    ) {
        if (results.isEmpty()) {
            Text(
                text = "没有匹配的词条\n可试试「尺泽」「LU5」「肺经」「咳嗽」「脚踝疼」",
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                color = UiRoles.color(UiColorRole.OnSurfaceVariant),
            )
        } else {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                results.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            thickness = 0.5.dp,
                            color = outline,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clickable(role = Role.Button, onClick = { onSelect(item) })
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .testTag("encyclopedia-search-item-${item.kind.name}-${item.stableId}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).padding(end = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = item.title,
                                style = UiRoles.text(MaterialTheme.typography.bodyLarge),
                                fontWeight = FontWeight.Medium,
                                color = if (item.exactCodeMatch) UiRoles.accent else UiRoles.foreground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = item.subtitle,
                                style = UiRoles.text(MaterialTheme.typography.bodySmall),
                                color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = item.kind.label,
                                style = UiRoles.text(MaterialTheme.typography.labelMedium),
                                color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                            )
                            Icon(
                                UiIcons.Chevron,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = UiRoles.color(UiColorRole.OnSurfaceVariant),
                            )
                        }
                    }
                }
            }
        }
    }
}

internal data class SegmentedTabOption<T>(
    val value: T,
    val label: String,
    val testTag: String,
    val selectedDescription: String? = null,
)

private class SegmentedDragRelease(val position: Float, val velocity: Float)

private const val SegmentedOverdragLimit = 0.25f

/**
 * 通用移动透镜分段控件：对齐 AppBottomBar 的滑动拖拽、弹性物理过渡、玻璃材质与高对比主题色选区。
 */
@Composable
internal fun <T> EncyclopediaSegmentedControl(
    options: List<SegmentedTabOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: UiBackdrop? = LocalUiBackdrop.current,
    compact: Boolean = false,
) {
    if (options.isEmpty()) return
    val selectedIndex = options.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    val latestSelection by rememberUpdatedState(selectedIndex)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val animate = rememberEncyclopediaAnimationsEnabled()
    val desiredGlass = LocalUiGlassQuality.current.desired
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val dark = UiRoles.dark
    val colors = MaterialTheme.colorScheme
    val padDp = 4.dp
    val containerHeight = if (compact) 46.dp else 50.dp

    val position = remember { Animatable(selectedIndex.toFloat()) }
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    var release by remember { mutableStateOf<SegmentedDragRelease?>(null) }
    var appliedRelease by remember { mutableStateOf<SegmentedDragRelease?>(null) }
    val dragging = dragPosition != null

    val tintAlpha = if (desiredGlass == UiGlassQuality.None) 0.96f else if (dark) 0.46f else 0.32f
    val selectedFill = lerp(colors.primaryContainer, UiRoles.accent, if (dark) 0.34f else 0.24f)
        .copy(alpha = tintAlpha * if (dragging) 0.65f else 1f)
    val lensBorderColor = UiRoles.accent.copy(
        alpha = if (desiredGlass == UiGlassQuality.None) 0.78f else if (dark) 0.56f else 0.40f,
    )

    LaunchedEffect(selectedIndex, dragging, release, animate) {
        if (dragging) {
            position.stop()
            return@LaunchedEffect
        }
        val currentRelease = release?.takeIf { it !== appliedRelease }
        if (currentRelease != null) {
            position.snapTo(currentRelease.position)
            appliedRelease = currentRelease
        }
        if (!animate) {
            position.snapTo(selectedIndex.toFloat())
        } else {
            position.animateTo(
                selectedIndex.toFloat(),
                spring(.9f, 650f),
                initialVelocity = currentRelease?.velocity ?: position.velocity,
            )
        }
    }

    val borderModifier = if (desiredGlass == UiGlassQuality.None) {
        Modifier.border(1.dp, UiRoles.color(UiColorRole.OutlineVariant), CircleShape)
    } else Modifier

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = containerHeight)
            .then(borderModifier)
            .pointerInput(options.size, rtl) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val padding = padDp.toPx()
                    val cell = (size.width - 2 * padding) / options.size
                    if (cell <= 0f) return@awaitEachGesture
                    var completed = false
                    var velocity = 0f
                    try {
                        val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                            ?: return@awaitEachGesture
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        tracker.addPosition(slop.uptimeMillis, slop.position)
                        var lastX = slop.position.x
                        dragPosition = dockOverdragPosition(lastX, padding, cell, options.size, rtl)
                            .coerceIn(-SegmentedOverdragLimit, options.lastIndex + SegmentedOverdragLimit)
                        completed = horizontalDrag(slop.id) { change ->
                            lastX = change.position.x
                            tracker.addPosition(change.uptimeMillis, change.position)
                            dragPosition = dockOverdragPosition(lastX, padding, cell, options.size, rtl)
                                .coerceIn(-SegmentedOverdragLimit, options.lastIndex + SegmentedOverdragLimit)
                            change.consume()
                        }
                        if (completed) {
                            currentEvent.changes.firstOrNull { it.id == slop.id }?.let {
                                lastX = it.position.x
                                tracker.addPosition(it.uptimeMillis, it.position)
                                dragPosition = dockOverdragPosition(lastX, padding, cell, options.size, rtl)
                                    .coerceIn(-SegmentedOverdragLimit, options.lastIndex + SegmentedOverdragLimit)
                            }
                            val pixelsPerSecond = tracker.calculateVelocity().x
                            velocity = (pixelsPerSecond / cell * if (rtl) -1f else 1f).coerceIn(-12f, 12f)
                            resolveDockDragTarget(
                                lastX = lastX,
                                velocityX = pixelsPerSecond,
                                currentIndex = latestSelection,
                                itemCount = options.size,
                                endPaddingPx = padding,
                                cellWidthPx = cell,
                                rtl = rtl,
                            )?.let { targetIdx -> latestOnSelect(options[targetIdx].value) }
                        }
                    } finally {
                        dragPosition?.let { release = SegmentedDragRelease(it, if (completed) velocity else 0f) }
                        dragPosition = null
                    }
                }
            },
    ) {
        val density = LocalDensity.current
        val pitch = (maxWidth - padDp * 2) / options.size
        val pitchPx = with(density) { pitch.toPx() }
        val padPx = with(density) { padDp.toPx() }

        UiPane(
            role = UiPaneRole.Pill,
            shape = CircleShape,
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth().heightIn(min = containerHeight),
        ) {
            Box(
                Modifier
                    .align(AbsoluteAlignment.CenterLeft)
                    .width(pitch)
                    .height(containerHeight - 8.dp)
                    .absoluteOffset {
                        val pending = release?.takeIf { it !== appliedRelease }?.position
                        val logical = (dragPosition ?: pending ?: position.value)
                            .coerceIn(-SegmentedOverdragLimit, options.lastIndex + SegmentedOverdragLimit)
                        val physical = if (rtl) options.lastIndex - logical else logical
                        IntOffset((padPx + physical * pitchPx).roundToInt(), 0)
                    }
                    .graphicsLayer {
                        scaleX = if (dragging) 1.03f else 1f
                        scaleY = if (dragging) 1.04f else 1f
                    }
                    .clip(CircleShape),
            ) {
                UiPane(
                    role = UiPaneRole.Lens,
                    shape = CircleShape,
                    backdrop = backdrop,
                    lensIntensity = if (dragging) 1f else .68f,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(selectedFill)
                            .border(1.dp, lensBorderColor, CircleShape),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = containerHeight)
                    .padding(horizontal = padDp)
                    .selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                options.forEach { option ->
                    val isSelected = option.value == selected
                    val textColor = if (isSelected) {
                        if (desiredGlass == UiGlassQuality.None) colors.onPrimaryContainer else UiRoles.accent
                    } else {
                        UiRoles.color(UiColorRole.OnSurfaceVariant)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clip(CircleShape)
                            .selectable(
                                selected = isSelected,
                                interactionSource = null,
                                indication = null,
                                role = Role.Tab,
                                onClick = { if (!isSelected) latestOnSelect(option.value) },
                            )
                            .semantics {
                                semanticsSelected = isSelected
                                option.selectedDescription?.let { contentDescription = it }
                            }
                            .testTag(option.testTag),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = option.label,
                            style = if (compact) UiRoles.text(MaterialTheme.typography.labelLarge)
                            else UiRoles.actionText,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = textColor,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 筛选流式胶囊栏：48dp 最小触控目标，窄屏或大字体自动换行，保证全部项可达。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EncyclopediaFilterFlowRow(
    items: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    backdrop: UiBackdrop? = LocalUiBackdrop.current,
    tagPrefix: String = "encyclopedia-filter",
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { (key, label) ->
            val selected = key == selectedKey
            UiAction(
                label = label,
                leadingIcon = if (selected) UiIcons.Check else null,
                filled = selected,
                backdrop = backdrop,
                horizontalPadding = 12.dp,
                onClick = { if (!selected) onSelect(key) },
                actionId = "$tagPrefix.$key",
                modifier = Modifier
                    .semantics { semanticsSelected = selected }
                    .testTag("$tagPrefix-$key"),
            )
        }
    }
}

/**
 * 图谱组合工具栏：将放大、缩小、复位与全屏收入同一圆角玻璃容器，尺寸紧凑以免遮挡人体脚部插画。
 */
@Composable
internal fun AtlasGlassToolCluster(
    zoomPercent: Int,
    canZoomIn: Boolean,
    canZoomOut: Boolean,
    fullscreen: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onReset: () -> Unit,
    onToggleFullscreen: () -> Unit,
    backdrop: UiBackdrop?,
    onReportBoundsPx: (AtlasBox) -> Unit,
    modifier: Modifier = Modifier,
) {
    val outline = UiRoles.color(UiColorRole.OutlineVariant)
    val glassNone = LocalUiGlassQuality.current.desired == UiGlassQuality.None
    val shape = RoundedCornerShape(18.dp)
    UiPane(
        role = UiPaneRole.Regular,
        shape = shape,
        backdrop = backdrop,
        modifier = modifier
            .onGloballyPositioned { coords ->
                val b = coords.boundsInParent()
                onReportBoundsPx(AtlasBox(b.left, b.top, b.right, b.bottom))
            }
            .then(if (glassNone) Modifier.border(1.dp, outline, shape) else Modifier)
            .testTag("atlas-tool-cluster"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AtlasClusterIconButton(
                label = "放大图谱",
                shortText = "+",
                enabled = canZoomIn,
                onClick = onZoomIn,
                compactWidth = true,
                testTag = "atlas-zoom-in",
            )
            AtlasClusterIconButton(
                label = "缩小图谱",
                shortText = "−",
                enabled = canZoomOut,
                onClick = onZoomOut,
                compactWidth = true,
                testTag = "atlas-zoom-out",
            )
            AtlasClusterIconButton(
                label = "复位图谱（当前 $zoomPercent%）",
                shortText = "${zoomPercent}%",
                enabled = true,
                onClick = onReset,
                compactWidth = false,
                testTag = "atlas-zoom-reset",
            )
            AtlasClusterIconButton(
                label = if (fullscreen) "退出全屏图谱" else "全屏图谱",
                shortText = if (fullscreen) "退出" else "全屏",
                enabled = true,
                onClick = onToggleFullscreen,
                compactWidth = false,
                testTag = "atlas-fullscreen-toggle",
            )
        }
    }
}

@Composable
private fun AtlasClusterIconButton(
    label: String,
    shortText: String,
    enabled: Boolean,
    onClick: () -> Unit,
    compactWidth: Boolean,
    testTag: String,
) {
    Box(
        modifier = Modifier
            .sizeIn(minWidth = if (compactWidth) 30.dp else 38.dp, minHeight = 34.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compactWidth) 5.dp else 6.dp)
            .semantics {
                contentDescription = label
                if (!enabled) stateDescription = "已达边界"
            }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = shortText,
            style = UiRoles.text(MaterialTheme.typography.labelMedium).copy(fontSize = 12.sp),
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) UiRoles.foreground else UiRoles.color(UiColorRole.OnSurfaceVariant).copy(alpha = 0.45f),
            maxLines = 1,
        )
    }
}

/**
 * 图谱底部浮动快速预览卡：置于 Dock 之上或全屏底栏位置，避免遮挡中央人体图。
 */
@Composable
internal fun EncyclopediaPointPeekOverlay(
    pointName: String,
    pointCode: String,
    metaLine: String,
    hasRegion: Boolean,
    regionTitle: String?,
    inReview: Boolean,
    onClose: () -> Unit,
    onOpenDetail: () -> Unit,
    onOpenRegion: () -> Unit,
    onAddReview: (() -> Unit)?,
    backdrop: UiBackdrop?,
    modifier: Modifier = Modifier,
) {
    val outline = UiRoles.color(UiColorRole.OutlineVariant)
    UiPane(
        role = UiPaneRole.Regular,
        shape = RoundedCornerShape(22.dp),
        backdrop = backdrop,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, outline, RoundedCornerShape(22.dp))
            .testTag("encyclopedia-point-peek"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = pointName,
                        style = UiRoles.text(MaterialTheme.typography.titleMedium),
                        fontWeight = FontWeight.Bold,
                        color = UiRoles.foreground,
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = UiRoles.color(UiColorRole.PrimaryContainer),
                    ) {
                        Text(
                            text = pointCode,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = UiRoles.text(MaterialTheme.typography.labelMedium),
                            color = UiRoles.color(UiColorRole.OnPrimaryContainer),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                UiAction(
                    label = "关闭穴位预览",
                    icon = UiIcons.Close,
                    onClick = onClose,
                    inheritedPane = true,
                    actionId = "encyclopedia.peek.close",
                    modifier = Modifier.testTag("encyclopedia-peek-close"),
                )
            }
            if (metaLine.isNotBlank()) {
                Text(
                    text = metaLine,
                    style = UiRoles.text(MaterialTheme.typography.bodySmall),
                    color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasRegion && regionTitle != null) {
                    UiAction(
                        label = "查看详情",
                        onClick = onOpenDetail,
                        backdrop = null,
                        foreground = UiRoles.accent,
                        horizontalPadding = 12.dp,
                        actionId = "encyclopedia.peek.detail",
                        modifier = Modifier.testTag("encyclopedia-peek-detail"),
                    )
                    Spacer(Modifier.weight(1f))
                    UiAction(
                        label = "查看$regionTitle",
                        onClick = onOpenRegion,
                        filled = true,
                        backdrop = backdrop,
                        actionId = "encyclopedia.peek.region",
                        modifier = Modifier.testTag("encyclopedia-peek-region"),
                    )
                } else {
                    if (onAddReview != null) {
                        UiAction(
                            label = if (inReview) "已加入回顾" else "加入回顾",
                            onClick = onAddReview,
                            enabled = !inReview,
                            backdrop = null,
                            foreground = UiRoles.accent,
                            horizontalPadding = 12.dp,
                            actionId = "encyclopedia.peek.review",
                            modifier = Modifier.testTag("encyclopedia-peek-review"),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    UiAction(
                        label = "查看详情 →",
                        onClick = onOpenDetail,
                        filled = true,
                        backdrop = backdrop,
                        actionId = "encyclopedia.peek.detail",
                        modifier = Modifier.testTag("encyclopedia-peek-detail"),
                    )
                }
            }
        }
    }
}

/**
 * 详情与局部图共用底部单容器玻璃操作条。
 */
@Composable
internal fun EncyclopediaBottomBar(
    backdrop: UiBackdrop?,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val outline = UiRoles.color(UiColorRole.OutlineVariant)
    val pageBackground = UiRoles.surface(UiSurfaceRole.Page)
    val glassNone = LocalUiGlassQuality.current.desired == UiGlassQuality.None
    val scrimModifier = if (backdrop != null && !glassNone) {
        Modifier.background(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                0.0f to Color.Transparent,
                0.28f to pageBackground.copy(alpha = 0.58f),
                0.68f to pageBackground.copy(alpha = 0.88f),
                1.0f to pageBackground,
            ),
        )
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(scrimModifier)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        UiPane(
            role = UiPaneRole.Banner,
            shape = RoundedCornerShape(24.dp),
            backdrop = backdrop,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, outline, RoundedCornerShape(24.dp)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

/**
 * 十四经络选择弹层：复用针知原生 UiSheet（遮罩、圆角抽屉、下拉手势与焦点语义）。
 */
@Composable
internal fun MeridianPickerSheet(
    snapshot: EncyclopediaSnapshot,
    selectedMeridianCode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    UiSheet(
        title = "十四经络",
        onDismiss = onDismiss,
        stableDrag = true,
        dismissOnBlankTap = true,
        maxContentFraction = 0.72f,
        modifier = Modifier.testTag("encyclopedia-meridian-sheet"),
        actions = {
            UiAction(
                label = "关闭",
                onClick = onDismiss,
                backdrop = null,
                foreground = UiRoles.foreground,
                horizontalPadding = 18.dp,
                actionId = "encyclopedia.meridianSheet.close",
                modifier = Modifier.testTag("encyclopedia-sheet-close"),
            )
        },
    ) {
        val overviewSelected = selectedMeridianCode == ALL_MERIDIANS_CODE
        MeridianSheetCard(
            title = "全部十四经（总览）",
            subtitle = "${ALL_MERIDIANS_CODE.uppercase()} · 361 穴 · 当前面避让标注",
            selected = overviewSelected,
            onClick = { onSelect(ALL_MERIDIANS_CODE) },
            testTag = "meridian-option-$ALL_MERIDIANS_CODE",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))

        val fontScale = LocalDensity.current.fontScale
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val singleCol = maxWidth < 300.dp || fontScale >= 1.45f
            val cols = if (singleCol) 1 else 2
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                snapshot.meridians.chunked(cols).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowItems.forEach { meridian ->
                            val onMapCount = AtlasViewId.entries
                                .flatMap { view ->
                                    snapshot.atlas.projectionsByViewAndMeridian[view]?.get(meridian.code).orEmpty()
                                }
                                .map { it.pointCode }
                                .toSet()
                                .size
                            val note = if (onMapCount == meridian.pointCount) {
                                "${meridian.pointCount} 穴"
                            } else {
                                "$onMapCount 穴在图 · ${meridian.pointCount - onMapCount} 穴图外"
                            }
                            MeridianSheetCard(
                                title = meridian.name,
                                subtitle = "${meridian.code} · $note",
                                selected = selectedMeridianCode == meridian.code,
                                onClick = { onSelect(meridian.code) },
                                testTag = "meridian-option-${meridian.code}",
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowItems.size == 1 && cols == 2) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "选择经络后打开点位最集中的视角，可继续切换正面、背面和左侧面。",
            style = UiRoles.text(MaterialTheme.typography.bodySmall),
            color = UiRoles.color(UiColorRole.OnSurfaceVariant),
        )
    }
}

@Composable
private fun MeridianSheetCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val container = if (selected) UiRoles.color(UiColorRole.PrimaryContainer) else UiRoles.surface(UiSurfaceRole.Block)
    val contentColor = if (selected) UiRoles.color(UiColorRole.OnPrimaryContainer) else UiRoles.foreground
    val borderColor = if (selected) UiRoles.accent else UiRoles.color(UiColorRole.OutlineVariant)
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 56.dp)
            .semantics { semanticsSelected = selected }
            .testTag(testTag),
        shape = RoundedCornerShape(14.dp),
        color = container,
        contentColor = contentColor,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = UiRoles.text(MaterialTheme.typography.titleSmall),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = UiRoles.text(MaterialTheme.typography.bodySmall),
                color = if (selected) contentColor.copy(alpha = 0.85f) else UiRoles.color(UiColorRole.OnSurfaceVariant),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal data class SourceSheetSection(
    val heading: String,
    val references: List<EncyclopediaSourceRef>,
    val note: String? = null,
)

/**
 * 资料来源弹层：复用 UiSheet 展示国标、课件、逐穴与关联出处。
 */
@Composable
internal fun SourceReferencesSheet(
    title: String = "资料来源",
    sections: List<SourceSheetSection>,
    onOpenReference: (EncyclopediaSourceRef) -> Unit,
    onDismiss: () -> Unit,
) {
    UiSheet(
        title = title,
        onDismiss = onDismiss,
        stableDrag = true,
        dismissOnBlankTap = true,
        maxContentFraction = 0.70f,
        modifier = Modifier.testTag("encyclopedia-source-sheet"),
        actions = {
            UiAction(
                label = "关闭",
                onClick = onDismiss,
                backdrop = null,
                foreground = UiRoles.foreground,
                horizontalPadding = 18.dp,
                actionId = "encyclopedia.sourceSheet.close",
                modifier = Modifier.testTag("encyclopedia-sheet-close"),
            )
        },
    ) {
        sections.forEachIndexed { idx, section ->
            if (idx > 0) Spacer(Modifier.height(12.dp))
            Text(
                text = section.heading,
                style = UiRoles.text(MaterialTheme.typography.titleSmall),
                fontWeight = FontWeight.SemiBold,
                color = UiRoles.foreground,
            )
            Spacer(Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = UiRoles.surface(UiSurfaceRole.Card),
                border = BorderStroke(1.dp, UiRoles.color(UiColorRole.OutlineVariant)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    section.references.forEachIndexed { refIdx, ref ->
                        if (refIdx > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 14.dp),
                                thickness = 0.5.dp,
                                color = UiRoles.color(UiColorRole.OutlineVariant),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .clickable(role = Role.Button, onClick = { onOpenReference(ref) })
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                                .testTag("source-sheet-link-$idx-$refIdx"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(
                                    text = ref.title,
                                    style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                                    color = UiRoles.accent,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = ref.url,
                                    style = UiRoles.text(MaterialTheme.typography.bodySmall),
                                    color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(
                                UiIcons.Chevron,
                                contentDescription = "打开来源",
                                modifier = Modifier.size(18.dp),
                                tint = UiRoles.accent,
                            )
                        }
                    }
                    section.note?.takeIf(String::isNotBlank)?.let { note ->
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            thickness = 0.5.dp,
                            color = UiRoles.color(UiColorRole.OutlineVariant),
                        )
                        Text(
                            text = note,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            style = UiRoles.text(MaterialTheme.typography.bodySmall),
                            color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
