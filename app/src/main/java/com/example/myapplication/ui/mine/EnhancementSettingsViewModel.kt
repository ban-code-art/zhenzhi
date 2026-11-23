package com.example.myapplication.ui.mine

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.knowledge.index.VectorCodec
import com.example.myapplication.data.knowledge.index.VectorEncodingPreference
import com.example.myapplication.data.search.DataStoreExternalSearchSettings
import com.example.myapplication.data.search.EnhancementToggles
import com.example.myapplication.data.search.ExternalSearchSettings
import com.example.myapplication.data.session.SessionIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class EnhancementTogglePhase { Loading, SignedOut, ReadFailed, Ready, Saving, Failed, Unconfirmed }

data class EnhancementToggleStatus(
    val phase: EnhancementTogglePhase = EnhancementTogglePhase.Ready,
    internal val requestId: Long? = null,
) {
    val canChange: Boolean
        get() = phase == EnhancementTogglePhase.Ready || phase == EnhancementTogglePhase.Failed ||
            phase == EnhancementTogglePhase.Unconfirmed

    val message: String?
        get() = when (phase) {
            EnhancementTogglePhase.Loading -> "正在读取设置"
            EnhancementTogglePhase.SignedOut -> "登录后可修改设置"
            EnhancementTogglePhase.ReadFailed -> "无法读取设置"
            EnhancementTogglePhase.Ready -> null
            EnhancementTogglePhase.Saving -> "正在保存"
            EnhancementTogglePhase.Failed -> "保存失败，可重新操作"
            EnhancementTogglePhase.Unconfirmed -> "尚未确认保存结果，可重新操作"
        }
}

/** 设置页「AI 增强」区状态：两个开关都默认关闭。 */
data class EnhancementSettingsUiState(
    val webSearchEnabled: Boolean = false,
    val aiReviewEnabled: Boolean = false,
    val vectorEncoding: String = VectorCodec.ENCODING_FLOAT32,
    val activeVectorEncoding: String = VectorCodec.ENCODING_FLOAT32,
    val vectorEncodingRebuildPending: Boolean = false,
    val searchEndpointDraft: String = "",
    val searchEndpointSaved: String? = null,
    val searchEndpointDirty: Boolean = false,
    val searchEndpointError: String? = null,
    val searchEndpointSaving: Boolean = false,
    val searchEndpointSaveRequestId: Long? = null,
    val searchEndpointSavedRequestId: Long? = null,
    val webSearchToggleStatus: EnhancementToggleStatus = EnhancementToggleStatus(),
    val aiReviewToggleStatus: EnhancementToggleStatus = EnhancementToggleStatus(),
)

/** Existing repositories remain the source of truth; the seam controls asynchronous boundaries. */
internal data class EnhancementSettingsDependencies(
    val sessionIdentity: Flow<SessionIdentity>,
    val enhancementToggles: EnhancementToggles,
    val vectorEncodingPreference: VectorEncodingPreference,
    val externalSearchSettings: ExternalSearchSettings,
    val activeEmbeddingFingerprint: suspend (Long) -> String,
    val scheduleIndexing: (Long) -> Unit,
)

/**
 * AI 增强开关的 UI 入口（W5-10）。
 *
 * 两个开关都是**逐用户**、默认关闭、可随时撤回：撤回后下一次工具调用立即失效，
 * 已产生的历史回答不受影响（不删除任何已有内容）。
 */
class EnhancementSettingsViewModel internal constructor(
    private val dependencies: EnhancementSettingsDependencies,
) : ViewModel() {
    constructor(application: LearningApplication) : this(
        EnhancementSettingsDependencies(
            sessionIdentity = application.container.sessionManager.identity,
            enhancementToggles = application.container.enhancementToggles,
            vectorEncodingPreference = application.container.vectorEncodingPreference,
            externalSearchSettings = application.container.externalSearchSettings,
            activeEmbeddingFingerprint = application.container.indexRunner::activeEmbeddingFingerprint,
            scheduleIndexing = application.container::scheduleIndexing,
        ),
    )

    private val _state = MutableStateFlow(settingsWithTogglesIn(EnhancementTogglePhase.Loading))
    val state: StateFlow<EnhancementSettingsUiState> = _state.asStateFlow()
    private var owner: SessionIdentity? = null
    private var accountScope: CoroutineScope? = null
    private var saveSerial = 0L
    private var toggleSerial = 0L

    init {
        viewModelScope.launch {
            dependencies.sessionIdentity.collect { identity ->
                if (identity == owner) return@collect
                // Invalidate before cancelling: even a cancellation-resistant old result is stale.
                owner = identity
                saveSerial++
                accountScope?.cancel()
                accountScope = null
                val id = identity.userId
                _state.value = settingsWithTogglesIn(
                    if (id == null) EnhancementTogglePhase.SignedOut else EnhancementTogglePhase.Loading,
                )
                if (id == null) return@collect
                val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
                accountScope = scope
                scope.launch {
                    observeToggle(identity, Toggle.WebSearch, dependencies.enhancementToggles.observeWebSearch(id))
                }
                scope.launch {
                    observeToggle(identity, Toggle.AiReview, dependencies.enhancementToggles.observeAiReview(id))
                }
                scope.launch {
                    dependencies.vectorEncodingPreference.observe(id).collect { encoding ->
                        if (owner == identity) _state.update {
                            it.copy(vectorEncoding = encoding, vectorEncodingRebuildPending = encoding != it.activeVectorEncoding)
                        }
                    }
                }
                scope.launch {
                    val fingerprint = dependencies.activeEmbeddingFingerprint(id)
                    if (fingerprint.isNotBlank() && owner == identity) {
                        dependencies.vectorEncodingPreference.observeActive(id, fingerprint).collect { encoding ->
                            if (owner == identity) _state.update {
                                it.copy(activeVectorEncoding = encoding, vectorEncodingRebuildPending = encoding != it.vectorEncoding)
                            }
                        }
                    }
                }
                scope.launch {
                    dependencies.externalSearchSettings.observeEndpoint().collect { endpoint ->
                        if (owner == identity) _state.update { state ->
                            if (state.searchEndpointDirty || state.searchEndpointSaving) state.copy(
                                searchEndpointSaved = endpoint,
                                searchEndpointDirty = state.searchEndpointDraft != endpoint.orEmpty(),
                            )
                            else state.copy(searchEndpointDraft = endpoint.orEmpty(), searchEndpointSaved = endpoint)
                        }
                    }
                }
            }
        }
    }

    fun setWebSearchEnabled(enabled: Boolean) = setToggle(Toggle.WebSearch, enabled)

    fun setAiReviewEnabled(enabled: Boolean) = setToggle(Toggle.AiReview, enabled)

    private suspend fun observeToggle(identity: SessionIdentity, toggle: Toggle, values: Flow<Boolean>) {
        values.catch {
            if (currentSession(identity)) _state.update { state ->
                when (toggle.status(state).phase) {
                    EnhancementTogglePhase.Loading -> toggle.update(
                        state, status = EnhancementToggleStatus(EnhancementTogglePhase.ReadFailed),
                    )
                    else -> state
                }
            }
        }.collect { enabled ->
            if (currentSession(identity)) _state.update { state ->
                val current = toggle.status(state)
                val status = when (current.phase) {
                    EnhancementTogglePhase.Loading,
                    EnhancementTogglePhase.ReadFailed,
                    EnhancementTogglePhase.Unconfirmed -> EnhancementToggleStatus()
                    else -> current
                }
                toggle.update(state, enabled, status)
            }
        }
    }

    private fun setToggle(toggle: Toggle, enabled: Boolean) {
        val identity = owner ?: return
        val id = identity.userId ?: return
        val scope = accountScope ?: return
        val state = _state.value
        if (!toggle.status(state).canChange) return
        if (enabled == toggle.value(state) && toggle.status(state).phase != EnhancementTogglePhase.Unconfirmed) return
        val request = ++toggleSerial
        _state.update {
            toggle.update(it, status = EnhancementToggleStatus(EnhancementTogglePhase.Saving, request))
        }
        scope.launch {
            var writeReturned = false
            try {
                if (!currentSession(identity)) return@launch
                val actual = withTimeoutOrNull(3_000) {
                    val repository = dependencies.enhancementToggles
                    when (toggle) {
                        Toggle.WebSearch -> repository.setWebSearchEnabled(id, enabled)
                        Toggle.AiReview -> repository.setAiReviewEnabled(id, enabled)
                    }
                    writeReturned = true
                    if (!currentSession(identity)) return@withTimeoutOrNull null
                    when (toggle) {
                        Toggle.WebSearch -> repository.isWebSearchEnabled(id)
                        Toggle.AiReview -> repository.isAiReviewEnabled(id)
                    }
                }
                ensureActive()
                if (!currentSession(identity)) return@launch
                finishToggle(
                    toggle, request, actual,
                    if (actual == enabled) EnhancementTogglePhase.Ready else EnhancementTogglePhase.Unconfirmed,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (currentSession(identity)) finishToggle(
                    toggle, request, value = null,
                    phase = if (writeReturned) EnhancementTogglePhase.Unconfirmed else EnhancementTogglePhase.Failed,
                )
            } finally {
                if (owner == identity) finishToggle(
                    toggle, request, value = null, phase = EnhancementTogglePhase.Unconfirmed,
                )
            }
        }
    }

    private fun finishToggle(toggle: Toggle, request: Long, value: Boolean?, phase: EnhancementTogglePhase) {
        _state.update { state ->
            if (toggle.status(state).requestId != request) state
            else toggle.update(state, value ?: toggle.value(state), EnhancementToggleStatus(phase))
        }
    }

    private enum class Toggle {
        WebSearch, AiReview;

        fun value(state: EnhancementSettingsUiState): Boolean =
            if (this == WebSearch) state.webSearchEnabled else state.aiReviewEnabled

        fun status(state: EnhancementSettingsUiState): EnhancementToggleStatus =
            if (this == WebSearch) state.webSearchToggleStatus else state.aiReviewToggleStatus

        fun update(
            state: EnhancementSettingsUiState,
            enabled: Boolean = value(state),
            status: EnhancementToggleStatus,
        ): EnhancementSettingsUiState = when (this) {
            WebSearch -> state.copy(webSearchEnabled = enabled, webSearchToggleStatus = status)
            AiReview -> state.copy(aiReviewEnabled = enabled, aiReviewToggleStatus = status)
        }
    }
    fun setVectorEncoding(encoding: String) {
        val identity = owner ?: return
        val id = identity.userId ?: return
        accountScope?.launch {
            if (currentSession(identity) && dependencies.vectorEncodingPreference.set(id, encoding) && currentSession(identity)) {
                dependencies.scheduleIndexing(id)
            }
        }
    }

    fun updateSearchEndpointDraft(value: String) {
        _state.update {
            if (it.searchEndpointSaving) it
            else it.copy(searchEndpointDraft = value, searchEndpointDirty = value != it.searchEndpointSaved.orEmpty(), searchEndpointError = null)
        }
    }

    fun discardSearchEndpointDraft() {
        _state.update {
            if (it.searchEndpointSaving) it
            else it.copy(searchEndpointDraft = it.searchEndpointSaved.orEmpty(), searchEndpointDirty = false, searchEndpointError = null)
        }
    }

    // Keep the original no-argument API, including function references used by existing callers.
    fun saveSearchEndpoint() {
        saveSearchEndpoint(onSaved = {})
    }

    /** The returned id binds a leave intent to its own success across screen recreation. */
    fun saveSearchEndpoint(onSaved: () -> Unit): Long? {
        val identity = owner?.takeIf { it.userId != null } ?: return null
        val scope = accountScope ?: return null
        val current = _state.value
        if (current.searchEndpointSaving) return null
        val serial = ++saveSerial
        val value = current.searchEndpointDraft
        _state.update { it.copy(searchEndpointSaving = true, searchEndpointError = null,
            searchEndpointSaveRequestId = serial, searchEndpointSavedRequestId = null) }
        scope.launch {
            var succeeded = false
            try {
                if (!currentSession(identity)) return@launch
                val accepted = dependencies.externalSearchSettings.setEndpoint(value)
                ensureActive()
                if (serial != saveSerial || !currentSession(identity)) return@launch
                if (!accepted) {
                    _state.update { it.copy(searchEndpointError = "地址无效。请填写无账号密码、无查询参数的 HTTP(S) /search 地址。") }
                    return@launch
                }
                val normalized = DataStoreExternalSearchSettings.validateEndpoint(value)
                _state.update {
                    it.copy(searchEndpointDraft = normalized.orEmpty(), searchEndpointSaved = normalized,
                        searchEndpointDirty = false, searchEndpointError = null,
                        searchEndpointSavedRequestId = serial)
                }
                succeeded = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (serial == saveSerial && currentSession(identity)) {
                    _state.update { it.copy(searchEndpointError = "保存失败，请稍后重试。") }
                }
            } finally {
                if (owner == identity && serial == saveSerial) _state.update { it.copy(searchEndpointSaving = false) }
            }
            ensureActive()
            if (succeeded && serial == saveSerial && currentSession(identity)) onSaved()
        }
        return serial
    }

    private suspend fun currentSession(identity: SessionIdentity): Boolean =
        owner == identity && dependencies.sessionIdentity.first() == identity
}

private fun settingsWithTogglesIn(phase: EnhancementTogglePhase): EnhancementSettingsUiState =
    EnhancementSettingsUiState(
        webSearchToggleStatus = EnhancementToggleStatus(phase),
        aiReviewToggleStatus = EnhancementToggleStatus(phase),
    )

class EnhancementSettingsViewModelFactory(
    private val context: Context,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        EnhancementSettingsViewModel(context.applicationContext as LearningApplication) as T
}
