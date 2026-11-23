package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.data.reader.ReaderPreferenceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ReaderSettingsPersistenceState(
    val settings: ReaderSettings,
    val pending: Boolean = false,
    val saving: Boolean = false,
    val failure: String? = null,
    val inheritsDefaults: Boolean = true,
    val readOnly: Boolean = false,
    val outcomeUnknown: Boolean = false,
    val retryable: Boolean = true,
    val abandoned: Boolean = false,
    val followsGlobalTheme: Boolean = false,
)

internal class ReaderSettingsPersistence(
    scope: CoroutineScope,
    initialDefaults: ReaderSettings,
    initialDocumentJson: String?,
    private val readOnly: Boolean,
    private val identityValid: suspend () -> Boolean,
    private val saveDocument: suspend (String) -> OperationResult<Unit>,
    private val saveDefaults: suspend (ReaderSettings) -> OperationResult<Unit>,
    defaultsExplicitTheme: Boolean = true,
    initialGlobalDark: Boolean = false,
) {
    private val lock = Any()
    private val writes = Mutex()
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val owner = CoroutineScope(scope.coroutineContext + job)
    private var debounce: Job? = null
    private var defaults = initialDefaults
    private var defaultsExplicitTheme = defaultsExplicitTheme
    private var globalDark = initialGlobalDark
    private var documentJson = initialDocumentJson ?: "{}"
    private var revision = 0L
    private var acknowledged = 0L
    private var proposedDefaults: ReaderSettings? = null
    private var writtenDefaults: ReaderSettings? = null
    private var closed = false
    private var unknown = false
    private var canRetry = true
    private var abandoned = false
    private val mutable = resolveSettingsAndGlobalFollow().let { (resolved, followsGlobal) ->
        MutableStateFlow(
            ReaderSettingsPersistenceState(
                settings = resolved,
                readOnly = readOnly,
                inheritsDefaults = inherits(documentJson),
                followsGlobalTheme = followsGlobal,
            ),
        )
    }
    val state: StateFlow<ReaderSettingsPersistenceState> = mutable.asStateFlow()

    /** Typography and chrome edits never turn a transient global theme projection into an override. */
    fun editOptions(settings: ReaderSettings) = synchronized(lock) {
        edit(settings.copy(theme = mutable.value.settings.theme))
    }

    fun edit(settings: ReaderSettings, forceThemeOverride: Boolean = false) {
        synchronized(lock) {
            if (closed || readOnly) return
            abandoned = false
            documentJson = ReaderPreferenceStore.updateOverrides(documentJson, mutable.value.settings, settings)
            // Choosing the currently inherited color is still an explicit document choice.
            if (forceThemeOverride) {
                val fields = kotlinx.serialization.json.Json.parseToJsonElement(documentJson)
                    .let { it as kotlinx.serialization.json.JsonObject }.toMutableMap()
                fields["theme"] = kotlinx.serialization.json.JsonPrimitive(settings.theme.name)
                documentJson = kotlinx.serialization.json.JsonObject(fields).toString()
            }
            canRetry = true
            revision++
            publish(failure = null)
        }
        schedule()
    }

    fun updateDefaults(settings: ReaderSettings, hasExplicitDefaults: Boolean = true) = synchronized(lock) {
        if (closed) return@synchronized
        defaults = settings
        defaultsExplicitTheme = hasExplicitDefaults
        publish()
    }

    fun setGlobalDarkTheme(dark: Boolean) = synchronized(lock) {
        if (closed || globalDark == dark) return@synchronized
        globalDark = dark
        publish()
    }

    fun useDefaults() {
        synchronized(lock) {
            if (closed || readOnly) return
            abandoned = false
            documentJson = "{}"
            canRetry = true
            proposedDefaults = null
            revision++
            publish(failure = null)
        }
        schedule()
    }

    fun makeDefaults() {
        synchronized(lock) {
            if (closed || readOnly) return
            abandoned = false
            proposedDefaults = mutable.value.settings
            canRetry = true
            writtenDefaults = null
            defaults = mutable.value.settings
            documentJson = "{}"
            revision++
            publish(failure = null)
        }
        schedule()
    }

    fun retry() {
        if (!closed && mutable.value.pending && mutable.value.retryable) schedule(0)
    }

    private fun schedule(quietMillis: Long = 300L) = synchronized(lock) {
        if (closed || readOnly) return@synchronized
        debounce?.cancel()
        // The quiet-period job owns only its timer; edits must not cancel a storage call.
        debounce = owner.launch { delay(quietMillis); owner.launch { flushInternal(drain = false) } }
    }

    suspend fun flush(): Boolean = flushInternal(drain = true)

    /** Explicit user decision: retire retry intent; never issue a compensating storage write. */
    suspend fun abandonPending() = writes.withLock {
        synchronized(lock) {
            if (closed) return@synchronized
            debounce?.cancel(); debounce = null
            acknowledged = revision
            proposedDefaults = null
            writtenDefaults = null
            unknown = false
            canRetry = false
            abandoned = true
            publish(saving = false, failure = null)
        }
    }

    private suspend fun flushInternal(drain: Boolean): Boolean = writes.withLock {
        while (true) {
            val intent = synchronized(lock) {
                if (closed) return@withLock false
                if (readOnly || revision == acknowledged) return@withLock true
                if (!canRetry && mutable.value.failure != null) return@withLock false
                mutable.value = mutable.value.copy(saving = true, failure = null)
                Intent(revision, documentJson, proposedDefaults)
            }
            var invoked = false
            try {
                if (!identityValid()) return@withLock fail("READER_SETTINGS_IDENTITY_CHANGED")
                if (intent.defaults != null && intent.defaults != writtenDefaults) {
                    invoked = true
                    val result = saveDefaults(intent.defaults)
                    if (result !is OperationResult.Ok) return@withLock failResult(result)
                    if (!identityValid()) return@withLock fail("READER_SETTINGS_IDENTITY_CHANGED", outcomeUnknown = true)
                    synchronized(lock) {
                        writtenDefaults = intent.defaults
                        defaultsExplicitTheme = true
                    }
                }
                if (!identityValid()) return@withLock fail("READER_SETTINGS_IDENTITY_CHANGED", outcomeUnknown = invoked)
                invoked = true
                val result = saveDocument(intent.json)
                if (result !is OperationResult.Ok) return@withLock failResult(result)
                if (!identityValid()) return@withLock fail("READER_SETTINGS_IDENTITY_CHANGED", outcomeUnknown = true)
                synchronized(lock) {
                    if (closed) return@withLock false
                    acknowledged = intent.revision
                    unknown = false
                    canRetry = true
                    if (revision == intent.revision) proposedDefaults = null
                    publish(saving = false, failure = null)
                }
                if (!drain) return@withLock true
            } catch (cancelled: CancellationException) {
                fail("READER_SETTINGS_CANCELLED", invoked)
                throw cancelled
            } catch (_: Exception) {
                return@withLock fail("READER_SETTINGS_SAVE_FAILED", invoked)
            }
        }
        @Suppress("UNREACHABLE_CODE") false
    }

    fun close() {
        synchronized(lock) { closed = true; mutable.value = mutable.value.copy(saving = false) }
        job.cancel()
    }

    private fun failResult(result: OperationResult<*>): Boolean = fail(result.code(),
        result is OperationResult.Failed,
        when (result) {
            is OperationResult.Failed -> result.retryable
            is OperationResult.Conflict -> result.recoverable
            else -> false
        })

    private fun fail(code: String, outcomeUnknown: Boolean = false, retryable: Boolean = code != "READER_SETTINGS_IDENTITY_CHANGED"): Boolean {
        synchronized(lock) {
            unknown = unknown || outcomeUnknown
            canRetry = retryable
            publish(saving = false, failure = code)
        }
        return false
    }

    private fun resolveSettingsAndGlobalFollow(): Pair<ReaderSettings, Boolean> {
        val explicitDefaults = defaultsExplicitTheme || proposedDefaults != null
        val explicitDocument = ReaderPreferenceStore.hasExplicitTheme(documentJson)
        val followsGlobal = !explicitDefaults && !explicitDocument
        val baseDefaults = proposedDefaults ?: defaults
        val effectiveDefaults = if (followsGlobal) {
            baseDefaults.copy(theme = if (globalDark) ReaderTheme.NIGHT else ReaderTheme.LIGHT)
        } else {
            baseDefaults
        }
        return ReaderPreferenceStore.resolve(effectiveDefaults, documentJson) to followsGlobal
    }

    private fun publish(saving: Boolean = mutable.value.saving, failure: String? = mutable.value.failure) {
        val (resolved, followsGlobal) = resolveSettingsAndGlobalFollow()
        mutable.value = ReaderSettingsPersistenceState(
            settings = resolved,
            pending = revision != acknowledged, saving = saving, failure = failure,
            inheritsDefaults = inherits(documentJson), readOnly = readOnly,
            outcomeUnknown = unknown, retryable = canRetry, abandoned = abandoned,
            followsGlobalTheme = followsGlobal,
        )
    }

    private data class Intent(val revision: Long, val json: String, val defaults: ReaderSettings?)
    private fun OperationResult<*>.code(): String = when (this) {
        is OperationResult.Conflict -> "READER_SETTINGS_CONFLICT"
        is OperationResult.Rejected -> code
        is OperationResult.Failed -> code
        is OperationResult.Ok -> "READER_SETTINGS_INVALID_RESULT"
    }

    private companion object {
        fun inherits(json: String): Boolean = try {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject
            root == null || root.keys.none { it in setOf("fontScale", "lineHeight", "theme", "fontColor", "marginDp", "flowMode", "edgeTap", "immersivePreference", "pageIndicator") }
        } catch (_: IllegalArgumentException) { true }
    }
}
