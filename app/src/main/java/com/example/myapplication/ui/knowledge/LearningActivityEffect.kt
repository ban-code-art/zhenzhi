package com.example.myapplication.ui.knowledge

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.myapplication.LearningApplication
import com.example.myapplication.core.AppLog
import com.example.myapplication.data.session.SessionIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** Count only a displayed body in the foreground; flush on pause, navigation and chapter changes. */
@Composable
fun TrackCourseReadingActivity(activityKey: String, readingReady: Boolean) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as LearningApplication).container }
    val identity by container.sessionManager.identity.collectAsState(SessionIdentity(null, -1))
    val owner = LocalLifecycleOwner.current
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val interval = remember(identity, activityKey) { ForegroundReadingInterval(identity) }
    fun flush() {
        val slice = interval.flush(SystemClock.elapsedRealtime()) ?: return
        val captured = interval.identity
        container.applicationScope.launch {
            try {
                container.learningActivityRecorder.recordReading(captured, slice.key, slice.wallStart, slice.duration)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { AppLog.e("learning/reading", "阅读活动保存失败", failure) }
        }
    }
    val active = readingReady && resumed && identity.userId != null && activityKey.isNotBlank()
    DisposableEffect(interval, active) {
        if (active) interval.start(SystemClock.elapsedRealtime(), System.currentTimeMillis())
        onDispose { flush(); interval.stop() }
    }
    LaunchedEffect(interval, active) {
        if (active) while (true) { delay(5_000); flush() }
    }
}

internal class ForegroundReadingInterval(val identity: SessionIdentity = SessionIdentity(null, -1)) {
    data class Slice(val key: String, val wallStart: Long, val duration: Long)
    private var elapsedStart: Long? = null
    private var wallStart = 0L
    fun start(elapsedMs: Long, wallMs: Long) { elapsedStart = elapsedMs; wallStart = wallMs }
    fun stop() { elapsedStart = null }
    fun flush(elapsedMs: Long, wallMs: Long = System.currentTimeMillis()): Slice? {
        val start = elapsedStart ?: return null
        val duration = (elapsedMs - start).coerceIn(0, 24 * 60 * 60 * 1000L)
        if (duration == 0L) return null
        val result = Slice(UUID.randomUUID().toString(), wallStart, duration)
        elapsedStart = elapsedMs
        wallStart = wallMs
        return result
    }
}
