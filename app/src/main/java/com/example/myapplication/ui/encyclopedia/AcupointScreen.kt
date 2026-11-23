package com.example.myapplication.ui.encyclopedia

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.encyclopedia.Acupoint
import com.example.myapplication.data.encyclopedia.AtlasViewId
import com.example.myapplication.data.encyclopedia.EncyclopediaSnapshot
import com.example.myapplication.data.encyclopedia.EncyclopediaSourceRef
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiBackdrop
import com.example.myapplication.ui.components.UiGlassBackdrop
import com.example.myapplication.ui.components.rememberUiBackdrop
import com.example.myapplication.ui.encyclopedia.atlas.ALL_MERIDIANS_CODE
import com.example.myapplication.ui.encyclopedia.atlas.AcupointSinglePreviewCanvas
import com.example.myapplication.ui.knowledge.course.CourseScreenHeader
import com.example.myapplication.ui.navigation.LocalNavigationPageActive
import com.example.myapplication.ui.theme.UiColorRole
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 穴位索引列表页（361 穴 · 多维筛选与大字体换行兼容）。
 */
@Composable
internal fun PointIndexSection(
    snapshot: EncyclopediaSnapshot,
    frame: EncyclopediaPageFrame.Root,
    listState: LazyListState,
    contentPadding: PaddingValues,
    favoriteCodes: Set<String>,
    favoritesEnabled: Boolean,
    backdrop: UiBackdrop?,
    onSelectFilter: (AcupointFilterOption) -> Unit,
    onOpenPointDetail: (String) -> Unit,
) {
    val filters = remember(frame.meridianCode, favoritesEnabled) {
        EncyclopediaSearch.availablePointFilters(frame.meridianCode, favoritesEnabled)
    }
    val activeFilter = if (frame.pointFilter in filters) frame.pointFilter else AcupointFilterOption.ALL
    val filteredPoints = remember(snapshot, activeFilter, frame.meridianCode, frame.viewId, favoriteCodes) {
        EncyclopediaSearch.filterPoints(
            snapshot = snapshot,
            filter = activeFilter,
            meridianCode = frame.meridianCode,
            viewId = frame.viewId,
            favoriteCodes = favoriteCodes,
        )
    }

    val cardColor = UiRoles.surface(UiSurfaceRole.Card)
    val outlineColor = UiRoles.color(UiColorRole.OutlineVariant)
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize().testTag("point-index-section"),
    ) {
        item(key = "point-index-header", contentType = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "穴位索引",
                        style = UiRoles.text(MaterialTheme.typography.titleLarge),
                        fontWeight = FontWeight.Bold,
                        color = UiRoles.foreground,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = "国标十四经 ${snapshot.points.size} 穴 · 当前 ${filteredPoints.size} 项",
                        style = UiRoles.text(MaterialTheme.typography.bodySmall),
                        color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                    )
                }
                EncyclopediaFilterFlowRow(
                    items = filters.map { it.name to it.label },
                    selectedKey = activeFilter.name,
                    onSelect = { key ->
                        AcupointFilterOption.entries.firstOrNull { it.name == key }?.let(onSelectFilter)
                    },
                    backdrop = backdrop,
                    tagPrefix = "point-filter",
                )
                Spacer(Modifier.height(0.dp))
            }
        }

        if (filteredPoints.isEmpty()) {
            item(key = "point-index-empty", contentType = "empty") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = cardColor,
                    border = BorderStroke(1.dp, outlineColor),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (activeFilter == AcupointFilterOption.FAVORITES) {
                            "还没有收藏任何穴位，可在穴位详情右上角点亮星标收藏。"
                        } else {
                            "这个筛选条件下暂无匹配穴位。"
                        },
                        modifier = Modifier.padding(18.dp),
                        style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                        color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                    )
                }
            }
        } else {
            itemsIndexed(filteredPoints, key = { _, point -> point.code }, contentType = { _, _ -> "point" }) { index, point ->
                val first = index == 0
                val last = index == filteredPoints.lastIndex
                val topRadius = if (first) 18.dp else 0.dp
                val bottomRadius = if (last) 18.dp else 0.dp
                val shape = RoundedCornerShape(topRadius, topRadius, bottomRadius, bottomRadius)
                Column(
                    Modifier.fillMaxWidth().clip(shape).background(cardColor).drawWithCache {
                        val width = 1.dp.toPx()
                        val outline = shape.createOutline(size, layoutDirection, this)
                        onDrawWithContent {
                            drawContent()
                            clipRect(top = if (first) 0f else width, bottom = if (last) size.height else size.height - width) {
                                drawOutline(outline, outlineColor, style = Stroke(width * 2f))
                            }
                        }
                    },
                ) {
                    if (!first) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 74.dp, end = 14.dp),
                            thickness = 0.5.dp,
                            color = outlineColor,
                        )
                    }
                    PointIndexRow(
                        point = point,
                        meridianShort = snapshot.meridiansByCode[point.meridianCode]?.shortName ?: point.meridianCode,
                        occludedRegion = snapshot.occludedByPointCode[point.code]?.regionLabel,
                        favorite = point.code in favoriteCodes,
                        onClick = { onOpenPointDetail(point.code) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PointIndexRow(
    point: Acupoint,
    meridianShort: String,
    occludedRegion: String?,
    favorite: Boolean,
    onClick: () -> Unit,
) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("point-index-row-${point.code}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!largeText) {
            Box(
                modifier = Modifier
                    .width(52.dp)
                    .heightIn(min = 38.dp)
                    .background(UiRoles.color(UiColorRole.PrimaryContainer), RoundedCornerShape(10.dp))
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = point.code,
                    style = UiRoles.text(MaterialTheme.typography.labelLarge),
                    fontWeight = FontWeight.Bold,
                    color = UiRoles.color(UiColorRole.OnPrimaryContainer),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (largeText) "${point.name} · ${point.code}" else point.name,
                    style = UiRoles.text(MaterialTheme.typography.titleMedium),
                    fontWeight = FontWeight.SemiBold,
                    color = UiRoles.foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (favorite) {
                    Icon(
                        UiIcons.FavoriteFilled,
                        contentDescription = "已收藏",
                        tint = UiRoles.accent,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            val sub = buildString {
                append(meridianShort)
                val reg = occludedRegion ?: point.region.takeIf(String::isNotBlank)
                if (!reg.isNullOrBlank()) append(" · $reg")
                if (point.special.isNotBlank()) append(" · ${point.special}")
            }
            Text(
                text = sub,
                style = UiRoles.text(MaterialTheme.typography.bodySmall),
                color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            UiIcons.Chevron,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = UiRoles.color(UiColorRole.OnSurfaceVariant),
        )
    }
}

/**
 * 穴位详情页：保留页头返回+收藏、单点局部预览+蓝字进整组局部图、四分段页签（定位/经属/主治/来源）、ST17「教材说明」、底部双操作条。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AcupointDetailScreen(
    snapshot: EncyclopediaSnapshot,
    frame: EncyclopediaPageFrame.PointDetail,
    isFavorite: Boolean,
    onToggleFavorite: (String) -> Unit,
    canToggleFavorite: Boolean,
    onSelectDetailTab: (AcupointDetailTab) -> Unit,
    onOpenRegion: (String) -> Unit,
    onLocateOnAtlas: (String) -> Unit,
    onOpenMeridianPoints: (String) -> Unit,
    onOpenSymptom: (String) -> Unit,
    onOpenSourceSheet: () -> Unit,
    onOpenSourceRef: (EncyclopediaSourceRef) -> Unit,
    onAddReview: ((String) -> Unit)?,
    inReview: Boolean,
    onAskAi: (() -> Unit)? = null,
    onBack: () -> Unit,
    onScrollChanged: (Int) -> Unit,
) {
    val point = snapshot.pointsByCode[frame.pointCode] ?: return
    val meridian = snapshot.meridiansByCode[point.meridianCode]
    val region = snapshot.regionByPointCode[point.code]
    val learning = snapshot.learningForPoint(point.code)
    val occluded = snapshot.occludedByPointCode[point.code]
    val relatedSymptoms = remember(snapshot, point.code) {
        snapshot.symptomsForPoint(point.code)
    }
    val preferredView = snapshot.viewsContainingPoint(point.code).firstOrNull()
        ?: snapshot.preferredViewForMeridian(point.meridianCode)
    val viewName = snapshot.atlasViewById[preferredView]?.name ?: preferredView.wireKey
    val rating = snapshot.projectionsByPointCode[point.code]?.get(preferredView)?.rating
    val ratingNote = rating?.let { snapshot.atlas.projectionNotes[it] }.orEmpty()

    val scroll = rememberScrollState(initial = frame.scrollOffset)
    val reportScroll by rememberUpdatedState(onScrollChanged)
    val active = LocalNavigationPageActive.current
    LaunchedEffect(scroll, active) {
        if (active) snapshotFlow { scroll.value }.collect { reportScroll(it) }
    }
    val animate = rememberEncyclopediaAnimationsEnabled()
    val starScale = remember { Animatable(1f) }
    LaunchedEffect(isFavorite) {
        if (!animate || !isFavorite) {
            starScale.snapTo(1f)
        } else {
            starScale.animateTo(1.22f, tween(110))
            starScale.animateTo(1f, tween(140))
        }
    }

    val backdrop = rememberUiBackdrop()
    val outline = UiRoles.color(UiColorRole.OutlineVariant)
    val standardRef = EncyclopediaSourceRef(
        title = "GB/T 12346-2021《经穴名称与定位》§${point.clause}（第 ${point.pdfPage} 页）",
        url = "${snapshot.learning.references.standardPdfUrl}#page=${point.pdfPage}",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("acupoint-detail-screen-${point.code}"),
    ) {
        UiGlassBackdrop(backdrop, Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(start = 16.dp, end = 16.dp, top = 84.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Hero 信息头
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = meridian?.name ?: point.meridianCode,
                        style = UiRoles.text(MaterialTheme.typography.labelLarge),
                        color = UiRoles.accent,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "${point.name} ${point.code}",
                        style = UiRoles.text(MaterialTheme.typography.headlineMedium),
                        fontWeight = FontWeight.Bold,
                        color = UiRoles.foreground,
                        modifier = Modifier.semantics { heading() }.testTag("acupoint-hero-title"),
                    )
                    val metaParts = listOfNotNull(
                        point.pinyin.takeIf(String::isNotBlank),
                        point.special.takeIf(String::isNotBlank),
                        occluded?.regionLabel ?: point.region.takeIf(String::isNotBlank),
                    )
                    if (metaParts.isNotEmpty()) {
                        Text(
                            text = metaParts.joinToString(" · "),
                            style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                            color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                        )
                    }
                }

                // 单穴局部预览卡片（仅标当前穴位，蓝字进入整组局部图）
                if (region != null) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = UiRoles.surface(UiSurfaceRole.Card),
                        border = BorderStroke(1.dp, outline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            AcupointSinglePreviewCanvas(point = point, region = region)
                            Text(
                                text = "查看${region.title} ↗",
                                style = UiRoles.actionText,
                                color = UiRoles.accent,
                                modifier = Modifier
                                    .clickable(role = Role.Button) { onOpenRegion(point.code) }
                                    .padding(horizontal = 4.dp, vertical = 10.dp)
                                    .testTag("acupoint-open-region-link"),
                            )
                        }
                    }
                }

                // 四标签分段控制器：定位 / 经属 / 主治 / 来源
                EncyclopediaSegmentedControl(
                    options = AcupointDetailTab.entries.map { tab ->
                        SegmentedTabOption(
                            value = tab,
                            label = tab.label,
                            testTag = "acupoint-tab-${tab.wireKey}",
                        )
                    },
                    selected = frame.detailTab,
                    onSelect = onSelectDetailTab,
                    backdrop = null,
                    compact = true,
                )

                AnimatedContent(
                    targetState = frame.detailTab,
                    transitionSpec = {
                        if (animate) fadeIn(tween(150)) togetherWith fadeOut(tween(110))
                        else fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    },
                    label = "acupointDetailTab",
                ) { activeTab ->
                    when (activeTab) {
                        AcupointDetailTab.LOCATION -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionSurfaceCard(title = "标准定位") {
                                Text(
                                    text = point.location,
                                    style = UiRoles.text(MaterialTheme.typography.bodyLarge),
                                    color = UiRoles.foreground,
                                )
                                Text(
                                    text = if (occluded != null) {
                                        "所在部位为${occluded.regionLabel}，本体位遮挡，可从「来源」查看国标定位。"
                                    } else {
                                        "$viewName：$ratingNote"
                                    }.trimEnd('。', ' ') + "。",
                                    style = UiRoles.text(MaterialTheme.typography.bodySmall),
                                    color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                                )
                                if (!point.projectionNote.isNullOrBlank()) {
                                    Text(
                                        text = point.projectionNote,
                                        style = UiRoles.text(MaterialTheme.typography.bodySmall),
                                        color = UiRoles.accent,
                                    )
                                }
                            }
                        }

                        AcupointDetailTab.MERIDIAN -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionSurfaceCard(title = "归经与序号") {
                                Text(
                                    text = "${meridian?.name ?: point.meridianCode}（${point.meridianCode} · 第 ${point.orderInMeridian} 穴 / 共 ${meridian?.pointCount ?: "?"} 穴）",
                                    style = UiRoles.text(MaterialTheme.typography.bodyLarge),
                                    color = UiRoles.foreground,
                                )
                                if (!meridian?.direction.isNullOrBlank()) {
                                    Text(
                                        text = "循行方向：${meridian?.direction}",
                                        style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                                        color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                                    )
                                }
                                if (point.special.isNotBlank()) {
                                    Text(
                                        text = "特定穴属性：${point.special}",
                                        style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                                        color = UiRoles.foreground,
                                    )
                                }
                                UiAction(
                                    label = "查看本经全部穴位",
                                    onClick = { onOpenMeridianPoints(point.meridianCode) },
                                    backdrop = null,
                                    actionId = "acupoint.meridian.all",
                                    modifier = Modifier.testTag("acupoint-meridian-all"),
                                )
                            }
                        }

                        AcupointDetailTab.INDICATIONS -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val displayHeading = learning?.displayHeading ?: "传统主治"
                            SectionSurfaceCard(
                                title = displayHeading,
                                modifier = Modifier.testTag("acupoint-indications-card"),
                            ) {
                                if (learning == null) {
                                    Text(
                                        text = "本穴的主治资料待教材核对。",
                                        style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                                        color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                                    )
                                } else {
                                    learning.groups.forEachIndexed { gIdx, group ->
                                        if (gIdx > 0) {
                                            HorizontalDivider(
                                                thickness = 0.5.dp,
                                                color = outline,
                                            )
                                        }
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                text = group.title,
                                                style = UiRoles.text(MaterialTheme.typography.titleSmall),
                                                fontWeight = FontWeight.SemiBold,
                                                color = UiRoles.accent,
                                            )
                                            Text(
                                                text = group.content,
                                                style = UiRoles.text(MaterialTheme.typography.bodyLarge),
                                                color = UiRoles.foreground,
                                            )
                                        }
                                    }
                                    HorizontalDivider(thickness = 0.5.dp, color = outline)
                                    Text(
                                        text = "${learning.source.title} ↗",
                                        style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                                        fontWeight = FontWeight.Medium,
                                        color = UiRoles.accent,
                                        modifier = Modifier
                                            .clickable(role = Role.Button) { onOpenSourceRef(learning.source) }
                                            .padding(vertical = 8.dp)
                                            .testTag("acupoint-indication-source"),
                                    )
                                }
                            }

                            if (relatedSymptoms.isNotEmpty()) {
                                SectionSurfaceCard(title = "关联病症（共 ${relatedSymptoms.size} 项）") {
                                    relatedSymptoms.forEachIndexed { sIdx, symptom ->
                                        val assoc = snapshot.associationsByKey[symptom.id to point.code]
                                        if (sIdx > 0) {
                                            HorizontalDivider(thickness = 0.5.dp, color = outline)
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 52.dp)
                                                .clickable(role = Role.Button) { onOpenSymptom(symptom.id) }
                                                .padding(vertical = 8.dp)
                                                .testTag("acupoint-related-symptom-${symptom.id}"),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text(
                                                    text = symptom.name,
                                                    style = UiRoles.text(MaterialTheme.typography.titleSmall),
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = UiRoles.foreground,
                                                )
                                                if (!assoc?.reason.isNullOrBlank()) {
                                                    Text(
                                                        text = assoc?.reason.orEmpty(),
                                                        style = UiRoles.text(MaterialTheme.typography.bodySmall),
                                                        color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                                                    )
                                                }
                                            }
                                            Icon(
                                                UiIcons.Chevron,
                                                contentDescription = "打开${symptom.name}",
                                                modifier = Modifier.size(18.dp),
                                                tint = UiRoles.color(UiColorRole.OnSurfaceVariant),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        AcupointDetailTab.SOURCE -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionSurfaceCard(title = "定位与循行依据") {
                                SourceLinkRow(
                                    title = standardRef.title,
                                    onClick = { onOpenSourceRef(standardRef) },
                                    testTag = "acupoint-source-standard",
                                )
                                if (point.meridianCode == "LU") {
                                    HorizontalDivider(thickness = 0.5.dp, color = outline)
                                    val courseRef = EncyclopediaSourceRef(
                                        title = snapshot.manifest.upstreamAttribution.courseware.title,
                                        url = snapshot.atlas.lungFront.textbookUrl,
                                    )
                                    SourceLinkRow(
                                        title = courseRef.title,
                                        onClick = { onOpenSourceRef(courseRef) },
                                        testTag = "acupoint-source-courseware",
                                    )
                                }
                            }
                            if (learning != null) {
                                SectionSurfaceCard(title = "${learning.displayHeading}出处") {
                                    SourceLinkRow(
                                        title = learning.source.title,
                                        onClick = { onOpenSourceRef(learning.source) },
                                        testTag = "acupoint-source-learning",
                                    )
                                }
                            }
                            if (relatedSymptoms.isNotEmpty()) {
                                SectionSurfaceCard(title = "关联病症各自出处") {
                                    relatedSymptoms.forEachIndexed { idx, symptom ->
                                        val assoc = snapshot.associationsByKey[symptom.id to point.code]
                                        val ref = assoc?.dedicatedSource ?: learning?.source
                                        if (ref != null) {
                                            if (idx > 0) HorizontalDivider(thickness = 0.5.dp, color = outline)
                                            SourceLinkRow(
                                                title = "${symptom.name}：${ref.title}",
                                                subtitle = assoc?.reason,
                                                onClick = { onOpenSourceRef(ref) },
                                                testTag = "acupoint-source-assoc-${symptom.id}",
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            CourseScreenHeader(
                title = "${point.name} ${point.code}",
                onBack = onBack,
                backTag = "acupoint-back",
                titleTag = "acupoint-header-title",
                backdrop = backdrop,
            ) {
                if (canToggleFavorite) {
                    UiAction(
                        label = if (isFavorite) "取消收藏${point.name}" else "收藏${point.name}",
                        icon = if (isFavorite) UiIcons.FavoriteFilled else UiIcons.Favorite,
                        onClick = { onToggleFavorite(point.code) },
                        backdrop = backdrop,
                        foreground = if (isFavorite) UiRoles.accent else UiRoles.foreground,
                        foregroundModifier = Modifier.graphicsLayer {
                            scaleX = starScale.value
                            scaleY = starScale.value
                        },
                        actionId = "acupoint.favorite",
                        modifier = Modifier.testTag("acupoint-favorite-button"),
                    )
                }
            }
        }

        EncyclopediaBottomBar(
            backdrop = backdrop,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compact = maxWidth < 370.dp
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
                ) {
                    UiAction(
                        label = if (compact) "图谱" else "定位图谱",
                        onClick = { onLocateOnAtlas(point.code) },
                        backdrop = null,
                        foreground = UiRoles.accent,
                        horizontalPadding = if (compact) 6.dp else 10.dp,
                        actionId = "acupoint.bottom.locate",
                        modifier = Modifier.testTag("acupoint-bottom-locate"),
                    )
                    if (onAddReview != null) {
                        UiAction(
                            label = if (inReview) (if (compact) "已入" else "已入回顾") else (if (compact) "回顾" else "加入回顾"),
                            onClick = { onAddReview(point.code) },
                            enabled = !inReview,
                            backdrop = null,
                            foreground = UiRoles.accent,
                            horizontalPadding = if (compact) 6.dp else 10.dp,
                            actionId = "acupoint.bottom.review",
                            modifier = Modifier.testTag("acupoint-bottom-review"),
                        )
                    }
                    if (onAskAi != null) {
                        UiAction(
                            label = if (compact) "问AI" else "问百科 AI",
                            onClick = onAskAi,
                            backdrop = null,
                            foreground = UiRoles.accent,
                            horizontalPadding = if (compact) 6.dp else 10.dp,
                            actionId = "acupoint.bottom.ai",
                            modifier = Modifier.testTag("acupoint-bottom-ai"),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    UiAction(
                        label = "来源",
                        onClick = onOpenSourceSheet,
                        filled = true,
                        backdrop = backdrop,
                        horizontalPadding = if (compact) 10.dp else 14.dp,
                        actionId = "acupoint.bottom.sources",
                        modifier = Modifier.testTag("acupoint-bottom-sources"),
                    )
                }
            }
        }
    }
}

@Composable
internal fun SectionSurfaceCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = UiRoles.surface(UiSurfaceRole.Card),
        border = BorderStroke(1.dp, UiRoles.color(UiColorRole.OutlineVariant)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = UiRoles.text(MaterialTheme.typography.titleMedium),
                fontWeight = FontWeight.SemiBold,
                color = UiRoles.foreground,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

@Composable
internal fun SourceLinkRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "$title ↗",
                style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                color = UiRoles.accent,
                fontWeight = FontWeight.Medium,
            )
            subtitle?.takeIf(String::isNotBlank)?.let {
                Text(
                    text = it,
                    style = UiRoles.text(MaterialTheme.typography.bodySmall),
                    color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                )
            }
        }
        Icon(
            UiIcons.Chevron,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = UiRoles.accent,
        )
    }
}
