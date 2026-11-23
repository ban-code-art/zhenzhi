package com.example.myapplication.ui.encyclopedia.atlas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.encyclopedia.AtlasViewId
import com.example.myapplication.data.encyclopedia.EncyclopediaSnapshot
import com.example.myapplication.data.encyclopedia.EncyclopediaSourceRef
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiBackdrop
import com.example.myapplication.ui.components.UiGlassBackdrop
import com.example.myapplication.ui.components.UiGlassQuality
import com.example.myapplication.ui.components.LocalUiGlassQuality
import com.example.myapplication.ui.components.UiPane
import com.example.myapplication.ui.components.UiPaneRole
import com.example.myapplication.ui.components.rememberUiBackdrop
import com.example.myapplication.ui.encyclopedia.AcupointFilterOption
import com.example.myapplication.ui.encyclopedia.EncyclopediaPageFrame
import com.example.myapplication.ui.encyclopedia.EncyclopediaSegmentedControl
import com.example.myapplication.ui.encyclopedia.AtlasGlassToolCluster
import com.example.myapplication.ui.encyclopedia.SegmentedTabOption
import com.example.myapplication.ui.theme.UiColorRole
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

/**
 * 全身经络图谱主区域：包含当前经络头、人体交互画布（正面/背面/左侧面）、图外穴位入口、腕手快捷入口与所属腧穴摘要。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AtlasMainSection(
    snapshot: EncyclopediaSnapshot,
    frame: EncyclopediaPageFrame.Root,
    backdrop: UiBackdrop?,
    onOpenMeridianPicker: () -> Unit,
    onSelectView: (AtlasViewId) -> Unit,
    onCameraChange: (AtlasCameraState) -> Unit,
    onZoomStep: (Boolean) -> Unit,
    onResetCamera: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onTapPoint: (String) -> Unit,
    onOpenPointDetail: (String) -> Unit,
    onOpenRegion: (String) -> Unit,
    onOpenFilteredPoints: (AcupointFilterOption) -> Unit,
    onOpenTextbookUrl: (EncyclopediaSourceRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isOverview = frame.meridianCode == ALL_MERIDIANS_CODE
    val meridian = snapshot.meridiansByCode[frame.meridianCode]
    val subtitle = if (isOverview) {
        "十四经 · ${snapshot.points.size} 穴"
    } else if (meridian != null) {
        "${meridian.code} · ${meridian.pointCount} 穴 · ${meridian.direction}"
    } else ""

    Column(
        modifier = modifier.fillMaxWidth().testTag("atlas-main-section"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 经络名称与切换经络胶囊
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = meridian?.name ?: "全身经络",
                    style = UiRoles.text(MaterialTheme.typography.titleLarge),
                    fontWeight = FontWeight.Bold,
                    color = UiRoles.foreground,
                    modifier = Modifier.semantics { heading() }.testTag("atlas-meridian-title"),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = UiRoles.text(MaterialTheme.typography.bodySmall),
                        color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            UiAction(
                label = "切换经络",
                trailingIcon = UiIcons.ChevronDown,
                onClick = onOpenMeridianPicker,
                backdrop = backdrop,
                horizontalPadding = 12.dp,
                actionId = "atlas.meridianPicker",
                modifier = Modifier.testTag("atlas-switch-meridian"),
            )
        }

        // 人体交互图谱卡片
        AtlasInteractiveCanvasCard(
            snapshot = snapshot,
            meridianCode = frame.meridianCode,
            viewId = frame.viewId,
            camera = frame.bodyCamera,
            selectedPointCode = frame.peekPointCode,
            fullscreen = false,
            backdrop = backdrop,
            onSelectView = onSelectView,
            onCameraChange = onCameraChange,
            onZoomStep = onZoomStep,
            onResetCamera = onResetCamera,
            onToggleFullscreen = onToggleFullscreen,
            onTapPoint = onTapPoint,
            onOpenPointDetail = onOpenPointDetail,
            modifier = Modifier.fillMaxWidth().height(498.dp),
        )

        // 手太阴肺经腕手局部快速入口
        if (frame.meridianCode == "LU") {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = UiRoles.surface(UiSurfaceRole.Card),
                border = BorderStroke(1.dp, UiRoles.color(UiColorRole.OutlineVariant)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "腕手局部图快捷入口",
                        style = UiRoles.text(MaterialTheme.typography.labelLarge),
                        fontWeight = FontWeight.SemiBold,
                        color = UiRoles.foreground,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        snapshot.atlas.lungFront.wristPointCodes.forEach { code ->
                            val p = snapshot.pointsByCode[code] ?: return@forEach
                            UiAction(
                                label = p.name,
                                onClick = { onOpenRegion(code) },
                                horizontalPadding = 12.dp,
                                backdrop = backdrop,
                                actionId = "atlas.wrist.$code",
                                modifier = Modifier.testTag("atlas-wrist-$code"),
                            )
                        }
                    }
                }
            }
        }

        // 所属腧穴与三视图分布摘要卡
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = UiRoles.surface(UiSurfaceRole.Card),
            border = BorderStroke(1.dp, UiRoles.color(UiColorRole.OutlineVariant)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "所属腧穴",
                        style = UiRoles.text(MaterialTheme.typography.titleMedium),
                        fontWeight = FontWeight.SemiBold,
                        color = UiRoles.foreground,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        UiAction(
                            label = "本面穴位",
                            onClick = { onOpenFilteredPoints(AcupointFilterOption.CURRENT_VIEW) },
                            backdrop = backdrop,
                            foreground = UiRoles.accent,
                            horizontalPadding = 12.dp,
                            actionId = "atlas.openViewPoints",
                            modifier = Modifier.testTag("atlas-open-view-points"),
                        )
                        UiAction(
                            label = if (isOverview) "查看全部 →" else "查看全经 →",
                            onClick = {
                                onOpenFilteredPoints(
                                    if (isOverview) AcupointFilterOption.ALL else AcupointFilterOption.CURRENT_MERIDIAN,
                                )
                            },
                            backdrop = backdrop,
                            foreground = UiRoles.accent,
                            horizontalPadding = 12.dp,
                            actionId = "atlas.openMeridianPoints",
                            modifier = Modifier.testTag("atlas-open-meridian-points"),
                        )
                    }
                }

                // 三视图数量分布与快切
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AtlasViewId.entries.forEach { v ->
                        val viewMeta = snapshot.atlasViewById[v]
                        val count = if (isOverview) {
                            snapshot.projectionsByView[v]?.size ?: 0
                        } else {
                            snapshot.atlas.projectionsByViewAndMeridian[v]?.get(frame.meridianCode)?.size ?: 0
                        }
                        if (isOverview || count > 0) {
                            val selected = frame.viewId == v
                            UiAction(
                                label = "${viewMeta?.name ?: v.wireKey} $count",
                                filled = selected,
                                horizontalPadding = 12.dp,
                                backdrop = backdrop,
                                onClick = { if (!selected) onSelectView(v) },
                                actionId = "atlas.dist.${v.wireKey}",
                                modifier = Modifier.testTag("atlas-dist-${v.wireKey}"),
                            )
                        }
                    }
                }

                // 未在当前视图呈现的图外部位提示
                val otherOccluded = snapshot.occludedByView.entries
                    .filter { (v, _) -> v != frame.viewId }
                    .flatMap { (_, list) -> list }
                    .filter { isOverview || snapshot.pointsByCode[it.pointCode]?.meridianCode == frame.meridianCode }
                if (otherOccluded.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "图外部位：",
                            style = UiRoles.text(MaterialTheme.typography.bodySmall),
                            color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                        otherOccluded.forEach { occ ->
                            val pt = snapshot.pointsByCode[occ.pointCode] ?: return@forEach
                            Text(
                                text = "${pt.name} · ${occ.regionLabel}",
                                style = UiRoles.text(MaterialTheme.typography.bodySmall),
                                fontWeight = FontWeight.Medium,
                                color = UiRoles.accent,
                                modifier = Modifier
                                    .clickable(role = Role.Button) { onSelectView(occ.view) }
                                    .padding(horizontal = 4.dp, vertical = 8.dp)
                                    .testTag("atlas-occluded-switch-${occ.pointCode}"),
                            )
                        }
                    }
                }

                if (frame.meridianCode == "LU" && frame.viewId == AtlasViewId.FRONT) {
                    Text(
                        text = "实线为体表主线，从中府沿上臂内侧前缘过肘，经前臂桡侧、腕、鱼际至少商；虚线为腕后通向食指支脉。",
                        style = UiRoles.text(MaterialTheme.typography.bodySmall),
                        color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                    )
                    Text(
                        text = "查看《针灸学》课件循行说明 ↗",
                        style = UiRoles.text(MaterialTheme.typography.bodySmall),
                        fontWeight = FontWeight.Medium,
                        color = UiRoles.accent,
                        modifier = Modifier
                            .clickable(role = Role.Button) {
                                onOpenTextbookUrl(
                                    EncyclopediaSourceRef(
                                        title = snapshot.manifest.upstreamAttribution.courseware.title,
                                        url = snapshot.atlas.lungFront.textbookUrl,
                                    ),
                                )
                            }
                            .padding(vertical = 8.dp)
                            .testTag("atlas-lung-textbook-link"),
                    )
                } else {
                    Text(
                        text = if (isOverview) {
                            "双指或工具栏放大可逐步显示密集穴位；也可从「本面穴位」或上方搜索直达。"
                        } else {
                            "图中标注本经当前体位重点穴位，点击穴位查看局部图与详情，其余穴位可从「本面穴位」浏览。"
                        },
                        style = UiRoles.text(MaterialTheme.typography.bodySmall),
                        color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                    )
                }
            }
        }
    }
}

/**
 * 全屏图谱覆盖视图：保留三视图分段、图外入口、缩放/复位/退出全屏工具组及重叠点位候选项。
 */
@Composable
internal fun AtlasFullscreenView(
    snapshot: EncyclopediaSnapshot,
    frame: EncyclopediaPageFrame.Root,
    backdrop: UiBackdrop?,
    onSelectView: (AtlasViewId) -> Unit,
    onCameraChange: (AtlasCameraState) -> Unit,
    onZoomStep: (Boolean) -> Unit,
    onResetCamera: () -> Unit,
    onExitFullscreen: () -> Unit,
    onTapPoint: (String) -> Unit,
    onOpenPointDetail: (String) -> Unit,
) {
    val meridian = snapshot.meridiansByCode[frame.meridianCode]
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(UiRoles.surface(UiSurfaceRole.Page))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("atlas-fullscreen-view"),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    UiAction(
                        label = "退出全屏图谱",
                        icon = UiIcons.Back,
                        onClick = onExitFullscreen,
                        backdrop = backdrop,
                        actionId = "atlas.fullscreen.back",
                        modifier = Modifier.testTag("atlas-fullscreen-back"),
                    )
                    Text(
                        text = meridian?.name ?: "全身经络图谱",
                        style = UiRoles.text(UiRoles.pageTitle),
                        color = UiRoles.foreground,
                    )
                }
                Text(
                    text = "${frame.bodyCamera.zoomPercent}%",
                    style = UiRoles.text(MaterialTheme.typography.labelLarge),
                    color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                )
            }

            AtlasInteractiveCanvasCard(
                snapshot = snapshot,
                meridianCode = frame.meridianCode,
                viewId = frame.viewId,
                camera = frame.bodyCamera,
                selectedPointCode = frame.peekPointCode,
                fullscreen = true,
                backdrop = backdrop,
                onSelectView = onSelectView,
                onCameraChange = onCameraChange,
                onZoomStep = onZoomStep,
                onResetCamera = onResetCamera,
                onToggleFullscreen = onExitFullscreen,
                onTapPoint = onTapPoint,
                onOpenPointDetail = onOpenPointDetail,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AtlasInteractiveCanvasCard(
    snapshot: EncyclopediaSnapshot,
    meridianCode: String,
    viewId: AtlasViewId,
    camera: AtlasCameraState,
    selectedPointCode: String?,
    fullscreen: Boolean,
    backdrop: UiBackdrop?,
    onSelectView: (AtlasViewId) -> Unit,
    onCameraChange: (AtlasCameraState) -> Unit,
    onZoomStep: (Boolean) -> Unit,
    onResetCamera: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onTapPoint: (String) -> Unit,
    onOpenPointDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val outline = UiRoles.color(UiColorRole.OutlineVariant)
    val density = LocalDensity.current
    var toolsBoxPx by remember { mutableStateOf<AtlasBox?>(null) }
    var footerBoxPx by remember { mutableStateOf<AtlasBox?>(null) }
    var disambiguateCandidates by remember(meridianCode, viewId, camera) {
        mutableStateOf<List<PlacedAtlasMarker>>(emptyList())
    }

    val viewInfo = snapshot.atlasViewById[viewId] ?: snapshot.atlas.views.first()
    val isOverview = meridianCode == ALL_MERIDIANS_CODE
    val occludedHere = remember(snapshot, viewId, meridianCode) {
        snapshot.occludedByView[viewId].orEmpty().filter { occ ->
            isOverview || snapshot.pointsByCode[occ.pointCode]?.meridianCode == meridianCode
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = UiRoles.surface(UiSurfaceRole.Card),
        border = BorderStroke(1.dp, outline),
    ) {
        Column(Modifier.fillMaxSize()) {
            // 顶部控制条：三视图分段与当前体位遮挡穴位入口置于底图上方，避免遮挡人体头部
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EncyclopediaSegmentedControl(
                    options = AtlasViewId.entries.map { v ->
                        SegmentedTabOption(
                            value = v,
                            label = snapshot.atlasViewById[v]?.name ?: v.wireKey,
                            testTag = "atlas-view-${v.wireKey}",
                        )
                    },
                    selected = viewId,
                    onSelect = onSelectView,
                    backdrop = null,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (occludedHere.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        occludedHere.forEach { occ ->
                            val pt = snapshot.pointsByCode[occ.pointCode] ?: return@forEach
                            UiAction(
                                label = "${pt.name} · ${occ.regionLabel}",
                                onClick = { onOpenPointDetail(occ.pointCode) },
                                horizontalPadding = 10.dp,
                                backdrop = null,
                                actionId = "atlas.occluded.${occ.pointCode}",
                                modifier = Modifier.testTag("atlas-occluded-${occ.pointCode}"),
                            )
                        }
                    }
                }
            }

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val widthDp = maxWidth.value
                val heightDp = maxHeight.value
                val densityPx = density.density.coerceAtLeast(1f)
                val occupiedDp = remember(toolsBoxPx, footerBoxPx, densityPx) {
                    listOfNotNull(toolsBoxPx, footerBoxPx).map { box ->
                        AtlasBox(
                            left = box.left / densityPx,
                            top = box.top / densityPx,
                            right = box.right / densityPx,
                            bottom = box.bottom / densityPx,
                        )
                    }
                }
                val scene = remember(snapshot, meridianCode, viewId, camera, widthDp, heightDp, selectedPointCode, occupiedDp) {
                    AtlasLayoutEngine.layout(
                        snapshot = snapshot,
                        meridianCode = meridianCode,
                        viewId = viewId,
                        camera = camera,
                        viewportWidthDp = widthDp,
                        viewportHeightDp = heightDp,
                        selectedPointCode = selectedPointCode,
                        overlayOccupiedDp = occupiedDp,
                    )
                }
                val mapBackdrop = rememberUiBackdrop()

                UiGlassBackdrop(mapBackdrop, Modifier.fillMaxSize()) {
                    FullBodyAtlasCanvas(
                        snapshot = snapshot,
                        scene = scene,
                        artworkPath = viewInfo.artwork,
                        camera = camera,
                        onCameraChange = onCameraChange,
                        onTapPoint = { primaryCode, nearby ->
                            if (isOverview && nearby.size > 1) {
                                disambiguateCandidates = nearby.take(5)
                            } else {
                                disambiguateCandidates = emptyList()
                                onTapPoint(primaryCode)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // 密集区域命中重叠候选列表
                if (disambiguateCandidates.isNotEmpty()) {
                    val glassNone = LocalUiGlassQuality.current.desired == UiGlassQuality.None
                    UiPane(
                        role = UiPaneRole.Menu,
                        shape = RoundedCornerShape(18.dp),
                        backdrop = mapBackdrop,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 24.dp)
                            .then(if (glassNone) Modifier.border(1.dp, outline, RoundedCornerShape(18.dp)) else Modifier)
                            .testTag("atlas-dense-candidates"),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "附近穴位",
                                    style = UiRoles.text(MaterialTheme.typography.titleSmall),
                                    fontWeight = FontWeight.SemiBold,
                                    color = UiRoles.foreground,
                                )
                                Icon(
                                    UiIcons.Close,
                                    contentDescription = "关闭候选",
                                    tint = UiRoles.color(UiColorRole.OnSurfaceVariant),
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clickable { disambiguateCandidates = emptyList() },
                                )
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                disambiguateCandidates.forEach { candidate ->
                                    UiAction(
                                        label = "${candidate.pointName} ${candidate.pointCode}",
                                        onClick = {
                                            disambiguateCandidates = emptyList()
                                            onTapPoint(candidate.pointCode)
                                        },
                                        horizontalPadding = 10.dp,
                                        backdrop = mapBackdrop,
                                        actionId = "atlas.candidate.${candidate.pointCode}",
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 底部控制条：置于底图下方，保持足部插画和底层标注完全清晰无遮挡
            val statusText = when {
                isOverview -> "本面 ${snapshot.projectionsByView[viewId]?.size ?: 0} 穴 · 放大看详"
                (snapshot.atlas.projectionsByViewAndMeridian[viewId]?.get(meridianCode)?.size ?: 0) > 0 ->
                    "本面 ${snapshot.atlas.projectionsByViewAndMeridian[viewId]?.get(meridianCode)?.size ?: 0} 穴 · 重点标"
                else -> "本经此视角无体表标点"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                UiPane(
                    role = UiPaneRole.Banner,
                    shape = RoundedCornerShape(14.dp),
                    backdrop = null,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(end = 8.dp),
                ) {
                    Text(
                        text = statusText,
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("atlas-footer-status"),
                        style = UiRoles.text(MaterialTheme.typography.labelSmall),
                        color = UiRoles.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                AtlasGlassToolCluster(
                    zoomPercent = camera.zoomPercent,
                    canZoomIn = camera.zoom < AtlasFrames.MAX_ZOOM - 0.01f,
                    canZoomOut = camera.zoom > AtlasFrames.MIN_ZOOM + 0.01f,
                    fullscreen = fullscreen,
                    onZoomIn = { onZoomStep(true) },
                    onZoomOut = { onZoomStep(false) },
                    onReset = onResetCamera,
                    onToggleFullscreen = onToggleFullscreen,
                    backdrop = null,
                    onReportBoundsPx = { toolsBoxPx = null; footerBoxPx = null },
                )
            }
        }
    }
}
