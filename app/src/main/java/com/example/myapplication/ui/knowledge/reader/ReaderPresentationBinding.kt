package com.example.myapplication.ui.knowledge.reader

import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key

/**
 * Binds reader window immersion and toolbar idling to one presentation lifetime.
 *
 * [readerKey] must identify the current reading session, including reopening the same document.
 * The host supplies actual visibility, IME, selection, editor, overlay and accessibility inputs
 * through [conditions]. Changing the window or session disposes both old bindings.
 *
 * Use the returned controller for toolbar visibility and center taps. Toggling its chrome does
 * not change the immersive preference or show the system bars. [initialChromeVisible] applies
 * only when a new toolbar controller is created. [immersiveReassertToken] requests a fresh
 * system-bar assertion without recreating the chrome controller.
 */
@Composable
fun rememberReaderPresentationBinding(
    window: Window,
    readerKey: Any,
    conditions: ReaderChromeConditions,
    autoHideDelayMillis: Long = 3_000L,
    initialChromeVisible: Boolean = true,
    immersiveReassertToken: Any? = null,
): ReaderChromeController = key(window, readerKey) {
    val chrome = rememberReaderChromeController(
        readerKey = readerKey,
        conditions = conditions,
        autoHideDelayMillis = autoHideDelayMillis,
        initialVisible = initialChromeVisible,
    )
    ReaderImmersiveEffect(
        window = window,
        enabled = conditions.immersiveEnabled,
        readerVisible = conditions.readerVisible,
        reassertToken = immersiveReassertToken,
    )
    chrome
}
