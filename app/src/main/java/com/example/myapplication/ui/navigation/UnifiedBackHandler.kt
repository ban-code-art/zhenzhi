package com.example.myapplication.ui.navigation

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun isBackImeVisible(): Boolean = WindowInsets.ime.getBottom(LocalDensity.current) > 0

/** Top buttons and committed system/gesture back events invoke the same decision executor. */
@Composable
fun unifiedBackRequest(state: BackState, execute: (BackDecision) -> Unit): () -> Unit {
    val keyboard = LocalSoftwareKeyboardController.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = LocalNavigationPageActive.current && lifecycleState == Lifecycle.State.RESUMED
    val preview = LocalPageBackPreview.current
    val settleScope = rememberCoroutineScope()
    val decision = BackDecision.resolve(state)
    val latestDecision by rememberUpdatedState(decision)
    val latestActive by rememberUpdatedState(active)
    val latestExecute by rememberUpdatedState(execute)
    val request: () -> Unit = {
        if (latestActive) {
            val current = latestDecision
            if (current == BackDecision.SystemIme) keyboard?.hide() else latestExecute(current)
        }
    }
    PredictiveBackHandler(enabled = active && decision != BackDecision.SystemIme &&
        decision != BackDecision.ActivityDefault) { progress ->
        var previewRevision: Long? = null
        try {
            progress.collect { event ->
                if (latestActive && latestDecision.isPageExit()) {
                    previewRevision = preview?.follow(event.progress, event.swipeEdge == BackEventCompat.EDGE_RIGHT)
                } else {
                    preview?.reset()
                    previewRevision = null
                }
            }
            val committedRevision = previewRevision.takeIf { latestActive && latestDecision.isPageExit() }
            if (committedRevision != null) preview?.commit(committedRevision) else preview?.reset()
            request()
            if (committedRevision != null) {
                // The host starts the restore together with the actual pop, including asynchronous reader saves.
                // A rejected/failed navigation still releases the held preview instead of stranding the page.
                settleScope.launch {
                    delay(1_000)
                    preview?.restoreUnchangedCommit(committedRevision)
                }
            }
        } catch (cancelled: CancellationException) {
            previewRevision?.let { cancelledRevision ->
                settleScope.launch { preview?.restore(cancelledRevision) }
            }
            throw cancelled
        }
    }
    return request
}

@Composable
internal fun unifiedPageBack(onBack: () -> Unit): () -> Unit =
    unifiedBackRequest(BackState(page = BackPage.CHILD, imeVisible = isBackImeVisible())) { decision ->
        if (decision == BackDecision.PopPage) onBack()
    }

private fun BackDecision.isPageExit(): Boolean =
    this == BackDecision.PopPage || this == BackDecision.ReturnToCaller ||
        this == BackDecision.SaveReadingPositionAndLeave
