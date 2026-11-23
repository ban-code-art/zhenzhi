package com.example.myapplication.ui.knowledge.reader

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One producer and explicit display leases own these pixels; raw bitmap fields are borrowed views. */
class OwnedPdfFrame internal constructor(
    internal val bitmap: Bitmap,
    internal val adjacentBitmap: Bitmap? = null,
    private val allocateDisplayLease: (OwnedPdfFrame) -> DisplayLease = { DisplayLease(it) },
) {
    private val lock = Any()
    private var producerOwned = true
    private var displayOwners = 0
    private var recycled = false

    internal fun acquireDisplay(): DisplayLease? = synchronized(lock) {
        if (recycled) return@synchronized null
        // Constructor/AtomicBoolean allocation can fail; only a fully created lease owns a count.
        val lease = allocateDisplayLease(this)
        displayOwners++
        lease
    }

    /** A remembered display may finish drawing the retired frame before releasing its lease. */
    internal fun retire() = synchronized(lock) {
        producerOwned = false
        recycleIfUnowned()
    }

    private fun releaseDisplay() = synchronized(lock) {
        check(displayOwners > 0) { "PDF display lease released without ownership" }
        displayOwners--
        recycleIfUnowned()
    }

    private fun recycleIfUnowned() {
        if (producerOwned || displayOwners != 0 || recycled) return
        recycled = true
        if (!bitmap.isRecycled) bitmap.recycle()
        adjacentBitmap?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
    }

    internal class DisplayLease(private val frame: OwnedPdfFrame) : AutoCloseable {
        private val released = AtomicBoolean(false)
        val bitmap: Bitmap get() = frame.bitmap
        val adjacentBitmap: Bitmap? get() = frame.adjacentBitmap
        override fun close() { if (released.compareAndSet(false, true)) frame.releaseDisplay() }
    }
}

/** Acquires only inside remember; abandoned compositions release even if no effect ever ran. */
internal class RememberedPdfFrame(frame: OwnedPdfFrame) : RememberObserver {
    private val lease = frame.acquireDisplay()
    val bitmap: Bitmap? get() = lease?.bitmap
    val adjacentBitmap: Bitmap? get() = lease?.adjacentBitmap
    override fun onRemembered() = Unit
    override fun onForgotten() { lease?.close() }
    override fun onAbandoned() { lease?.close() }
}

@Composable
internal fun rememberPdfFrame(frame: OwnedPdfFrame?): RememberedPdfFrame? =
    remember(frame) { frame?.let(::RememberedPdfFrame) }

/** Register the product on the IO side, before prompt cancellation can discard its return value. */
internal suspend fun loadOwnedPdfFrame(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    wrap: (Bitmap) -> OwnedPdfFrame = { OwnedPdfFrame(it) },
    render: () -> Bitmap?,
): OwnedPdfFrame? {
    val rawBitmap = AtomicReference<Bitmap?>()
    val produced = AtomicReference<OwnedPdfFrame?>()
    var delivered = false
    try {
        val frame = withContext(dispatcher) {
            render()?.let { bitmap ->
                // Record pixels before allocating the wrapper or any of its fields.
                rawBitmap.set(bitmap)
                wrap(bitmap).also(produced::set)
            }
        }
        delivered = true
        return frame
    } finally {
        if (!delivered) {
            val frame = produced.get()
            if (frame != null) frame.retire()
            else rawBitmap.get()?.takeIf { !it.isRecycled }?.recycle()
        }
    }
}

/** Allocation and rendering are one ownership boundary, including failures after allocation. */
internal fun renderPdfBitmap(allocate: () -> Bitmap, render: (Bitmap) -> Unit): Bitmap {
    val bitmap = allocate()
    try {
        render(bitmap)
        return bitmap
    } catch (failure: Throwable) {
        if (!bitmap.isRecycled) bitmap.recycle()
        throw failure
    }
}
