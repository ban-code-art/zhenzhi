package com.example.myapplication.ui.encyclopedia

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.encyclopedia.EncyclopediaSnapshot
import com.example.myapplication.data.encyclopedia.SymptomLesson
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiBackdrop
import com.example.myapplication.ui.components.UiGlassBackdrop
import com.example.myapplication.ui.components.rememberUiBackdrop
import com.example.myapplication.ui.knowledge.course.CourseScreenHeader
import com.example.myapplication.ui.navigation.LocalNavigationPageActive
import com.example.myapplication.ui.theme.UiColorRole
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

/**
 * 病症学习列表区：9 类筛选与分组卡片排布（保留类别筛选、别名关联数展示）。
 */
@Composable
internal fun SymptomIndexSection(
    snapshot: EncyclopediaSnapshot,
    frame: EncyclopediaPageFrame.Root,
    listState: LazyListState,
    contentPadding: PaddingValues,
    backdrop: UiBackdrop?,
    onSelectCategory: (String) -> Unit,
    onOpenSymptom: (String) -> Unit,
) {
    val shown = remember(snapshot, frame.symptomCategory) {
        EncyclopediaSearch.filterSymptoms(snapshot, frame.symptomCategory)
    }

    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize().testTag("symptom-index-section"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "symptom-index-heading", contentType = "heading") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "病症学习",
                    style = UiRoles.text(MaterialTheme.typography.titleLarge),
                    fontWeight = FontWeight.Bold,
                    color = UiRoles.foreground,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = "${shown.size} 项",
                    style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                    color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                )
            }
        }
        item(key = "symptom-index-filters", contentType = "filters") {
            EncyclopediaFilterFlowRow(
                items = snapshot.learning.symptomCategories.map { it to it },
                selectedKey = frame.symptomCategory,
                onSelect = onSelectCategory,
                backdrop = backdrop,
                tagPrefix = "symptom-category",
            )
        }

        if (frame.symptomCategory == "全部") {
            snapshot.learning.symptomCategories.forEach { category ->
                val lessons = snapshot.symptomsByCategory[category].orEmpty()
                if (category != "全部" && lessons.isNotEmpty()) {
                    item(key = "category-$category", contentType = "category") {
                        Text(
                            text = category,
                            style = UiRoles.text(MaterialTheme.typography.titleMedium),
                            fontWeight = FontWeight.SemiBold,
                            color = UiRoles.foreground,
                            modifier = Modifier.padding(top = 4.dp).semantics { heading() },
                        )
                    }
                    items(lessons, key = { it.id }, contentType = { "symptom" }) { lesson ->
                        SymptomLessonCard(lesson = lesson, onClick = { onOpenSymptom(lesson.id) })
                    }
                }
            }
        } else {
            items(shown, key = { it.id }, contentType = { "symptom" }) { lesson ->
                SymptomLessonCard(lesson = lesson, onClick = { onOpenSymptom(lesson.id) })
            }
        }
    }
}

@Composable
private fun SymptomLessonCard(
    lesson: SymptomLesson,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = UiRoles.surface(UiSurfaceRole.Card),
        border = BorderStroke(1.dp, UiRoles.color(UiColorRole.OutlineVariant)),
        modifier = Modifier.fillMaxWidth().testTag("symptom-card-${lesson.id}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = lesson.name,
                    style = UiRoles.text(MaterialTheme.typography.titleMedium),
                    fontWeight = FontWeight.SemiBold,
                    color = UiRoles.foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = lesson.summary,
                    style = UiRoles.text(MaterialTheme.typography.bodySmall),
                    color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${lesson.links.size} 个关联穴位",
                    style = UiRoles.text(MaterialTheme.typography.labelMedium),
                    color = UiRoles.accent,
                    fontWeight = FontWeight.Medium,
                )
            }
            Icon(
                UiIcons.Chevron,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = UiRoles.color(UiColorRole.OnSurfaceVariant),
            )
        }
    }
}

/**
 * 病症学习详情页：常见表现、可展开辨证学习线索、关联穴位（点击直达主治）、相关经络（点击跳图谱）及底部单容器操作条。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SymptomDetailScreen(
    snapshot: EncyclopediaSnapshot,
    frame: EncyclopediaPageFrame.SymptomDetail,
    backButtonText: String,
    onToggleClue: (Int) -> Unit,
    onOpenPointIndications: (String) -> Unit,
    onOpenRelatedMeridian: (String) -> Unit,
    onOpenSourceSheet: () -> Unit,
    onAskAi: (() -> Unit)? = null,
    onBack: () -> Unit,
    onScrollChanged: (Int) -> Unit,
) {
    val symptom = snapshot.symptomsById[frame.symptomId] ?: return
    val relatedMeridians = remember(snapshot, symptom.id) {
        snapshot.meridianCodesBySymptomId[symptom.id].orEmpty()
            .mapNotNull { code -> snapshot.meridiansByCode[code] }
    }
    val scroll = rememberScrollState(initial = frame.scrollOffset)
    val reportScroll by rememberUpdatedState(onScrollChanged)
    val active = LocalNavigationPageActive.current
    LaunchedEffect(scroll, active) {
        if (active) snapshotFlow { scroll.value }.collect { reportScroll(it) }
    }
    val animate = rememberEncyclopediaAnimationsEnabled()
    val backdrop = rememberUiBackdrop()
    val outline = UiRoles.color(UiColorRole.OutlineVariant)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("symptom-detail-screen-${symptom.id}"),
    ) {
        UiGlassBackdrop(backdrop, Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(start = 16.dp, end = 16.dp, top = 84.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = UiRoles.color(UiColorRole.PrimaryContainer),
                    ) {
                        Text(
                            text = symptom.category,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = UiRoles.text(MaterialTheme.typography.labelMedium),
                            color = UiRoles.color(UiColorRole.OnPrimaryContainer),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = symptom.name,
                        style = UiRoles.text(MaterialTheme.typography.headlineMedium),
                        fontWeight = FontWeight.Bold,
                        color = UiRoles.foreground,
                        modifier = Modifier.semantics { heading() }.testTag("symptom-hero-title"),
                    )
                    Text(
                        text = symptom.summary,
                        style = UiRoles.text(MaterialTheme.typography.bodyLarge),
                        color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                    )
                }

                // 常见表现
                SectionSurfaceCard(title = "常见表现") {
                    symptom.signals.forEachIndexed { idx, entry ->
                        if (idx > 0) HorizontalDivider(thickness = 0.5.dp, color = outline)
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = entry.title,
                                style = UiRoles.text(MaterialTheme.typography.titleSmall),
                                fontWeight = FontWeight.SemiBold,
                                color = UiRoles.foreground,
                            )
                            Text(
                                text = entry.content,
                                style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                                color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                            )
                        }
                    }
                }

                // 辨证学习线索（可折叠展开，记录状态）
                SectionSurfaceCard(title = "辨证学习线索") {
                    symptom.clues.forEachIndexed { idx, clue ->
                        val expanded = idx in frame.openClues
                        if (idx > 0) HorizontalDivider(thickness = 0.5.dp, color = outline)
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable(role = Role.Button) { onToggleClue(idx) }
                                    .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
                                    .padding(vertical = 8.dp)
                                    .testTag("symptom-clue-toggle-$idx"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = clue.title,
                                    style = UiRoles.text(MaterialTheme.typography.titleSmall),
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (expanded) UiRoles.accent else UiRoles.foreground,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                )
                                Icon(
                                    UiIcons.ChevronDown,
                                    contentDescription = if (expanded) "收起" else "展开",
                                    tint = UiRoles.color(UiColorRole.OnSurfaceVariant),
                                    modifier = Modifier
                                        .size(18.dp)
                                        .graphicsLayer { rotationZ = if (expanded) 180f else 0f },
                                )
                            }
                            AnimatedVisibility(
                                visible = expanded,
                                enter = if (animate) expandVertically(tween(170)) + fadeIn(tween(150)) else fadeIn(tween(0)),
                                exit = if (animate) shrinkVertically(tween(140)) + fadeOut(tween(120)) else fadeOut(tween(0)),
                            ) {
                                Text(
                                    text = clue.content,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 10.dp)
                                        .testTag("symptom-clue-body-$idx"),
                                    style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                                    color = UiRoles.foreground,
                                )
                            }
                        }
                    }
                }

                // 关联穴位：复用紧凑列表行，点击直达穴位「主治」页签
                SectionSurfaceCard(title = "关联穴位（点击直达主治）") {
                    symptom.links.forEachIndexed { idx, link ->
                        val point = snapshot.pointsByCode[link.pointCode] ?: return@forEachIndexed
                        val meridianShort = snapshot.meridiansByCode[point.meridianCode]?.shortName ?: point.meridianCode
                        if (idx > 0) HorizontalDivider(thickness = 0.5.dp, color = outline)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 54.dp)
                                .clickable(role = Role.Button) { onOpenPointIndications(point.code) }
                                .padding(vertical = 8.dp)
                                .testTag("symptom-point-link-${point.code}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = "${point.name} ${point.code} · $meridianShort",
                                    style = UiRoles.text(MaterialTheme.typography.titleSmall),
                                    fontWeight = FontWeight.SemiBold,
                                    color = UiRoles.foreground,
                                )
                                Text(
                                    text = link.reason,
                                    style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                                    color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                                )
                            }
                            Icon(
                                UiIcons.Chevron,
                                contentDescription = "打开${point.name}主治",
                                modifier = Modifier.size(18.dp),
                                tint = UiRoles.accent,
                            )
                        }
                    }
                }

                // 相关经络：紧凑入口跳往对应经络图谱并可返回本病症
                if (relatedMeridians.isNotEmpty()) {
                    SectionSurfaceCard(title = "相关经络（点击在图谱查看）") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            relatedMeridians.forEach { meridian ->
                                UiAction(
                                    label = meridian.name,
                                    onClick = { onOpenRelatedMeridian(meridian.code) },
                                    horizontalPadding = 12.dp,
                                    backdrop = null,
                                    actionId = "symptom.meridian.${meridian.code}",
                                    modifier = Modifier.testTag("symptom-meridian-${meridian.code}"),
                                )
                            }
                        }
                    }
                }
            }
        }

        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            CourseScreenHeader(
                title = symptom.name,
                onBack = onBack,
                backTag = "symptom-back",
                titleTag = "symptom-header-title",
                backdrop = backdrop,
            )
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
                        label = backButtonText,
                        onClick = onBack,
                        backdrop = null,
                        foreground = UiRoles.accent,
                        horizontalPadding = if (compact) 8.dp else 12.dp,
                        actionId = "symptom.bottom.back",
                        modifier = Modifier.testTag("symptom-bottom-back"),
                    )
                    if (onAskAi != null) {
                        UiAction(
                            label = if (compact) "问AI" else "问百科 AI",
                            onClick = onAskAi,
                            backdrop = null,
                            foreground = UiRoles.accent,
                            horizontalPadding = if (compact) 8.dp else 12.dp,
                            actionId = "symptom.bottom.ai",
                            modifier = Modifier.testTag("symptom-bottom-ai"),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    UiAction(
                        label = if (compact) "教材出处" else "教材与关联出处",
                        onClick = onOpenSourceSheet,
                        filled = true,
                        backdrop = backdrop,
                        horizontalPadding = if (compact) 10.dp else 14.dp,
                        actionId = "symptom.bottom.sources",
                        modifier = Modifier.testTag("symptom-bottom-sources"),
                    )
                }
            }
        }
    }
}
