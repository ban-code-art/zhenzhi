package com.example.myapplication.ui.encyclopedia.atlas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected as semanticsSelected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.encyclopedia.EncyclopediaSnapshot
import com.example.myapplication.data.encyclopedia.EncyclopediaSourceRef
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiActionGroup
import com.example.myapplication.ui.components.UiGlassBackdrop
import com.example.myapplication.ui.components.UiPane
import com.example.myapplication.ui.components.UiPaneRole
import com.example.myapplication.ui.components.rememberUiBackdrop
import com.example.myapplication.ui.encyclopedia.EncyclopediaBottomBar
import com.example.myapplication.ui.encyclopedia.EncyclopediaPageFrame
import com.example.myapplication.ui.knowledge.course.CourseScreenHeader
import com.example.myapplication.ui.theme.UiColorRole
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

/**
 * 穴位整组局部图页面：显示解剖引导线、同图穴位群组切换（保留相机视角）、国标依据及底部「返回 / 查看穴位详情」操作条。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalAtlasScreen(
    snapshot: EncyclopediaSnapshot,
    frame: EncyclopediaPageFrame.Region,
    onSelectPoint: (String) -> Unit,
    onCameraChange: (AtlasCameraState) -> Unit,
    onZoomStep: (Boolean) -> Unit,
    onResetCamera: () -> Unit,
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenStandardSource: (EncyclopediaSourceRef) -> Unit,
    onScrollChanged: (Int) -> Unit,
) {
    val activeCode = frame.selectedPointCode
    val point = snapshot.pointsByCode[activeCode] ?: snapshot.pointsByCode[frame.pointCode] ?: return
    val region = snapshot.regionByPointCode[point.code] ?: snapshot.regionByPointCode[frame.pointCode] ?: return
    val meridian = snapshot.meridiansByCode[point.meridianCode]
    val groupCodes = region.points.ifEmpty { listOf(region.pointCode) }
    val scroll = rememberScrollState(initial = frame.scrollOffset)
    LaunchedEffect(scroll.value) { onScrollChanged(scroll.value) }

    val backdrop = rememberUiBackdrop()
    val outline = UiRoles.color(UiColorRole.OutlineVariant)
    val clauseRef = if (point.code == "LU5") "§3.5.9、§${point.clause}" else "§${point.clause}"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("local-atlas-screen"),
    ) {
        UiGlassBackdrop(backdrop, Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(start = 16.dp, end = 16.dp, top = 84.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = meridian?.name ?: point.meridianCode,
                        style = UiRoles.text(MaterialTheme.typography.labelLarge),
                        color = UiRoles.accent,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "${point.name} ${point.code}",
                        style = UiRoles.text(MaterialTheme.typography.headlineSmall),
                        fontWeight = FontWeight.Bold,
                        color = UiRoles.foreground,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = region.pose,
                        style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                        color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                    )
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = UiRoles.surface(UiSurfaceRole.Card),
                    border = BorderStroke(1.dp, outline),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Box(Modifier.fillMaxWidth().height(232.dp)) {
                            LocalRegionAtlasCanvas(
                                snapshot = snapshot,
                                region = region,
                                selectedPointCode = point.code,
                                selectedActive = frame.selectedInRegion,
                                camera = frame.camera,
                                onCameraChange = onCameraChange,
                                onSelectPoint = onSelectPoint,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = outline.copy(alpha = 0.45f),
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            UiPane(
                                role = UiPaneRole.Banner,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f, fill = false),
                            ) {
                                Text(
                                    text = "GB/T 12346-2021 · ${frame.camera.zoomPercent}%",
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    style = UiRoles.text(MaterialTheme.typography.labelMedium),
                                    color = UiRoles.foreground,
                                )
                            }

                            UiActionGroup {
                                UiAction(
                                    label = "+",
                                    onClick = { onZoomStep(true) },
                                    enabled = frame.camera.zoom < AtlasFrames.MAX_ZOOM - 0.01f,
                                    horizontalPadding = 10.dp,
                                    actionId = "local.zoom.in",
                                    modifier = Modifier
                                        .semantics { contentDescription = "放大局部图" }
                                        .testTag("region-zoom-in"),
                                )
                                UiAction(
                                    label = "−",
                                    onClick = { onZoomStep(false) },
                                    enabled = frame.camera.zoom > AtlasFrames.MIN_ZOOM + 0.01f,
                                    horizontalPadding = 10.dp,
                                    actionId = "local.zoom.out",
                                    modifier = Modifier
                                        .semantics { contentDescription = "缩小局部图" }
                                        .testTag("region-zoom-out"),
                                )
                                UiAction(
                                    label = "复位",
                                    onClick = onResetCamera,
                                    horizontalPadding = 10.dp,
                                    actionId = "local.zoom.reset",
                                    modifier = Modifier.testTag("region-zoom-reset"),
                                )
                            }
                        }
                    }
                }

                if (groupCodes.size > 1) {
                    Text(
                        text = "本图穴位（共 ${groupCodes.size} 穴，点击切换）",
                        style = UiRoles.text(MaterialTheme.typography.labelLarge),
                        fontWeight = FontWeight.SemiBold,
                        color = UiRoles.foreground,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        groupCodes.forEach { code ->
                            val candidate = snapshot.pointsByCode[code] ?: return@forEach
                            val selected = frame.selectedInRegion && code == point.code
                            UiAction(
                                label = "${candidate.name} $code",
                                leadingIcon = if (selected) UiIcons.Check else null,
                                filled = selected,
                                horizontalPadding = 12.dp,
                                backdrop = null,
                                onClick = { onSelectPoint(code) },
                                actionId = "region.select.$code",
                                modifier = Modifier
                                    .semantics { semanticsSelected = selected }
                                    .testTag("region-chip-$code"),
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = UiRoles.surface(UiSurfaceRole.Card),
                    border = BorderStroke(1.dp, outline),
                    modifier = Modifier.fillMaxWidth().testTag("region-caption"),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (frame.selectedInRegion) "已选中 ${point.name} ${point.code}"
                            else region.focus ?: "${point.name}体表解剖对照",
                            style = UiRoles.text(MaterialTheme.typography.titleMedium),
                            fontWeight = FontWeight.SemiBold,
                            color = UiRoles.foreground,
                        )
                        Text(
                            text = if (frame.selectedInRegion) point.location else region.hint ?: point.location,
                            style = UiRoles.text(MaterialTheme.typography.bodyLarge),
                            color = UiRoles.foreground,
                        )
                        if (frame.selectedInRegion && point.code != "LU5" && !region.hint.isNullOrBlank()) {
                            Text(
                                text = region.hint,
                                style = UiRoles.text(MaterialTheme.typography.bodySmall),
                                color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                            )
                        }
                        Text(
                            text = "国标依据 $clauseRef（第 ${point.pdfPage} 页）↗",
                            style = UiRoles.actionText,
                            color = UiRoles.accent,
                            modifier = Modifier
                                .clickable(role = Role.Button) {
                                    onOpenStandardSource(
                                        EncyclopediaSourceRef(
                                            title = "GB/T 12346-2021 $clauseRef",
                                            url = "${snapshot.learning.references.standardPdfUrl}#page=${point.pdfPage}",
                                        ),
                                    )
                                }
                                .padding(vertical = 8.dp)
                                .testTag("region-standard-link"),
                        )
                    }
                }
            }
        }

        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            CourseScreenHeader(
                title = region.title,
                onBack = onBack,
                backTag = "local-atlas-back",
                backdrop = backdrop,
            )
        }

        EncyclopediaBottomBar(
            backdrop = backdrop,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            UiAction(
                label = "返回",
                onClick = onBack,
                foreground = UiRoles.accent,
                horizontalPadding = 16.dp,
                actionId = "local.bottom.back",
                modifier = Modifier.testTag("local-bottom-back"),
            )
            Spacer(Modifier.weight(1f))
            UiAction(
                label = "查看穴位详情",
                onClick = { onOpenDetail(point.code) },
                filled = true,
                backdrop = backdrop,
                horizontalPadding = 16.dp,
                actionId = "local.bottom.detail",
                modifier = Modifier.testTag("local-bottom-detail"),
            )
        }
    }
}
