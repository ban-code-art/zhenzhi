package com.example.myapplication.ui.knowledge.reader

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import com.example.myapplication.domain.reader.ReaderTurnDirection
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class WebViewReflowHostSlot(val id: String, val view: WebView)
data class WebViewReflowHostState(
    val slots: List<WebViewReflowHostSlot> = emptyList(),
    val activeSurfaceId: String? = null,
)

/** Only owns attachment/presentation. Native destruction remains the surface owner's responsibility. */
class WebViewReflowRuntimeHost(context: Context) : ReflowRuntimeHost {
    private val context = context.applicationContext
    private val mutableState = MutableStateFlow(WebViewReflowHostState())
    private val selectionPorts = linkedMapOf<String, ReflowSelectionSurfacePort>()
    val state = mutableState.asStateFlow()
    private var inputBlocked = true
    private var systemEdgePx = 0f
    private var inputEpoch = 0L
    private var tap: (ReaderTapAction) -> Unit = {}
    private var swipe: (ReaderTurnDirection) -> Unit = {}

    internal fun updateInput(blocked: Boolean, edgePx: Float, onTap: (ReaderTapAction) -> Unit,
        onSwipe: (ReaderTurnDirection) -> Unit) {
        if (inputBlocked != blocked || systemEdgePx != edgePx) inputEpoch++
        inputBlocked = blocked
        systemEdgePx = edgePx
        tap = onTap
        swipe = onSwipe
    }
    internal fun clearInput() {
        inputEpoch++
        inputBlocked = true
        tap = {}
        swipe = {}
    }

    override suspend fun createSurface(): HostedReflowSurface = withContext(Dispatchers.Main.immediate) {
        val surface = WebViewReflowReaderSurface(context)
        val id = UUID.randomUUID().toString()
        surface.view.alpha = 0f
        observeInput(id, surface.view)
        selectionPorts[id] = surface.selectionPort
        mutableState.value = state.value.copy(slots = state.value.slots + WebViewReflowHostSlot(id, surface.view))
        HostedReflowSurface(id, surface).also { it.selectionPort = surface.selectionPort }
    }

    override suspend fun showSurface(id: String) = withContext(Dispatchers.Main.immediate) {
        try {
            require(state.value.slots.any { it.id == id }) { "REFLOW_HOST_SLOT_MISSING" }
            val view = state.value.slots.first { it.id == id }.view
            // Capture selection before WebView's default touch handling can collapse it.
            withTimeout(5_000) { suspendCancellableCoroutine<Unit> { continuation ->
                view.evaluateJavascript("""(function(){
                    if(window.__isolatedReaderInputInstalled)return;
                    window.__isolatedReaderInputInstalled=true;
                    function selected(){var s=getSelection();return !!(s&&!s.isCollapsed);}
                    document.addEventListener('touchstart',function(){window.__isolatedReaderSelected=selected();},{passive:true,capture:true});
                    document.addEventListener('touchmove',function(){window.__isolatedReaderSelected=window.__isolatedReaderSelected||selected();},{passive:true,capture:true});
                    document.addEventListener('touchend',function(){window.__isolatedReaderSelected=window.__isolatedReaderSelected||selected();},{passive:true,capture:true});
                })()""".trimIndent()) { if (continuation.isActive) continuation.resume(Unit) }
            } }
            if (state.value.activeSurfaceId != id) inputEpoch++
            state.value.slots.forEach { it.view.alpha = if (it.id == id) 1f else 0f }
            mutableState.value = state.value.copy(activeSurfaceId = id)
            selectionPorts.filterKeys { it != id }.values.forEach { it.setPresented(false) }
            selectionPorts[id]?.setPresented(true)
            Unit
        } catch (error: Exception) {
            withContext(NonCancellable) { runCatching { hidePresentation() } }
            throw error
        }
    }

    override suspend fun releaseSurface(id: String) = withContext(Dispatchers.Main.immediate) {
        val current = state.value
        current.slots.firstOrNull { it.id == id }?.view?.setOnTouchListener(null)
        inputEpoch++
        mutableState.value = current.copy(slots = current.slots.filterNot { it.id == id },
            activeSurfaceId = current.activeSurfaceId.takeUnless { it == id })
        selectionPorts.remove(id)?.setPresented(false)
        Unit
    }

    override suspend fun hideAll() = withContext(Dispatchers.Main.immediate) {
        hidePresentation()
    }

    private suspend fun hidePresentation() {
        inputEpoch++
        state.value.slots.forEach { it.view.alpha = 0f }
        mutableState.value = state.value.copy(activeSurfaceId = null)
        var failure: Exception? = null
        selectionPorts.values.toList().forEach { port ->
            try { port.setPresented(false) } catch (error: Exception) { failure = error }
        }
        failure?.let { throw it }
    }

    /** Native events remain WebView-owned. DOM checks veto controls, links and native selection. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun observeInput(id: String, view: WebView) {
        val config = ViewConfiguration.get(context)
        val arbiter = ReaderInputArbiter(config.scaledTouchSlop.toFloat(), ViewConfiguration.getLongPressTimeout().toLong())
        val swipes = ReaderSwipeArbiter(config.scaledTouchSlop.toFloat(), ViewConfiguration.getLongPressTimeout().toLong())
        class Gesture(val x: Float, val y: Float, val time: Long, val epoch: Long,
            val width: Int, val height: Int, val scale: Float, val edge: Boolean) {
            var blocked = false
            var domBlocked: Boolean? = null
            var pointerCount = 1
            var movement = 0f
            var finger = true
        }
        var gesture: Gesture? = null
        fun allowed(g: Gesture) = !inputBlocked && g.epoch == inputEpoch && state.value.activeSurfaceId == id &&
            view.width == g.width && view.height == g.height && view.scale == g.scale
        fun hitTest(x: Float, y: Float, width: Int, includeGestureSelection: Boolean = false, callback: (Boolean) -> Unit) {
            if (!x.isFinite() || !y.isFinite() || width <= 0) { callback(true); return }
            val script = """(function(){
                var s=window.getSelection(),t=document.elementFromPoint($x*innerWidth/$width,$y*innerWidth/$width);
                var controls='a,button,input,textarea,select,option,label,summary,audio,video,iframe,canvas,[onclick],[role="button"],[role="link"],[role="slider"],[role="checkbox"],[role="textbox"],[role="switch"],[tabindex],[data-reader-highlight],[contenteditable]:not([contenteditable="false"])';
                return !t||!!(s&&!s.isCollapsed)||!!t.closest(controls)||($includeGestureSelection&&!!window.__isolatedReaderSelected);
            })()""".trimIndent()
            try { view.evaluateJavascript(script) { callback(it != "false") } }
            catch (_: Exception) { callback(true) }
        }
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val g = Gesture(event.x, event.y, event.eventTime, inputEpoch, view.width, view.height, view.scale,
                    event.x < systemEdgePx || event.x >= view.width - systemEdgePx)
                g.blocked = !allowed(g)
                gesture = g
                hitTest(g.x, g.y, g.width) { g.domBlocked = it }
            }
            val g = gesture
            if (g != null) {
                g.pointerCount = maxOf(g.pointerCount, event.pointerCount)
                g.finger = g.finger && (0 until event.pointerCount).all { event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER }
                g.movement = maxOf(g.movement, kotlin.math.hypot(event.x - g.x, event.y - g.y))
                for (index in 0 until event.historySize) g.movement = maxOf(g.movement,
                    kotlin.math.hypot(event.getHistoricalX(index) - g.x, event.getHistoricalY(index) - g.y))
                g.blocked = g.blocked || !allowed(g)
                when (event.actionMasked) {
                    MotionEvent.ACTION_CANCEL -> gesture = null
                    MotionEvent.ACTION_UP -> {
                        gesture = null
                        val dx = event.x - g.x
                        val dy = event.y - g.y
                        val duration = event.eventTime - g.time
                        val endX = event.x
                        val endY = event.y
                        hitTest(endX, endY, g.width, true) { blocked ->
                            if (!allowed(g) || g.blocked || g.domBlocked != false || blocked) return@hitTest
                            val sample = ReaderTapSample(g.x, g.y, g.width.toFloat(), g.height.toFloat(), duration,
                                g.movement, g.pointerCount, g.finger, startedInSystemEdge = g.edge,
                                cancelled = endX < 0 || endX >= g.width || endY < 0 || endY >= g.height)
                            val action = arbiter.resolve(sample)
                            if (action != null) tap(action)
                            else swipes.resolve(ReaderSwipeSample(sample, dx, dy))?.let(swipe)
                        }
                    }
                }
            }
            false
        }
    }
}
