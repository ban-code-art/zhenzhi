package com.example.myapplication.ui.mine

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.example.myapplication.ui.navigation.LocalNavigationPageActive

/** Navigation permission belongs to the page that submitted, independently of its retained VM. */
internal class AccountPageCompletion(
    private val isActive: () -> Boolean,
    private val onComplete: () -> Unit,
) {
    private var revoked = false

    fun revoke() {
        revoked = true
    }

    fun capture(): () -> Unit {
        var consumed = false
        return done@{
            if (consumed) return@done
            // One repository operation may report completion once. A parent back handler can
            // dismiss only the IME, leaving this page available for a later, independent save.
            consumed = true
            if (!revoked && isActive()) onComplete()
        }
    }
}

@Composable
internal fun rememberAccountPageCompletion(onComplete: () -> Unit): AccountPageCompletion {
    val pageActive = LocalNavigationPageActive.current
    val latestActive = rememberUpdatedState(pageActive)
    val latestComplete = rememberUpdatedState(onComplete)
    // A quick re-entry during the outgoing animation gets a fresh permission. The old request
    // retains its revoked object, so becoming active again cannot revive an old completion.
    val completion = remember(pageActive) {
        AccountPageCompletion(
            isActive = { latestActive.value },
            onComplete = { latestComplete.value() },
        )
    }
    SideEffect {
        if (!pageActive) completion.revoke()
    }
    DisposableEffect(completion) {
        onDispose { completion.revoke() }
    }
    return completion
}
