package com.example.myapplication.ui.knowledge.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.myapplication.domain.reader.ReaderTarget
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiAlertHost
import com.example.myapplication.ui.components.UiGlassBackdrop
import com.example.myapplication.ui.components.rememberAdaptiveWindowInfo
import com.example.myapplication.ui.components.rememberUiBackdrop
import com.example.myapplication.ui.knowledge.course.*
import com.example.myapplication.ui.theme.UiColorRole
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared directory; the route owns its saved position and refreshes after reading. */
@Composable
fun CourseBookScreen(model: CourseBookViewModel, onOpen: (ReaderTarget) -> Unit, onBack: () -> Unit,
    onContinue: (CourseContinueReading) -> Unit = { onOpen(it.target) },
    scroll: LazyListState = rememberLazyListState(), summary: String? = null) {
    val state by model.state.collectAsState()
    val adaptive = rememberAdaptiveWindowInfo()
    val backdrop = rememberUiBackdrop()
    var showSummary by rememberSaveable { mutableStateOf(false) }
    val enabled = !state.closed && !state.loading && state.error == null
    val completed = state.rows.count { it.readingStatus == CourseReadingStatus.COMPLETED }
    val density = LocalDensity.current
    var headerHeightPx by remember { mutableIntStateOf(0) }
    val headerTopPadding = with(density) { headerHeightPx.toDp() }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(UiRoles.surface(UiSurfaceRole.Page))) {
        UiGlassBackdrop(
            backdrop = backdrop,
            modifier = Modifier.fillMaxSize(),
            captureEnabled = false,
        ) {
            LazyColumn(
                state = scroll,
                modifier = Modifier.align(Alignment.TopCenter)
                    .widthIn(max = adaptive.contentMaxWidth).fillMaxSize().testTag("course-chapters"),
                contentPadding = PaddingValues(
                    start = if (adaptive.widthDp < 360) 16.dp else 20.dp,
                    top = maxOf(headerTopPadding, 76.dp) + 8.dp,
                    end = if (adaptive.widthDp < 360) 16.dp else 20.dp,
                    bottom = 16.dp + navBottom,
                ),
            ) {
                // Keep these two overview items aligned with CourseDirectoryPositionProjector.layout.
                item(key = "course-directory-overview") {
                    Surface(shape = RoundedCornerShape(16.dp), color = UiRoles.surface(UiSurfaceRole.Card)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            summary?.takeIf(String::isNotBlank)?.let {
                                Text(it, Modifier.testTag("course-book-summary"), style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                                    color = UiRoles.color(UiColorRole.OnSurfaceVariant), maxLines = 3)
                            }
                            if (state.rows.isNotEmpty()) {
                                Text("已完成 $completed / ${state.rows.size} 章",
                                    style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                                    color = UiRoles.foreground)
                                LinearProgressIndicator(progress = { completed.toFloat() / state.rows.size },
                                    modifier = Modifier.fillMaxWidth().height(4.dp),
                                    trackColor = UiRoles.color(UiColorRole.OutlineVariant))
                            }
                            state.continueReading?.let { next ->
                                val continueLabel = if (state.rows.any { it.readingStatus != CourseReadingStatus.NOT_STARTED }) "继续学习" else "开始学习"
                                UiAction(
                                    label = continueLabel,
                                    onClick = { onContinue(next) },
                                    enabled = enabled,
                                    filled = true,
                                    actionId = "courseBook.continue",
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("course-continue"),
                                )
                                if (next.resetReason != null) Text("阅读位置已失效，将从本章开头阅读",
                                    Modifier.testTag("course-continue-reset"),
                                    style = UiRoles.text(MaterialTheme.typography.bodySmall),
                                    color = UiRoles.color(UiColorRole.OnSurfaceVariant))
                            }
                            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("course-loading"))
                            state.error?.let { error ->
                                CourseInlineNotice(courseDisplayError(error), Modifier.testTag("course-error"), error = true)
                                if (!state.closed) UiAction(
                                    label = "重试",
                                    onClick = model::reload,
                                    actionId = "courseBook.retry",
                                )
                            }
                            state.quizError?.let {
                                CourseInlineNotice("练习摘要加载失败", Modifier.testTag("course-quiz-error"), error = true)
                                UiAction(
                                    label = "重试练习摘要",
                                    onClick = model::reload,
                                    enabled = enabled,
                                    actionId = "courseBook.quizRetry",
                                    modifier = Modifier.testTag("course-quiz-retry"),
                                )
                            }
                        }
                    }
                }
                item(key = "course-directory-heading") {
                    CourseSectionHeading("章节目录", Modifier.padding(top = 12.dp), "${state.rows.size} 章")
                }
                items(state.rows, key = { "chapter:${it.target.chapterKey}" }) { row ->
                    CourseChapterRow(state.rows.indexOf(row) + 1, row.title, { onOpen(row.target) }, enabled,
                        Modifier.testTag("course-chapter-${row.target.chapterKey}"),
                        highlighted = row.readingStatus == CourseReadingStatus.READING) {
                        Text(when (row.readingStatus) {
                            CourseReadingStatus.NOT_STARTED -> "未开始"
                            CourseReadingStatus.READING -> "阅读中"
                            CourseReadingStatus.COMPLETED -> "已完成"
                        }, Modifier.testTag("course-status-${row.target.chapterKey}"),
                            style = UiRoles.text(MaterialTheme.typography.bodySmall), color = UiRoles.accent)
                        val lastGraded = (row.latestQuiz as? CourseLatestQuizProgress.Attempt)?.lastGraded
                        Text(if (lastGraded != null) {
                            "最近已评成绩：${lastGraded.score}/${lastGraded.total} · ${quizTime(lastGraded.submittedAt)}"
                        } else when (val quiz = row.quizProgress) {
                            is CourseQuizProgress.Graded -> if (row.latestQuiz == null) "已评成绩：${quiz.score}/${quiz.total}"
                                else "历史已评成绩：${quiz.score}/${quiz.total}"
                            CourseQuizProgress.NoGradedResult -> "无已评成绩"
                            CourseQuizProgress.Unavailable -> "练习摘要不可用"
                        }, Modifier.testTag("course-quiz-${row.target.chapterKey}"),
                            style = UiRoles.text(MaterialTheme.typography.bodySmall),
                            color = UiRoles.color(UiColorRole.OnSurfaceVariant))
                        row.latestQuiz?.let { latest ->
                            Text(when (latest) {
                                CourseLatestQuizProgress.Unavailable -> "最近作答：不可用"
                                CourseLatestQuizProgress.NoAttempt -> "最近作答：暂无作答"
                                is CourseLatestQuizProgress.Attempt -> when (latest.status) {
                                    CourseLatestQuizStatus.IN_PROGRESS -> "最近作答：进行中 · ${quizTime(latest.startedAt)}"
                                    CourseLatestQuizStatus.PENDING_REVIEW -> "最近作答：待评 · ${quizTime(latest.submittedAt!!)}"
                                    CourseLatestQuizStatus.GRADED -> "最近作答：已评成绩：${latest.score}/${latest.total} · ${quizTime(latest.submittedAt!!)}"
                                }
                            }, Modifier.testTag("course-latest-quiz-${row.target.chapterKey}"),
                                style = UiRoles.text(MaterialTheme.typography.bodySmall),
                                color = UiRoles.color(UiColorRole.OnSurfaceVariant))
                        }
                    }
                    HorizontalDivider(color = UiRoles.color(UiColorRole.OutlineVariant))
                }
                if (state.combinedQuizzes.isNotEmpty()) item(key = "combined-heading") {
                    CourseSectionHeading("组合练习", Modifier.padding(top = 16.dp), "成绩属于对应章节组合")
                }
                items(state.combinedQuizzes, key = { "combined-${it.stableKey}" }) { combined ->
                    Surface(Modifier.fillMaxWidth().padding(bottom = 12.dp), shape = RoundedCornerShape(12.dp),
                        color = UiRoles.surface(UiSurfaceRole.Card)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp).testTag("course-combined-quiz-${combined.stableKey}"),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val titles = combined.chapterKeys.mapNotNull { key -> state.rows.singleOrNull { it.target.chapterKey == key }?.title }
                            Text("组合练习：${titles.joinToString("、")}",
                                style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                                fontWeight = FontWeight.Medium, color = UiRoles.foreground)
                            val latest = combined.latestQuiz
                            Text(when (latest.status) {
                                CourseLatestQuizStatus.IN_PROGRESS -> "组合练习：进行中 · ${quizTime(latest.startedAt)}"
                                CourseLatestQuizStatus.PENDING_REVIEW -> "组合练习：待评 · ${quizTime(latest.submittedAt!!)}"
                                CourseLatestQuizStatus.GRADED -> "组合练习总成绩：${latest.score}/${latest.total}"
                            }, style = UiRoles.text(MaterialTheme.typography.bodySmall), color = UiRoles.foreground)
                            latest.lastGraded?.takeIf { latest.status != CourseLatestQuizStatus.GRADED }?.let {
                                Text("组合练习最近已评总成绩：${it.score}/${it.total}",
                                    style = UiRoles.text(MaterialTheme.typography.bodySmall),
                                    color = UiRoles.color(UiColorRole.OnSurfaceVariant))
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().onSizeChanged { headerHeightPx = it.height }) {
            CourseScreenHeader(state.title.ifBlank { "课程目录" }, onBack, backTag = "course-book-back", backdrop = backdrop) {
                if (!summary.isNullOrBlank()) {
                    UiAction(
                        label = "课程简介",
                        icon = UiIcons.Info,
                        onClick = { showSummary = true },
                        backdrop = backdrop,
                        actionId = "courseBook.info",
                        modifier = Modifier.testTag("course-book-info"),
                    )
                }
            }
        }
    }
    if (showSummary) {
        val summaryTitle = state.title.ifBlank { "课程简介" }
        UiAlertHost(
            title = summaryTitle,
            ownerKey = "course-book-summary",
            onDismissRequest = { _, _ -> showSummary = false },
        ) { interaction, _, _ ->
            Text(
                summaryTitle,
                modifier = Modifier.fillMaxWidth().semantics { heading() },
                style = UiRoles.text(MaterialTheme.typography.titleLarge),
                color = UiRoles.foreground,
                textAlign = TextAlign.Center,
            )
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp)
                    .heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(
                    summary.orEmpty(),
                    style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                    color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                UiAction(
                    label = "关闭",
                    onClick = { showSummary = false },
                    inheritedPane = true,
                    interactionSource = interaction,
                    animatePress = false,
                    foreground = UiRoles.accent,
                    actionId = "courseBook.summary.close",
                )
            }
        }
    }
}

private fun quizTime(timestamp: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
