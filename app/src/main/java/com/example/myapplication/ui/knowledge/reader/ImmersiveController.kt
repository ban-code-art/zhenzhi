@file:Suppress("DEPRECATION")

package com.example.myapplication.ui.knowledge.reader

import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import androidx.annotation.MainThread
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Owns only status/navigation bar visibility while the reader requests immersion. */
@MainThread
class ImmersiveController(window: Window) : AutoCloseable {
    private var windowReference: WeakReference<Window>? = WeakReference(window)
    private var requested = false
    private var closed = false
    private var applied = false
    private var requestToken: Any? = null
    private var snapshot: WindowSnapshot? = null
    private var watchedDecor: WeakReference<View>? = null
    private var attachListener: AttachListener? = null
    private var layoutListener: LayoutListener? = null
    private var layoutObserver: WeakReference<ViewTreeObserver>? = null

    init {
        checkMainThread()
    }

    val isActive: Boolean
        get() {
            checkMainThread()
            return applied && snapshot != null
        }

    val isAwaitingWindow: Boolean
        get() {
            checkMainThread()
            return requested && !closed && snapshot == null
        }

    /** Returns false while attachment/insets are unavailable or another controller owns the window. */
    fun enter(): Boolean {
        checkMainThread()
        if (closed) return false
        val window = windowReference?.get() ?: return false
        if (!claim(window)) return false
        // A failed restoration must be retried by leave/close, never overwritten by a hidden state.
        if (snapshot != null) {
            // 已应用过：部分设备在窗口失焦（例如打开设置对话框）时会忽略 hide()，
            // 重新拿回窗口时再断言一次，避免"开了沉浸但系统栏还在"。
            val decor = watchedDecor?.get()
            if (decor != null && ViewCompat.isAttachedToWindow(decor)) {
                val insets = ViewCompat.getRootWindowInsets(decor)
                if (insets == null || insets.isVisible(WindowInsetsCompat.Type.statusBars()) ||
                    insets.isVisible(WindowInsetsCompat.Type.navigationBars())) {
                    snapshot?.controller?.hide(
                        WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars(),
                    )
                }
            }
            return applied
        }
        if (!requested) {
            requested = true
            requestToken = Any()
            watchDecor(window.decorView, checkNotNull(requestToken))
        }
        val decor = watchedDecor?.get() ?: return false
        val token = checkNotNull(requestToken)
        if (captureAndApply(decor, token)) return true
        awaitInsets(decor, token)
        return false
    }

    /** Restores the captured state before relinquishing ownership. Safe to repeat. */
    fun leave() {
        checkMainThread()
        requested = false
        requestToken = null
        stopWatching()
        applied = false
        val saved = snapshot
        if (saved != null) {
            val window = checkNotNull(saved.window.get()) { "Captured immersive window is unavailable" }
            check(owns(window)) { "Cannot restore a window owned by another immersive controller" }
            restore(window, saved)
            snapshot = null
            release(window, restored = saved)
        } else {
            windowReference?.get()?.let { release(it, restored = null) }
        }
    }

    override fun close() {
        checkMainThread()
        if (closed && snapshot == null) return
        closed = true
        try {
            leave()
            windowReference?.clear()
            windowReference = null
        } finally {
            stopWatching()
        }
        // If restoration throws, closed stays true and the original snapshot remains for a retry.
    }

    private fun captureAndApply(decor: View, token: Any): Boolean {
        if (!validRequest(decor, token) || snapshot != null) return applied
        if (!ViewCompat.isAttachedToWindow(decor) || decor.windowToken == null) return false
        val rootInsets = ViewCompat.getRootWindowInsets(decor) ?: return false
        if (rootInsets.isConsumed) return false
        val window = windowReference?.get() ?: return false
        val statusVisible = rootInsets.isVisible(WindowInsetsCompat.Type.statusBars())
        val navigationVisible = rootInsets.isVisible(WindowInsetsCompat.Type.navigationBars())
        val owner = owners[window] ?: return false
        // A layout is only a retry signal: actual visibility must confirm a preceding restoration.
        if (owner.expectedStatusVisible?.let { it != statusVisible } == true ||
            owner.expectedNavigationVisible?.let { it != navigationVisible } == true) return false
        // Read raw compatibility flags before any controller getter can have side effects.
        val originalWindowFlags = window.attributes.flags
        val originalSystemUiFlags = decor.systemUiVisibility
        val controller = WindowCompat.getInsetsController(window, decor)
        val saved = WindowSnapshot(
            window = WeakReference(window),
            decor = WeakReference(decor),
            controller = controller,
            statusVisible = statusVisible,
            navigationVisible = navigationVisible,
            lightStatus = controller.isAppearanceLightStatusBars,
            lightNavigation = controller.isAppearanceLightNavigationBars,
            behavior = controller.systemBarsBehavior,
            windowFlags = originalWindowFlags,
            systemUiFlags = originalSystemUiFlags,
        )
        snapshot = saved
        owner.expectedStatusVisible = null
        owner.expectedNavigationVisible = null
        removeLayoutListener()
        return try {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            applied = true
            true
        } catch (failure: RuntimeException) {
            try {
                leave()
            } catch (restorationFailure: RuntimeException) {
                if (restorationFailure !== failure) failure.addSuppressed(restorationFailure)
            }
            throw failure
        }
    }

    private fun awaitInsets(decor: View, token: Any) {
        if (!validRequest(decor, token) || snapshot != null) return
        if (!ViewCompat.isAttachedToWindow(decor)) return
        installLayoutListener(decor, token)
        ViewCompat.requestApplyInsets(decor)
    }

    private fun onAttached(decor: View, token: Any) {
        checkMainThread()
        if (!validRequest(decor, token) || snapshot != null) return
        // Attachment alone can still expose stale root insets. Observe a requested traversal first.
        awaitInsets(decor, token)
    }

    private fun onDetached(decor: View, token: Any) {
        checkMainThread()
        if (!validRequest(decor, token)) return
        removeLayoutListener()
        applied = false
        val saved = snapshot ?: return
        val window = checkNotNull(saved.window.get()) { "Captured immersive window is unavailable" }
        check(owns(window)) { "Cannot restore a window owned by another immersive controller" }
        // Reuse the attached-time controller; constructing a new detached controller is unsafe.
        restore(window, saved)
        snapshot = null
        markRestoredVisibility(window, saved)
    }

    private fun onLayout(listener: LayoutListener, token: Any) {
        checkMainThread()
        if (layoutListener !== listener) return
        val decor = watchedDecor?.get() ?: return
        if (!validRequest(decor, token) || !ViewCompat.isAttachedToWindow(decor)) return
        captureAndApply(decor, token)
    }

    private fun watchDecor(decor: View, token: Any) {
        stopWatching()
        watchedDecor = WeakReference(decor)
        attachListener = AttachListener(this, token).also { decor.addOnAttachStateChangeListener(it) }
    }

    private fun installLayoutListener(decor: View, token: Any) {
        if (layoutListener != null && layoutObserver?.get()?.isAlive == true) return
        removeLayoutListener()
        val observer = decor.viewTreeObserver
        if (!observer.isAlive) return
        layoutListener = LayoutListener(this, token).also { observer.addOnGlobalLayoutListener(it) }
        layoutObserver = WeakReference(observer)
    }

    private fun removeLayoutListener() {
        val listener = layoutListener
        val observer = layoutObserver?.get()
        layoutListener = null
        layoutObserver = null
        if (listener != null && observer?.isAlive == true) observer.removeOnGlobalLayoutListener(listener)
    }

    private fun stopWatching() {
        removeLayoutListener()
        val listener = attachListener
        attachListener = null
        if (listener != null) watchedDecor?.get()?.removeOnAttachStateChangeListener(listener)
        watchedDecor = null
    }

    private fun validRequest(decor: View, token: Any): Boolean {
        val window = windowReference?.get() ?: return false
        return requested && !closed && requestToken === token && watchedDecor?.get() === decor && owns(window)
    }

    private fun restore(window: Window, saved: WindowSnapshot) {
        var firstFailure: RuntimeException? = null
        fun restorePart(action: () -> Unit) {
            try {
                action()
            } catch (failure: RuntimeException) {
                val first = firstFailure
                if (first == null) firstFailure = failure else if (first !== failure) first.addSuppressed(failure)
            }
        }
        restorePart { saved.controller.isAppearanceLightStatusBars = saved.lightStatus }
        restorePart { saved.controller.isAppearanceLightNavigationBars = saved.lightNavigation }
        restorePart { saved.controller.systemBarsBehavior = saved.behavior }
        restorePart { restoreVisibility(saved.controller, WindowInsetsCompat.Type.statusBars(), saved.statusVisible) }
        restorePart { restoreVisibility(saved.controller, WindowInsetsCompat.Type.navigationBars(), saved.navigationVisible) }
        // Compatibility setters may alter these flags; merge only their bits into the current state.
        restorePart { window.setFlags(saved.windowFlags, COMPAT_WINDOW_FLAGS) }
        restorePart {
            val decor = saved.decor.get() ?: return@restorePart
            decor.systemUiVisibility = (decor.systemUiVisibility and COMPAT_SYSTEM_UI_FLAGS.inv()) or
                (saved.systemUiFlags and COMPAT_SYSTEM_UI_FLAGS)
        }
        firstFailure?.let { throw it }
    }

    private fun restoreVisibility(controller: WindowInsetsControllerCompat, type: Int, visible: Boolean) {
        if (visible) controller.show(type) else controller.hide(type)
    }

    private fun claim(window: Window): Boolean {
        val entry = owners.getOrPut(window) { WindowOwner() }
        val owner = entry.owner?.get()
        if (owner != null && owner !== this) return false
        entry.owner = WeakReference(this)
        return true
    }

    private fun owns(window: Window): Boolean = owners[window]?.owner?.get() === this

    private fun markRestoredVisibility(window: Window, saved: WindowSnapshot) {
        if (!owns(window)) return
        owners[window]?.let {
            it.expectedStatusVisible = saved.statusVisible
            it.expectedNavigationVisible = saved.navigationVisible
        }
    }

    private fun release(window: Window, restored: WindowSnapshot?) {
        val entry = owners[window] ?: return
        if (entry.owner?.get() !== this) return
        if (restored != null) markRestoredVisibility(window, restored)
        entry.owner = null
    }

    private data class WindowSnapshot(
        val window: WeakReference<Window>,
        val decor: WeakReference<View>,
        val controller: WindowInsetsControllerCompat,
        val statusVisible: Boolean,
        val navigationVisible: Boolean,
        val lightStatus: Boolean,
        val lightNavigation: Boolean,
        val behavior: Int,
        val windowFlags: Int,
        val systemUiFlags: Int,
    )

    private class WindowOwner {
        var owner: WeakReference<ImmersiveController>? = null
        // Weak-map values never hold the Window, its decor, or a compat controller.
        var expectedStatusVisible: Boolean? = null
        var expectedNavigationVisible: Boolean? = null
    }

    private class AttachListener(owner: ImmersiveController, private val token: Any) : View.OnAttachStateChangeListener {
        private val owner = WeakReference(owner)
        override fun onViewAttachedToWindow(view: View) { owner.get()?.onAttached(view, token) }
        override fun onViewDetachedFromWindow(view: View) { owner.get()?.onDetached(view, token) }
    }

    private class LayoutListener(owner: ImmersiveController, private val token: Any) : ViewTreeObserver.OnGlobalLayoutListener {
        private val owner = WeakReference(owner)
        override fun onGlobalLayout() { owner.get()?.onLayout(this, token) }
    }

    private companion object {
        val owners = WeakHashMap<Window, WindowOwner>()
        const val COMPAT_WINDOW_FLAGS = WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
        const val COMPAT_SYSTEM_UI_FLAGS = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR or
            View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        fun checkMainThread() {
            check(Looper.myLooper() === Looper.getMainLooper()) { "ImmersiveController requires the main thread" }
        }
    }
}
