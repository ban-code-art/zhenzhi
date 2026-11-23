package com.example.myapplication.ui

import com.example.myapplication.domain.reader.ReaderFeatureReport
import com.example.myapplication.domain.reader.ReaderSelectionEvent
import com.example.myapplication.domain.reader.ReaderSessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

/** One fixture layout owns its streams permanently, including Flow objects collected later. */
internal class GeckoPocLayoutEvents(val token: ReaderSessionToken) {
    private val feature = Broadcast<ReaderFeatureReport>()
    private val selection = Broadcast<ReaderSelectionEvent>()

    fun features(): Flow<ReaderFeatureReport> = feature.events()
    fun selections(): Flow<ReaderSelectionEvent> = selection.events()
    fun publish(value: ReaderFeatureReport) { check(value.token == token); feature.publish(value) }
    fun publish(value: ReaderSelectionEvent) { check(value.token == token); selection.publish(value) }
    fun finish() { feature.finish(); selection.finish() }

    fun snapshot(): JSONObject = JSONObject().put("feature", feature.snapshot()).put("selection", selection.snapshot())

    private class Broadcast<T> {
        private val lock = Any()
        private var ended = false
        private var latest: T? = null
        private val subscribers = mutableSetOf<Channel<T>>()
        private var overflows = 0L

        fun events(): Flow<T> = flow {
            val channel = Channel<T>(64)
            synchronized(lock) {
                if (ended) channel.close() else {
                    latest?.let { check(channel.trySend(it).isSuccess) }
                    subscribers += channel
                }
            }
            try {
                for (value in channel) {
                    currentCoroutineContext().ensureActive()
                    // Channel.close alone drains its buffer. Ended layouts must discard it.
                    if (synchronized(lock) { ended }) break
                    emit(value)
                }
            } finally {
                synchronized(lock) { subscribers -= channel }
                channel.cancel()
            }
        }

        fun publish(value: T) = synchronized(lock) {
            if (ended) return@synchronized
            latest = value
            // Main.immediate collectors can reenter cleanup from trySend/close/cancel.
            for (channel in subscribers.toList()) {
                if (ended) break
                val sent = channel.trySend(value)
                if (sent.isFailure) {
                    subscribers -= channel
                    if (!sent.isClosed) {
                        overflows++
                        channel.cancel(CancellationException("Fixture layout event subscriber exceeded 64 buffered events"))
                    }
                }
            }
        }

        fun finish() = synchronized(lock) {
            if (ended) return@synchronized
            ended = true
            latest = null
            val closing = subscribers.toList()
            subscribers.clear()
            closing.forEach { it.close() }
        }

        fun snapshot(): JSONObject = synchronized(lock) {
            JSONObject().put("ended", ended).put("subscribers", subscribers.size).put("overflows", overflows)
        }
    }
}
