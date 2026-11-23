package com.example.myapplication.ui.components

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import kotlinx.coroutines.delay

internal data class UiNoticeVisuals(
    val eventId: String,
    val ownerKey: Any,
    override val message: String,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Indefinite,
    val actionExpiresAtMillis: Long? = null,
) : SnackbarVisuals

internal fun canActOnUiNotice(
    eventIsCurrent: Boolean,
    ownerIsCurrent: Boolean,
    expiresAtMillis: Long?,
    nowMillis: Long,
): Boolean = eventIsCurrent && ownerIsCurrent &&
    (expiresAtMillis == null || nowMillis < expiresAtMillis)

private class ShownNoticeIds {
    private val ids = linkedSetOf<String>()

    fun add(eventId: String): Boolean {
        if (!ids.add(eventId)) return false
        if (ids.size > 64) ids.remove(ids.first())
        return true
    }
}

/** SnackbarHost owns visual timeout, including accessibility. A business deadline is independent. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UiNoticeHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    ownerKey: Any? = null,
    backdrop: UiBackdrop? = null,
) {
    val colors = MaterialTheme.colorScheme
    val shownIds = remember(hostState, ownerKey) { ShownNoticeIds() }
    SnackbarHost(hostState, modifier) { notice ->
        val visuals = notice.visuals
        val event = visuals as? UiNoticeVisuals
        val ownerIsCurrent = event == null || event.ownerKey == ownerKey
        val isFirstDisplay = remember(notice, shownIds, ownerIsCurrent) {
            ownerIsCurrent && (event == null || shownIds.add(event.eventId))
        }
        LaunchedEffect(notice, ownerIsCurrent, isFirstDisplay) {
            if ((!ownerIsCurrent || !isFirstDisplay) && hostState.currentSnackbarData === notice) {
                notice.dismiss()
            }
        }
        if (ownerIsCurrent && isFirstDisplay) {
            val deadline = event?.actionExpiresAtMillis
            var expired by remember(notice, deadline) {
                mutableStateOf(deadline != null && SystemClock.elapsedRealtime() >= deadline)
            }
            LaunchedEffect(notice, deadline) {
                if (deadline != null) {
                    delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                    expired = true
                }
            }
            val eventTag = event?.eventId ?: "active"
            UiPane(
                role = UiPaneRole.Banner,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                backdrop = backdrop,
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = visuals.message,
                        color = colors.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.CenterVertically).padding(vertical = 8.dp),
                    )
                    visuals.actionLabel?.let { action ->
                        UiAction(
                            label = action,
                            actionId = "notice.$eventTag.action",
                            inheritedPane = true,
                            enabled = !expired,
                            foreground = UiRoles.accent,
                            onClick = {
                                if (canActOnUiNotice(
                                        hostState.currentSnackbarData === notice, ownerIsCurrent,
                                        deadline, SystemClock.elapsedRealtime(),
                                    )
                                ) {
                                    notice.performAction()
                                }
                            },
                        )
                    }
                    if (visuals.withDismissAction) {
                        UiAction(
                            label = "关闭通知",
                            icon = UiIcons.Close,
                            actionId = "notice.$eventTag.dismiss",
                            inheritedPane = true,
                            onClick = { if (hostState.currentSnackbarData === notice) notice.dismiss() },
                        )
                    }
                }
            }
        }
    }
}
