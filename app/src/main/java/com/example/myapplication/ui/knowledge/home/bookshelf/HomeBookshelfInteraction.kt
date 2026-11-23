package com.example.myapplication.ui.knowledge.home.bookshelf

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.sign

@Stable
internal class ShelfInteraction(initial: List<HomeShelfEntry>, rememberedKey: String?) {
    enum class Phase { Idle, Press, Drag, Settle, Suspended }

    var entries by mutableStateOf(initial)
        private set
    var q by mutableFloatStateOf(preferredIndex(initial, rememberedKey).toFloat())
        private set
    var selectedKey by mutableStateOf(initial.getOrNull(q.toInt())?.key)
        private set
    var phase by mutableStateOf(Phase.Idle)
        private set
    var target by mutableStateOf<Int?>(null)
        private set
    var degraded by mutableStateOf(false)
        private set

    private var enabled = true
    private var geometry: ShelfMetrics? = null
    private var rtl = false
    private var velocity = 0f
    private var tieDirection = 0
    private var generation = 0L
    private var job: Job? = null

    val visualIndex: Int
        get() = nearestShelfIndex(q, entries.size, selected = entries.indexOfFirst { it.key == selectedKey })

    val commandIndex by derivedStateOf { target ?: visualIndex }

    val stableIndex: Int?
        get() {
            if (!enabled || phase != Phase.Idle) return null
            return visualIndex.takeIf { q == it.toFloat() && it in entries.indices }
        }

    fun synchronize(
        next: List<HomeShelfEntry>,
        inputEnabled: Boolean,
        nextGeometry: ShelfMetrics?,
        isRtl: Boolean,
        rememberedKey: String?,
        scope: CoroutineScope,
    ) {
        val geometryChanged = geometry !== nextGeometry || rtl != isRtl
        if (geometryChanged || enabled != inputEnabled) {
            invalidate()
            phase = if (inputEnabled) Phase.Idle else Phase.Suspended
            geometry = nextGeometry
            enabled = inputEnabled
            rtl = isRtl
        }

        val keysChanged = next.map { it.key } != entries.map { it.key }
        val nextByKey = next.associateBy { it.key }
        val vanished = keysChanged && entries.any { it.key !in nextByKey }
        val lostAccess = entries.any { entry -> entry.canOpen && nextByKey[entry.key]?.canOpen == false }
        if (vanished || lostAccess || (keysChanged && phase in listOf(Phase.Idle, Phase.Suspended))) {
            replaceEntries(next, rememberedKey)
        } else if (!keysChanged && entries != next) {
            entries = next
        }

        if (enabled && phase == Phase.Idle) {
            val index = visualIndex
            if (q != index.toFloat()) {
                select(index, scope)
            } else if (entries.getOrNull(index)?.key != selectedKey) {
                settle(index)
            }
        }
    }

    fun beginPress(): Long {
        invalidate()
        phase = Phase.Press
        return generation
    }

    fun owns(token: Long): Boolean = enabled && token == generation

    fun drag(token: Long, deltaQ: Float) {
        if (!owns(token)) return
        phase = Phase.Drag
        if (deltaQ != 0f) tieDirection = deltaQ.sign.toInt()
        q = (q + deltaQ).coerceIn(0f, (entries.size - 1).coerceAtLeast(0).toFloat())
    }

    fun release(token: Long, velocityQ: Float, scope: CoroutineScope) {
        if (!owns(token)) return
        val plan = ShelfMotionPlan.release(q, velocityQ, entries.size, tieDirection, entries.indexOfFirst { it.key == selectedKey })
        play(plan, scope)
    }

    fun endPress(token: Long, tappedIndex: Int?, scope: CoroutineScope) {
        if (!owns(token)) return
        if (tappedIndex != null && tappedIndex in entries.indices) {
            select(tappedIndex, scope)
        } else {
            select(visualIndex, scope)
        }
    }

    fun select(index: Int, scope: CoroutineScope): Boolean {
        if (!enabled || index !in entries.indices) return false
        play(ShelfMotionPlan.toTarget(q, velocity, index, entries.size), scope)
        return true
    }

    fun dispose() {
        invalidate()
    }

    private fun replaceEntries(next: List<HomeShelfEntry>, rememberedKey: String?) {
        val oldIndex = visualIndex
        val anchorKey = entries.getOrNull(oldIndex)?.key
        val anchor = next.indexOfFirst { it.key == anchorKey }
        val fraction = (q - oldIndex).coerceIn(-.5f, .5f)
        val selected = next.indexOfFirst { it.key == selectedKey }
        val fallback = if (selected >= 0) selected else if (anchor >= 0) anchor else {
            closestSurvivingIndex(entries, oldIndex, next) ?: preferredIndex(next, rememberedKey)
        }
        val wasActive = phase == Phase.Press || phase == Phase.Drag || phase == Phase.Settle
        invalidate()
        entries = next
        val index = if (wasActive && anchor >= 0) anchor else fallback
        q = if (wasActive && anchor >= 0) {
            (anchor + fraction).coerceIn(0f, (next.size - 1).coerceAtLeast(0).toFloat())
        } else {
            index.toFloat()
        }
        selectedKey = next.getOrNull(fallback)?.key
        phase = if (enabled) Phase.Idle else Phase.Suspended
    }

    private fun play(plan: ShelfMotionPlan, scope: CoroutineScope) {
        invalidate()
        val pitch = geometry?.pitch
        val scale = scope.coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
        val first = plan.sample(0f)
        if (pitch == null || scale <= 0f || degraded || plan.settled(first, pitch)) {
            settle(plan.target)
            return
        }
        val toleranceSample = plan.sample(ShelfMaxMotionTime)
        if (!plan.settled(toleranceSample, pitch)) {
            degraded = true
            settle(plan.target)
            return
        }
        phase = Phase.Settle
        target = plan.target
        velocity = first.velocity
        val token = generation
        job = scope.launch {
            val origin = withFrameNanos { it }
            while (owns(token)) {
                val now = withFrameNanos { it }
                val actualScale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
                if (!owns(token)) break
                val time = if (actualScale > 0f) (now - origin) / 1_000_000_000f / actualScale else ShelfMaxMotionTime
                val sample = plan.sample(time.coerceIn(0f, ShelfMaxMotionTime))
                q = sample.q
                velocity = sample.velocity
                if (plan.settled(sample, pitch) || time >= ShelfMaxMotionTime) {
                    settle(plan.target)
                    break
                }
            }
        }
    }

    private fun settle(index: Int) {
        q = index.toFloat()
        velocity = 0f
        phase = Phase.Idle
        target = null
        selectedKey = entries.getOrNull(index)?.key
    }

    private fun invalidate() {
        generation++
        job?.cancel()
        job = null
        velocity = 0f
        tieDirection = 0
        target = null
    }

    private companion object {
        fun preferredIndex(entries: List<HomeShelfEntry>, rememberedKey: String?): Int =
            entries.indexOfFirst { it.key == rememberedKey }.takeIf { it >= 0 }
                ?: entries.indexOfFirst { it.canOpen }.coerceAtLeast(0)

        fun closestSurvivingIndex(
            old: List<HomeShelfEntry>,
            anchor: Int,
            next: List<HomeShelfEntry>,
        ): Int? {
            val indexes = next.mapIndexed { index, entry -> entry.key to index }.toMap()
            for (distance in 1 until old.size) {
                indexes[old.getOrNull(anchor - distance)?.key]?.let { return it }
                indexes[old.getOrNull(anchor + distance)?.key]?.let { return it }
            }
            return null
        }
    }
}
