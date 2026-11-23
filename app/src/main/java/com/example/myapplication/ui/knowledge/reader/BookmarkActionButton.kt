package com.example.myapplication.ui.knowledge.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiBackdrop
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles

private val BookmarkGreen = Color(0xFF22863A)

/** Candidate choice is explicit; no row is removed by opening this list. */
@Composable
fun BookmarkCandidateChoices(
    state: BookmarkActionState,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    inheritedPane: Boolean = true,
) {
    if (state.resolution !is BookmarkActionResolution.Ambiguous) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "选择当前位置的书签",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.candidates.forEach { bookmark ->
            val label = bookmark.preview.ifBlank { "书签" } + " · " + when (val at = bookmark.locator) {
                is com.example.myapplication.domain.reader.ReaderLocator.Reflow -> "${at.canonicalStart}–${at.canonicalEnd}"
                is com.example.myapplication.domain.reader.ReaderLocator.Fixed -> "第 ${at.pageIndex + 1} 页"
                else -> bookmark.id
            }
            UiAction(
                label = label,
                onClick = { onSelect(bookmark.id) },
                enabled = !state.showsProgress,
                inheritedPane = inheritedPane,
                actionId = "bookmark.candidate.${bookmark.id}",
            )
        }
    }
}

/** Top action. The host launches controller.toggleBookmark()/retry calls in its own UI scope. */
@Composable
internal fun BookmarkToggleButton(
    state: BookmarkActionState,
    onToggle: () -> Unit,
    onRetryWrite: () -> Unit,
    onRetryObservation: () -> Unit,
    modifier: Modifier = Modifier,
    showMessage: Boolean = true,
    backdrop: UiBackdrop? = null,
    inheritedPane: Boolean = false,
) {
    val intent = BookmarkActionIntent.TOGGLE
    val retryObservation = state.canRetryObservation
    val retryWrite = state.canRetryWrite(intent)
    val description = when {
        retryObservation -> "重新读取书签状态"
        retryWrite -> "重试书签操作"
        state.isBookmarked -> "移除当前位置书签"
        else -> "添加当前位置书签"
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        UiAction(
            label = description,
            icon = if (state.isBookmarked) UiIcons.BookmarkFilled else UiIcons.Bookmark,
            onClick = when {
                retryObservation -> onRetryObservation
                retryWrite -> onRetryWrite
                else -> onToggle
            },
            enabled = retryObservation || retryWrite || state.canInvoke(intent),
            busy = state.showsProgress,
            backdrop = backdrop,
            inheritedPane = inheritedPane,
            foreground = if (state.isBookmarked) BookmarkGreen else UiRoles.foreground,
            actionId = "bookmark.toggle",
            modifier = Modifier.bookmarkSemantics(state, description),
        )
        if (showMessage) BookmarkActionMessage(state, intent)
    }
}

/** Menu action. Even a green/current match calls ensure, preserving add-only semantics. */
@Composable
internal fun BookmarkEnsureButton(
    state: BookmarkActionState,
    onEnsure: () -> Unit,
    onRetryWrite: () -> Unit,
    onRetryObservation: () -> Unit,
    modifier: Modifier = Modifier,
    showMessage: Boolean = true,
    backdrop: UiBackdrop? = null,
    inheritedPane: Boolean = false,
) {
    val intent = BookmarkActionIntent.ENSURE_ADDED
    val retryObservation = state.canRetryObservation
    val retryWrite = state.canRetryWrite(intent)
    val label = when {
        retryObservation -> "重新读取书签"
        retryWrite -> "重试加入书签"
        state.write == BookmarkWritePhase.SAVING -> "正在保存书签"
        state.write == BookmarkWritePhase.SYNCHRONIZING -> "正在确认书签"
        state.observation == BookmarkObservationPhase.LOADING -> "正在读取书签"
        state.isBookmarked -> "已加入书签"
        else -> "加入书签"
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        UiAction(
            label = label,
            onClick = when {
                retryObservation -> onRetryObservation
                retryWrite -> onRetryWrite
                else -> onEnsure
            },
            enabled = retryObservation || retryWrite || state.canInvoke(intent),
            busy = state.showsProgress,
            backdrop = backdrop,
            inheritedPane = inheritedPane,
            foreground = if (state.isBookmarked) BookmarkGreen else null,
            actionId = "bookmark.ensure",
            modifier = Modifier.bookmarkSemantics(state, label),
        )
        if (showMessage) BookmarkActionMessage(state, intent)
    }
}

private fun Modifier.bookmarkSemantics(state: BookmarkActionState, description: String): Modifier = semantics {
    role = Role.Button
    selected = state.isBookmarked
    contentDescription = description
    stateDescription = bookmarkStateDescription(state)
}

@Composable
private fun BookmarkActionMessage(state: BookmarkActionState, intent: BookmarkActionIntent) {
    val message = bookmarkMessage(state, intent) ?: return
    Text(text = message, style = MaterialTheme.typography.labelSmall,
        color = if (state.actionFailure != null || state.observationFailure != null)
            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun bookmarkStateDescription(state: BookmarkActionState): String = when {
    state.write == BookmarkWritePhase.SAVING -> "正在保存书签"
    state.observation == BookmarkObservationPhase.LOADING && state.write == BookmarkWritePhase.SYNCHRONIZING -> "正在确认书签状态"
    state.observation == BookmarkObservationPhase.LOADING -> "正在读取书签状态"
    state.observationFailure != null -> failureMessage(state.observationFailure)
    state.actionFailure != null -> failureMessage(state.actionFailure)
    state.resolution is BookmarkActionResolution.Ambiguous -> "当前位置有多条书签，尚待选择"
    state.resolution is BookmarkActionResolution.Unavailable -> unavailableMessage(state.resolution.reason)
    state.observation == BookmarkObservationPhase.CLOSED -> "书签工具已关闭"
    state.input?.writable == false -> if (state.isBookmarked) "已加入书签，当前内容只读" else "未加入书签，当前内容只读"
    state.isBookmarked -> "当前位置已加入书签"
    state.observation == BookmarkObservationPhase.READY -> "当前位置未加入书签"
    else -> "尚未获得当前位置"
}

private fun bookmarkMessage(state: BookmarkActionState, intent: BookmarkActionIntent): String? = when {
    state.showsProgress -> null
    state.observationFailure != null -> failureMessage(state.observationFailure)
    state.pendingIntent != null && state.pendingIntent != intent -> "请先完成上次书签操作"
    state.actionFailure != null -> failureMessage(state.actionFailure)
    state.resolution is BookmarkActionResolution.Ambiguous -> "当前位置有多条书签，请先选择处理"
    state.resolution is BookmarkActionResolution.Unavailable -> unavailableMessage(state.resolution.reason)
    state.input?.writable == false -> "当前内容只读"
    else -> null
}

private fun failureMessage(failure: BookmarkActionFailure): String = when (failure.kind) {
    BookmarkActionFailureKind.INPUT -> "当前位置无效，暂时无法使用书签"
    BookmarkActionFailureKind.IDENTITY -> "阅读会话已变化，请重新读取当前位置"
    BookmarkActionFailureKind.OWNERSHIP -> "阅读位置已变化，请重新读取当前位置"
    BookmarkActionFailureKind.OBSERVATION -> "书签状态读取失败，请重试"
    BookmarkActionFailureKind.RESOLUTION -> "当前位置无法确认，请重试读取书签"
    BookmarkActionFailureKind.CONFLICT -> if (failure.retryable) "书签状态已变化，请重试" else "书签操作冲突，请重新读取当前位置"
    BookmarkActionFailureKind.REJECTED -> if (failure.retryable) "暂时无法保存书签，请重试" else "当前内容暂时无法保存书签"
    BookmarkActionFailureKind.FAILED -> when {
        !failure.retryable -> "书签操作失败，请重新读取当前位置"
        failure.outcomeUnknown -> "书签操作结果尚未确认，请重试"
        else -> "书签操作失败，请重试"
    }
    BookmarkActionFailureKind.CANCELLED -> "书签操作已中断，请重试确认结果"
    BookmarkActionFailureKind.RECEIPT -> "书签结果尚未确认，请重试"
}

private fun unavailableMessage(reason: String): String = when (reason) {
    "INVALID_IDENTITY" -> "阅读会话无效"
    "NO_POSITION" -> "尚未获得当前位置"
    "INVALID_SELECTION" -> "选区位置无法确认"
    "UNPROVEN_READING_ORDER" -> "多段选区的阅读顺序尚未确认"
    "INVALID_POSITION" -> "当前位置无法用于书签"
    else -> "当前位置暂时无法用于书签"
}
