package com.example.myapplication.ui.knowledge.reader

import androidx.lifecycle.SavedStateHandle
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.domain.reader.*
import com.example.myapplication.ui.navigation.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class CourseReaderRuntimeCheckpoint(
    val bookId: String,
    val sequenceVersion: String,
    val chapterKey: String,
    val locator: ReaderLocator,
    val preferences: ReaderLayoutPreferences,
)

/** Caller reloads the real course scope before restore/save; a fixed document route cannot stand in for it. */
interface CourseReaderRuntimeRecovery {
    suspend fun restore(identity: SessionIdentity, sequence: ReadingSequence): OperationResult<CourseReaderRuntimeCheckpoint?>
    suspend fun save(identity: SessionIdentity, sequence: ReadingSequence, target: ReaderTarget,
        locator: ReaderLocator, preferences: ReaderLayoutPreferences): OperationResult<Unit>
    suspend fun markClosed()
}

class DiskCourseReaderRuntimeRecovery(
    private val store: ReturnPointStore,
    private val bookId: String,
    initialPointer: ReaderRuntimeRecoveryPointer = ReaderRuntimeRecoveryPointer(),
    private val publishPointer: suspend (ReaderRuntimeRecoveryPointer) -> Unit = {},
) : CourseReaderRuntimeRecovery {
    @Volatile var pointer = initialPointer
        private set
    private val lock = Mutex()
    private var lastSaved: CourseReaderRuntimeCheckpoint? = null

    override suspend fun restore(identity: SessionIdentity, sequence: ReadingSequence): OperationResult<CourseReaderRuntimeCheckpoint?> = lock.withLock {
        rejection(identity)?.let { return@withLock OperationResult.Rejected(it) }
        try {
            currentCoroutineContext().ensureActive()
            if (pointer.userId == null) publish(pointer.copy(userId = identity.userId, epoch = identity.epoch))
            val key = pointer.key ?: return@withLock OperationResult.Ok(null, "course-recovery-empty")
            val point = store.get(checkNotNull(identity.userId), key)
            currentCoroutineContext().ensureActive()
            if (point == null || point.routeInstanceKey != pointer.routeInstanceKey || point.callerRouteKey != routeKey())
                return@withLock OperationResult.Rejected("COURSE_RECOVERY_UNAVAILABLE")
            val data = Json.parseToJsonElement(point.positionStateJson).jsonObject
            require(data.keys == setOf("version", "bookId", "sequenceVersion", "chapterKey", "preferences"))
            require(data.getValue("version").jsonPrimitive.int == 1)
            val checkpoint = CourseReaderRuntimeCheckpoint(data.getValue("bookId").jsonPrimitive.content,
                data.getValue("sequenceVersion").jsonPrimitive.content, data.getValue("chapterKey").jsonPrimitive.content,
                checkNotNull(point.locatorJson?.let(ReaderLocatorCodec::decodeOrNull)), decode(data.getValue("preferences").jsonObject))
            val target = sequence.orderedTargets.singleOrNull { it.courseBookId == bookId && it.chapterKey == checkpoint.chapterKey }
            if (checkpoint.bookId != bookId || checkpoint.sequenceVersion != sequence.version || target == null ||
                point.documentId != checkpoint.locator.documentId || point.revision != checkpoint.locator.contentRevision ||
                !valid(sequence, target, checkpoint.locator, checkpoint.preferences))
                return@withLock OperationResult.Rejected("COURSE_RECOVERY_SCOPE_CHANGED")
            lastSaved = checkpoint
            OperationResult.Ok(checkpoint, "course-recovery-restored")
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            OperationResult.Rejected("COURSE_RECOVERY_UNAVAILABLE")
        }
    }

    override suspend fun save(identity: SessionIdentity, sequence: ReadingSequence, target: ReaderTarget,
        locator: ReaderLocator, preferences: ReaderLayoutPreferences): OperationResult<Unit> = lock.withLock {
        rejection(identity)?.let { return@withLock OperationResult.Rejected(it) }
        if (!valid(sequence, target, locator, preferences)) return@withLock OperationResult.Rejected("INVALID_COURSE_CHECKPOINT")
        val checkpoint = CourseReaderRuntimeCheckpoint(bookId, sequence.version, checkNotNull(target.chapterKey), locator, preferences)
        if (lastSaved == checkpoint && pointer.key != null) return@withLock OperationResult.Ok(Unit, "course-recovery-unchanged")
        try {
            currentCoroutineContext().ensureActive()
            val position = buildJsonObject {
                put("version", 1); put("bookId", bookId); put("sequenceVersion", sequence.version)
                put("chapterKey", target.chapterKey); put("preferences", encode(preferences))
            }.toString()
            val encodedLocator = ReaderLocatorCodec.encode(locator)
            if (position.length + encodedLocator.length > 256 * 1024)
                return@withLock OperationResult.Rejected("INVALID_COURSE_CHECKPOINT")
            val key = store.put(ReturnPoint(ownerUserId = checkNotNull(identity.userId), sessionEpoch = identity.epoch,
                routeInstanceKey = pointer.routeInstanceKey, callerRouteKey = routeKey(), documentId = target.documentId,
                revision = target.contentRevision, locatorJson = encodedLocator, positionStateJson = position))
            currentCoroutineContext().ensureActive()
            publish(pointer.copy(key = key, userId = identity.userId, epoch = identity.epoch))
            lastSaved = checkpoint
            OperationResult.Ok(Unit, "course-recovery-saved")
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            OperationResult.Failed("COURSE_RECOVERY_SAVE_FAILED", true)
        }
    }
    override suspend fun markClosed() = lock.withLock { publish(pointer.copy(closed = true)) }
    private suspend fun publish(next: ReaderRuntimeRecoveryPointer) {
        publishPointer(next)
        pointer = next
    }
    private fun routeKey() = "isolated-course-runtime/$bookId"
    private fun rejection(identity: SessionIdentity): String? = when {
        pointer.closed -> "RUNTIME_CLOSED"
        identity.userId == null -> "LOGIN_REQUIRED"
        bookId.isBlank() || bookId.length > 512 || pointer.routeInstanceKey.isBlank() || pointer.routeInstanceKey.length > 256 ||
            (pointer.userId == null) != (pointer.epoch == null) || (pointer.key != null && pointer.userId == null) -> "COURSE_RECOVERY_UNAVAILABLE"
        pointer.userId != null && (pointer.userId != identity.userId || pointer.epoch != identity.epoch) -> "SESSION_CHANGED"
        else -> null
    }
    private fun valid(sequence: ReadingSequence, target: ReaderTarget, locator: ReaderLocator, p: ReaderLayoutPreferences): Boolean =
        target.courseBookId == bookId && !target.chapterKey.isNullOrBlank() && sequence.orderedTargets.contains(target) &&
            sequence.orderedTargets.all { it.courseBookId == bookId && !it.chapterKey.isNullOrBlank() } &&
            sequence.orderedTargets.count { it.chapterKey == target.chapterKey } == 1 &&
            locator is ReaderLocator.Reflow && ReaderLocatorValidator.validate(locator).isValid &&
            locator.documentId == target.documentId && locator.contentRevision == target.contentRevision &&
            target.firstSectionId == target.lastSectionId && locator.sectionId == target.firstSectionId &&
            p.fontScale.isFinite() && p.fontScale in .5f..3f && p.lineHeight.isFinite() && p.lineHeight in 1f..3f &&
            p.marginDp in 0..96 && (p.fontFamily == null || p.fontFamily.length <= 256) &&
            p.flowMode == ReaderFlowMode.PAGINATED && COLOR.matches(p.backgroundHex) &&
            (p.foregroundHex == null || COLOR.matches(p.foregroundHex))
    private fun encode(p: ReaderLayoutPreferences) = buildJsonObject {
        put("fontScale", p.fontScale); put("lineHeight", p.lineHeight); put("marginDp", p.marginDp)
        put("fontFamily", p.fontFamily); put("foregroundHex", p.foregroundHex)
        put("backgroundHex", p.backgroundHex); put("flowMode", p.flowMode.name)
    }
    private fun decode(p: JsonObject): ReaderLayoutPreferences {
        require(p.keys == setOf("fontScale", "lineHeight", "marginDp", "fontFamily", "foregroundHex", "backgroundHex", "flowMode"))
        return ReaderLayoutPreferences(p.getValue("fontScale").jsonPrimitive.float, p.getValue("lineHeight").jsonPrimitive.float,
            p.getValue("marginDp").jsonPrimitive.int, p.getValue("fontFamily").jsonPrimitive.contentOrNull,
            p.getValue("foregroundHex").jsonPrimitive.contentOrNull, p.getValue("backgroundHex").jsonPrimitive.content,
            ReaderFlowMode.valueOf(p.getValue("flowMode").jsonPrimitive.content))
    }
    companion object { private val COLOR = Regex("#[0-9a-fA-F]{6}") }
}

fun createCourseReaderRuntimeRecovery(application: LearningApplication, handle: SavedStateHandle, bookId: String): CourseReaderRuntimeRecovery {
    val initial = runCatching {
        if (!handle.contains("courseRecoveryVersion")) ReaderRuntimeRecoveryPointer()
        else {
            require(handle.get<Int>("courseRecoveryVersion") == 1)
            ReaderRuntimeRecoveryPointer(handle["courseRecoveryKey"], checkNotNull(handle["courseRecoveryRoute"]),
                handle["courseRecoveryUser"], handle["courseRecoveryEpoch"], handle["courseRecoveryClosed"] ?: false)
        }
    }.getOrElse { ReaderRuntimeRecoveryPointer(routeInstanceKey = "") }
    val store = ReturnPointStore(File(application.noBackupFilesDir, "isolated-course-recovery"), {
        application.container.sessionManager.identity.first().let { id -> id.userId?.let { ReturnSession(it, id.epoch) } }
    })
    return DiskCourseReaderRuntimeRecovery(store, bookId, initial) { p -> withContext(Dispatchers.Main.immediate) {
        handle["courseRecoveryVersion"] = 1; handle["courseRecoveryKey"] = p.key; handle["courseRecoveryRoute"] = p.routeInstanceKey
        handle["courseRecoveryUser"] = p.userId; handle["courseRecoveryEpoch"] = p.epoch; handle["courseRecoveryClosed"] = p.closed
    } }
}

