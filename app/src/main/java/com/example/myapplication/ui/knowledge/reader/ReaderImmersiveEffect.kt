package com.example.myapplication.ui.knowledge.reader

import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Binds reader immersion to the visible reader and its resumed lifecycle owner.
 *
 * [reassertToken] lets the host ask for a fresh assertion after a dialog window (e.g. the settings
 * sheet) temporarily took focus: some OEM builds ignore hide() on an unfocused window.
 */
@Composable
fun ReaderImmersiveEffect(window: Window, enabled: Boolean, readerVisible: Boolean, reassertToken: Any? = null) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var activeController by remember { mutableStateOf<ImmersiveController?>(null) }
    val controllerForReassert = activeController

    DisposableEffect(window, lifecycleOwner, enabled, readerVisible) {
        val lifecycle = lifecycleOwner.lifecycle
        val controller = ImmersiveController(window)
        activeController = controller
        val observer = object : LifecycleEventObserver {
            private var closed = false
            private var cleanupComplete = false

            fun closeBinding() {
                if (cleanupComplete) return
                // Stop callbacks immediately while allowing failed cleanup to be retried.
                closed = true
                var firstFailure: Throwable? = null
                try {
                    lifecycle.removeObserver(this)
                } catch (failure: Throwable) {
                    firstFailure = failure
                }
                try {
                    controller.close()
                } catch (failure: Throwable) {
                    val previousFailure = firstFailure
                    if (previousFailure == null) {
                        firstFailure = failure
                    } else if (previousFailure !== failure) {
                        previousFailure.addSuppressed(failure)
                    }
                }
                val failure = firstFailure
                if (failure != null) throw failure
                cleanupComplete = true
            }

            fun synchronize() {
                if (closed) return
                when {
                    lifecycle.currentState == Lifecycle.State.DESTROYED -> closeBinding()
                    enabled && readerVisible && lifecycle.currentState == Lifecycle.State.RESUMED ->
                        controller.enter()
                    else -> controller.leave()
                }
            }

            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                if (closed) return
                when (event) {
                    Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> controller.leave()
                    Lifecycle.Event.ON_DESTROY -> closeBinding()
                    else -> synchronize()
                }
            }
        }

        try {
            if (lifecycle.currentState != Lifecycle.State.DESTROYED) {
                lifecycle.addObserver(observer)
            }
            observer.synchronize()
        } catch (failure: Throwable) {
            try {
                observer.closeBinding()
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }

        onDispose {
            try {
                observer.closeBinding()
            } finally {
                if (activeController === controller) activeController = null
            }
        }
    }

    // 对话框窗口关闭后重新断言：控制器已持有原窗口快照，enter() 只补一次 hide()。
    LaunchedEffect(controllerForReassert, reassertToken, enabled, readerVisible) {
        val controller = controllerForReassert ?: return@LaunchedEffect
        if (enabled && readerVisible) controller.enter()
    }
}
