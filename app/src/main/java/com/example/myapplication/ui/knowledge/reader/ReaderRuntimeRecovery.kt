package com.example.myapplication.ui.knowledge.reader

import androidx.lifecycle.SavedStateHandle
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.domain.reader.*
import com.example.myapplication.ui.navigation.ReturnPointStore
import com.example.myapplication.ui.navigation.ReturnPoint
import com.example.myapplication.ui.navigation.ReturnSession
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class ReaderRuntimeCheckpoint(val locator: ReaderLocator, val preferences: ReaderLayoutPreferences?)

interface ReaderRuntimeRecovery {
    suspend fun restore(identity: SessionIdentity): OperationResult<ReaderRuntimeCheckpoint?>
    suspend fun save(identity: SessionIdentity, locator: ReaderLocator, preferences: ReaderLayoutPreferences? = null): OperationResult<Unit>
    suspend fun markClosed()
}

/** Only this small pointer belongs in SavedState; content remains in the no-backup store. */
data class ReaderRuntimeRecoveryPointer(
    val key: String? = null,
    val routeInstanceKey: String = java.util.UUID.randomUUID().toString(),
    val userId: Long? = null,
    val epoch: Long? = null,
    val closed: Boolean = false,
)

class DiskReaderRuntimeRecovery(
    private val store: ReturnPointStore,
    private val documentId: String,
    private val kind: String,
    initialPointer: ReaderRuntimeRecoveryPointer = ReaderRuntimeRecoveryPointer(),
    private val publishPointer: suspend (ReaderRuntimeRecoveryPointer) -> Unit = {},
) : ReaderRuntimeRecovery {
    @Volatile var pointer: ReaderRuntimeRecoveryPointer = initialPointer
        private set
    private val lock = Mutex()
    private var lastSaved: ReaderRuntimeCheckpoint? = null
    override suspend fun restore(identity: SessionIdentity): OperationResult<ReaderRuntimeCheckpoint?> = lock.withLock {
        rejection(identity)?.let { return@withLock OperationResult.Rejected(it) }
        try {
            if (pointer.userId == null) publish(pointer.copy(userId = identity.userId, epoch = identity.epoch))
            val key = pointer.key ?: return@withLock OperationResult.Ok(null, "runtime-recovery-empty")
            val point = store.get(checkNotNull(identity.userId), key)
                ?: return@withLock OperationResult.Rejected("RUNTIME_RECOVERY_UNAVAILABLE")
            if (point.routeInstanceKey != pointer.routeInstanceKey || point.callerRouteKey != routeKey() ||
                point.documentId != documentId) return@withLock OperationResult.Rejected("RUNTIME_RECOVERY_UNAVAILABLE")
            val locator = point.locatorJson?.let(ReaderLocatorCodec::decodeOrNull)
                ?: return@withLock OperationResult.Rejected("RUNTIME_RECOVERY_UNAVAILABLE")
            val preferences = decodePreferences(point.positionStateJson)
            if (point.revision != locator.contentRevision || !valid(locator, preferences))
                return@withLock OperationResult.Rejected("RUNTIME_RECOVERY_UNAVAILABLE")
            val checkpoint = ReaderRuntimeCheckpoint(locator, preferences)
            lastSaved = checkpoint
            OperationResult.Ok(checkpoint, "runtime-recovery-restored")
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { OperationResult.Rejected("RUNTIME_RECOVERY_UNAVAILABLE") }
    }
    override suspend fun save(identity: SessionIdentity, locator: ReaderLocator, preferences: ReaderLayoutPreferences?): OperationResult<Unit> = lock.withLock {
        rejection(identity)?.let { return@withLock OperationResult.Rejected(it) }
        if (!valid(locator, preferences)) return@withLock OperationResult.Rejected("INVALID_RUNTIME_CHECKPOINT")
        val checkpoint = ReaderRuntimeCheckpoint(locator, preferences)
        if (lastSaved == checkpoint && pointer.key != null) return@withLock OperationResult.Ok(Unit, "runtime-recovery-unchanged")
        try {
            val locatorJson = ReaderLocatorCodec.encode(locator)
            val layoutJson = encodePreferences(preferences)
            if (locatorJson.length + layoutJson.length > MAX_CHECKPOINT_CHARS)
                return@withLock OperationResult.Rejected("INVALID_RUNTIME_CHECKPOINT")
            val key = store.put(ReturnPoint(ownerUserId = checkNotNull(identity.userId), sessionEpoch = identity.epoch,
                routeInstanceKey = pointer.routeInstanceKey, callerRouteKey = routeKey(), documentId = documentId,
                revision = locator.contentRevision, locatorJson = locatorJson, positionStateJson = layoutJson))
            // The old pointer remains usable if disk publication or SavedState publication fails.
            publish(pointer.copy(key = key, userId = identity.userId, epoch = identity.epoch))
            lastSaved = checkpoint
            OperationResult.Ok(Unit, "runtime-recovery-saved")
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { OperationResult.Failed("RUNTIME_RECOVERY_SAVE_FAILED", true) }
    }
    override suspend fun markClosed() = lock.withLock { publish(pointer.copy(closed = true)) }
    private suspend fun publish(next: ReaderRuntimeRecoveryPointer) {
        publishPointer(next)
        pointer = next
    }
    private fun rejection(identity: SessionIdentity): String? = when {
        pointer.closed -> "RUNTIME_CLOSED"
        identity.userId == null -> "LOGIN_REQUIRED"
        pointer.routeInstanceKey.isBlank() || pointer.routeInstanceKey.length > 256 ||
            (pointer.userId == null) != (pointer.epoch == null) || (pointer.key != null && pointer.userId == null) -> "RUNTIME_RECOVERY_UNAVAILABLE"
        pointer.userId != null && (pointer.userId != identity.userId || pointer.epoch != identity.epoch) -> "SESSION_CHANGED"
        else -> null
    }
    private fun routeKey() = "isolated-runtime/$kind"
    private fun valid(locator: ReaderLocator, preferences: ReaderLayoutPreferences?): Boolean =
        locator.documentId == documentId && ReaderLocatorValidator.validate(locator).isValid &&
            when (kind) {
                "PDF" -> locator is ReaderLocator.Fixed && preferences == null
                "REFLOW" -> locator is ReaderLocator.Reflow && preferences?.let(::validPreferences) == true
                else -> false
            }
    private fun validPreferences(p: ReaderLayoutPreferences): Boolean = p.fontScale.isFinite() && p.fontScale in .5f..3f &&
        p.lineHeight.isFinite() && p.lineHeight in 1f..3f && p.marginDp in 0..96 &&
        (p.fontFamily == null || p.fontFamily.length <= 256) && p.flowMode == ReaderFlowMode.PAGINATED &&
        COLOR.matches(p.backgroundHex) && (p.foregroundHex == null || COLOR.matches(p.foregroundHex))
    private fun encodePreferences(p: ReaderLayoutPreferences?) = buildJsonObject {
        put("version", 1)
        put("preferences", p?.let { buildJsonObject {
            put("fontScale", it.fontScale); put("lineHeight", it.lineHeight); put("marginDp", it.marginDp)
            put("fontFamily", it.fontFamily); put("foregroundHex", it.foregroundHex)
            put("backgroundHex", it.backgroundHex); put("flowMode", it.flowMode.name)
        } } ?: JsonNull)
    }.toString()
    private fun decodePreferences(text: String): ReaderLayoutPreferences? {
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.keys == setOf("version", "preferences") && root.getValue("version").jsonPrimitive.int == 1)
        if (root.getValue("preferences") == JsonNull) return null
        val p = root.getValue("preferences").jsonObject
        require(p.keys == setOf("fontScale", "lineHeight", "marginDp", "fontFamily", "foregroundHex", "backgroundHex", "flowMode"))
        return ReaderLayoutPreferences(p.getValue("fontScale").jsonPrimitive.float, p.getValue("lineHeight").jsonPrimitive.float,
            p.getValue("marginDp").jsonPrimitive.int, p.getValue("fontFamily").jsonPrimitive.contentOrNull,
            p.getValue("foregroundHex").jsonPrimitive.contentOrNull, p.getValue("backgroundHex").jsonPrimitive.content,
            ReaderFlowMode.valueOf(p.getValue("flowMode").jsonPrimitive.content))
    }
    companion object {
        private const val MAX_CHECKPOINT_CHARS = 256 * 1024
        private val COLOR = Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")
    }
}

fun createReaderRuntimeRecovery(application: LearningApplication, handle: SavedStateHandle, documentId: String, kind: String): ReaderRuntimeRecovery {
    val initial = runCatching {
        if (!handle.contains("runtimeRecoveryVersion")) ReaderRuntimeRecoveryPointer()
        else {
            require(handle.get<Int>("runtimeRecoveryVersion") == 1)
            ReaderRuntimeRecoveryPointer(handle["runtimeRecoveryKey"], checkNotNull(handle["runtimeRecoveryRoute"]),
                handle["runtimeRecoveryUser"], handle["runtimeRecoveryEpoch"], handle["runtimeRecoveryClosed"] ?: false)
        }
    }.getOrElse { ReaderRuntimeRecoveryPointer(routeInstanceKey = "") }
    val store = ReturnPointStore(File(application.noBackupFilesDir, "isolated-reader-recovery"), {
        application.container.sessionManager.identity.first().let { identity ->
            identity.userId?.let { ReturnSession(it, identity.epoch) }
        }
    })
    return DiskReaderRuntimeRecovery(store, documentId, kind, initial) { pointer ->
        withContext(Dispatchers.Main.immediate) {
            handle["runtimeRecoveryVersion"] = 1
            handle["runtimeRecoveryKey"] = pointer.key
            handle["runtimeRecoveryRoute"] = pointer.routeInstanceKey
            handle["runtimeRecoveryUser"] = pointer.userId
            handle["runtimeRecoveryEpoch"] = pointer.epoch
            handle["runtimeRecoveryClosed"] = pointer.closed
        }
    }
}
