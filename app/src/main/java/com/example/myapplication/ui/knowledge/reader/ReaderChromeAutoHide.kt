package com.example.myapplication.ui.knowledge.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/** Remembers reader chrome state and binds its idle timer to the current lifecycle owner. */
@Composable
fun rememberReaderChromeController(
    readerKey: Any,
    conditions: ReaderChromeConditions,
    autoHideDelayMillis: Long = 3_000L,
    initialVisible: Boolean = true,
): ReaderChromeController {
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember(readerKey, lifecycleOwner, autoHideDelayMillis) {
        ReaderChromeController(autoHideDelayMillis, initialVisible)
    }

    DisposableEffect(controller, lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = object : LifecycleEventObserver {
            private var closed = false
            private var cleanupComplete = false

            fun closeBinding() {
                if (cleanupComplete) return
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
                if (lifecycle.currentState == Lifecycle.State.DESTROYED) {
                    closeBinding()
                } else {
                    controller.setLifecycleResumed(lifecycle.currentState == Lifecycle.State.RESUMED)
                }
            }

            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                if (closed) return
                when (event) {
                    Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP ->
                        controller.setLifecycleResumed(false)
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

        onDispose { observer.closeBinding() }
    }

    SideEffect { controller.updateConditions(conditions) }

    val request = controller.autoHideRequest
    LaunchedEffect(controller, request) {
        val pendingRequest = request ?: return@LaunchedEffect
        while (controller.autoHideRequest === pendingRequest) {
            delay(controller.remainingDelayMillis(pendingRequest).coerceAtLeast(1L))
            if (controller.consumeAutoHide(pendingRequest)) break
        }
    }

    return controller
}
