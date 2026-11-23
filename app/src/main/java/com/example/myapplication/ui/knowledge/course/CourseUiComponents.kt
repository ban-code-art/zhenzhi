package com.example.myapplication.ui.knowledge.course

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected as semanticsSelected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.components.LocalUiBackdrop
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiBackdrop
import com.example.myapplication.ui.theme.UiColorRole
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

/** A growing title area keeps the 48dp controls usable at 200% text without a fixed-height crop. */
@Composable
internal fun CourseScreenHeader(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    backTag: String? = null,
    titleTag: String? = null,
    subtitleTag: String? = null,
    backdrop: UiBackdrop? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val pageBackground = UiRoles.surface(UiSurfaceRole.Page)
    val containerModifier = if (backdrop != null) {
        Modifier.fillMaxWidth().background(
            Brush.verticalGradient(
                0.0f to pageBackground,
                0.64f to pageBackground.copy(alpha = .96f),
                0.86f to pageBackground.copy(alpha = .82f),
                1.0f to Color.Transparent,
            ),
        )
    } else {
        Modifier.fillMaxWidth().background(pageBackground)
    }
    CompositionLocalProvider(LocalUiBackdrop provides backdrop) {
        Box(containerModifier) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UiAction(
                    label = "返回",
                    icon = UiIcons.Back,
                    onClick = onBack,
                    backdrop = backdrop,
                    actionId = "course.back",
                    modifier = backTag?.let { Modifier.testTag(it) } ?: Modifier,
                )
                Column(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 4.dp)) {
                    Text(
                        title,
                        modifier = titleTag?.let { Modifier.testTag(it) } ?: Modifier,
                        style = UiRoles.text(MaterialTheme.typography.titleLarge),
                        fontWeight = FontWeight.SemiBold,
                        color = UiRoles.foreground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    subtitle?.takeIf(String::isNotBlank)?.let {
                        Text(
                            it,
                            modifier = subtitleTag?.let { tag -> Modifier.testTag(tag) } ?: Modifier,
                            style = UiRoles.text(MaterialTheme.typography.bodySmall),
                            color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                actions()
            }
        }
    }
}

@Composable
internal fun CourseSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    tag: String,
    placeholder: String = "搜索课程或章节",
) {
    val cardColor = UiRoles.surface(UiSurfaceRole.Card)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag),
        shape = RoundedCornerShape(14.dp),
        textStyle = UiRoles.text(MaterialTheme.typography.bodyLarge),
        placeholder = {
            Text(
                placeholder,
                style = UiRoles.text(MaterialTheme.typography.bodyLarge),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = { Icon(UiIcons.Search, contentDescription = null) },
        trailingIcon = if (value.isNotEmpty()) ({
            UiAction(
                label = "清除搜索",
                icon = UiIcons.Close,
                onClick = { onValueChange("") },
                inheritedPane = true,
                actionId = "course.search.clear",
                modifier = Modifier.testTag("$tag-clear"),
            )
        }) else null,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = cardColor,
            unfocusedContainerColor = cardColor,
            unfocusedBorderColor = UiRoles.color(UiColorRole.OutlineVariant),
        ),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CourseSubjectFilters(
    subjects: List<CourseSubject>,
    selected: CourseSubject,
    onSelect: (CourseSubject) -> Unit,
) {
    FlowRow(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        subjects.forEach { subject ->
            val isSelected = selected == subject
            UiAction(
                label = subject.label,
                leadingIcon = if (isSelected) UiIcons.Check else null,
                filled = isSelected,
                onClick = { if (!isSelected) onSelect(subject) },
                actionId = "courseLibrary.filter.${subject.name}",
                modifier = Modifier
                    .semantics { semanticsSelected = isSelected }
                    .testTag("course-library-filter-${subject.name}"),
            )
        }
    }
}

@Composable
internal fun CourseStatusBadge(label: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (emphasized) UiRoles.color(UiColorRole.PrimaryContainer) else UiRoles.surface(UiSurfaceRole.Block),
        contentColor = if (emphasized) UiRoles.color(UiColorRole.OnPrimaryContainer) else UiRoles.color(UiColorRole.OnSurfaceVariant),
    ) {
        Text(
            label,
            modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = UiRoles.text(MaterialTheme.typography.labelSmall),
            fontWeight = FontWeight.Medium,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CourseBookCard(
    number: Int,
    title: String,
    description: String?,
    metadata: String,
    status: String,
    subject: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    statusModifier: Modifier = Modifier,
    unavailable: String? = null,
) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val cardColor = UiRoles.surface(UiSurfaceRole.Card)
    val foreground = UiRoles.foreground
    val secondary = UiRoles.color(UiColorRole.OnSurfaceVariant)
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, UiRoles.color(UiColorRole.OutlineVariant)),
        colors = CardDefaults.cardColors(
            containerColor = cardColor,
            contentColor = foreground,
            disabledContainerColor = cardColor,
            disabledContentColor = foreground,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
            if (!largeText) {
                Surface(
                    Modifier.width(48.dp).heightIn(min = 64.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = UiRoles.color(UiColorRole.PrimaryContainer),
                ) {
                    Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(
                            number.toString().padStart(2, '0'),
                            style = UiRoles.text(MaterialTheme.typography.titleLarge),
                            fontWeight = FontWeight.SemiBold,
                            color = UiRoles.color(UiColorRole.OnPrimaryContainer),
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (largeText && !subject.isNullOrBlank()) {
                    Text(
                        subject,
                        style = UiRoles.text(MaterialTheme.typography.labelMedium),
                        color = UiRoles.accent,
                    )
                }
                Text(
                    title,
                    style = UiRoles.text(MaterialTheme.typography.titleMedium),
                    fontWeight = FontWeight.SemiBold,
                    color = foreground,
                    maxLines = if (largeText) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                description?.takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        style = UiRoles.text(MaterialTheme.typography.bodySmall),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = secondary,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CourseStatusBadge(status, statusModifier, emphasized = status == "已审核")
                    Text(
                        metadata,
                        style = UiRoles.text(MaterialTheme.typography.bodySmall),
                        color = secondary,
                    )
                }
                unavailable?.let {
                    Text(it, style = UiRoles.text(MaterialTheme.typography.bodySmall), color = secondary)
                }
            }
            if (enabled) {
                Spacer(Modifier.width(4.dp))
                Icon(UiIcons.Chevron, null, Modifier.size(20.dp), tint = secondary)
            }
        }
    }
}

/** Continuous chapter rows keep navigation prominent without repeating a group of large actions. */
@Composable
internal fun CourseChapterRow(
    number: Int,
    title: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    details: @Composable ColumnScope.() -> Unit = {},
) {
    val secondary = UiRoles.color(UiColorRole.OnSurfaceVariant)
    Surface(
        color = if (highlighted) UiRoles.color(UiColorRole.PrimaryContainer) else UiRoles.surface(UiSurfaceRole.Card),
    ) {
        Row(
            modifier.fillMaxWidth().heightIn(min = 72.dp)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                number.toString().padStart(2, '0'),
                Modifier.widthIn(min = 28.dp).padding(top = 2.dp),
                style = UiRoles.text(MaterialTheme.typography.labelLarge),
                color = UiRoles.accent,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title,
                    style = UiRoles.text(MaterialTheme.typography.bodyLarge),
                    fontWeight = FontWeight.Medium,
                    color = UiRoles.foreground,
                    maxLines = if (LocalDensity.current.fontScale >= 1.5f) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                details()
            }
            Spacer(Modifier.width(4.dp))
            Icon(UiIcons.Chevron, null, Modifier.size(20.dp), tint = secondary)
        }
    }
}

@Composable
internal fun CourseSectionHeading(title: String, modifier: Modifier = Modifier, detail: String? = null) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = UiRoles.text(MaterialTheme.typography.titleMedium),
            fontWeight = FontWeight.SemiBold,
            color = UiRoles.foreground,
        )
        detail?.let {
            Text(
                it,
                style = UiRoles.text(MaterialTheme.typography.bodySmall),
                color = UiRoles.color(UiColorRole.OnSurfaceVariant),
            )
        }
    }
}

@Composable
internal fun CourseInlineNotice(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (error) UiRoles.color(UiColorRole.ErrorContainer) else UiRoles.surface(UiSurfaceRole.Block),
    ) {
        Text(
            text,
            Modifier.padding(12.dp),
            style = UiRoles.text(MaterialTheme.typography.bodySmall),
            color = if (error) UiRoles.color(UiColorRole.OnErrorContainer) else UiRoles.color(UiColorRole.OnSurfaceVariant),
        )
    }
}

internal fun courseDisplayError(code: String): String = when (code) {
    "LOGIN_REQUIRED" -> "登录后可查看个人课程记录，内置教材可直接阅读。"
    "SESSION_CHANGED" -> "登录状态已变化，请返回后重新进入。"
    "COURSE_LIBRARY_LOAD_FAILED" -> "课程目录暂未载入，请刷新重试。"
    "COURSE_NOT_FOUND" -> "这本课程暂不可用，请返回课程列表。"
    "COURSE_CHANGED" -> "课程内容已更新，请刷新后重试。"
    "COURSE_NOT_APPROVED" -> "这本课程暂不可用。"
    "COURSE_SEQUENCE_INVALID", "COURSE_SCOPE_INVALID", "COURSE_CATALOG_INVALID" -> "课程目录暂不可用，请刷新重试。"
    else -> if (code.matches(Regex("[A-Z][A-Z0-9_]+"))) "课程暂不可用，请稍后重试。" else code
}
