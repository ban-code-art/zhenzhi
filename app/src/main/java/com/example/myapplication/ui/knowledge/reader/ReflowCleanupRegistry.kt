package com.example.myapplication.ui.knowledge.reader

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Application-lived failed resource owners. No monitor is held across native/controller callbacks. */
class ReflowCleanupRegistry {
    private class Entry(val action: suspend () -> Boolean)
    private val pending = mutableMapOf<String, Entry>()
    private val failure = MutableStateFlow<String?>(null)
    val cleanupFailure = failure.asStateFlow()

    suspend fun retain(key: String, action: suspend () -> Boolean): Boolean = withContext(NonCancellable) {
        val entry = Entry(action)
        synchronized(pending) { pending[key] = entry; sync() }
        attempt(key, entry)
    }
    fun forget(key: String) { synchronized(pending) { pending.remove(key); sync() } }
    suspend fun retryCleanup(): Boolean = withContext(NonCancellable) {
        val copy = synchronized(pending) { pending.toMap() }
        copy.forEach { (key, entry) -> attempt(key, entry) }
        synchronized(pending) { pending.isEmpty() }
    }
    private suspend fun attempt(key: String, entry: Entry): Boolean {
        val closed = try { entry.action() } catch (_: Exception) { false }
        synchronized(pending) {
            if (closed && pending[key] === entry) pending.remove(key)
            sync()
        }
        return closed
    }
    private fun sync() { failure.value = if (pending.isEmpty()) null else "REFLOW_RUNTIME_CLEANUP_REQUIRED" }
}
