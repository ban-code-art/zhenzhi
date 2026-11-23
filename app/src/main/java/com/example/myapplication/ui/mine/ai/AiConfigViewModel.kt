package com.example.myapplication.ui.mine.ai

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.ai.config.CapabilityProbe
import com.example.myapplication.data.ai.config.ProbeFailure
import com.example.myapplication.data.ai.config.ProbeOutcome
import com.example.myapplication.data.ai.config.ProfileRepository
import com.example.myapplication.data.ai.config.ProfileRuntime
import com.example.myapplication.data.ai.config.EndpointConfigCodec
import com.example.myapplication.data.ai.config.EndpointOperation
import com.example.myapplication.data.ai.config.EndpointResolver
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.AiUserSettingsEntity
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.data.session.SessionManager
import com.example.myapplication.domain.ai.CapabilityProfile
import com.example.myapplication.domain.ai.ModelProfile
import com.example.myapplication.domain.ai.ProfilePurpose
import com.example.myapplication.domain.ai.WireProtocol
import com.example.myapplication.domain.reader.sha256Hex
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Draft state and evidence have independent lifetimes. */
data class AiConfigUiState(
    val purpose: ProfilePurpose = ProfilePurpose.CHAT,
    val profiles: List<ModelProfile> = emptyList(),
    val activeProfileId: String? = null,
    val draft: DraftState = DraftState(),
    val draftBaseline: DraftState = DraftState(),
    val saving: Boolean = false,
    val activating: Boolean = false,
    val probing: Boolean = false,
    val probePhase: String? = null,
    val probeResult: CapabilityProfile? = null,
    val probeNotes: List<String> = emptyList(),
    val probeFirstTokenMs: Long? = null,
    val probeTotalMs: Long? = null,
    val error: String? = null,
    val savedMessage: String? = null,
    val models: List<String> = emptyList(),
    val modelsLoading: Boolean = false,
    val modelQuery: String = "",
    val modelVisibleCount: Int = ModelListFilter.PAGE_SIZE,
    val generation: Long = 0,
    val savedCapabilities: CapabilityProfile = CapabilityProfile(),
) {
    val isDirty: Boolean get() = draft.isDirty(draftBaseline)

    fun accepts(ticket: DraftRequestTicket): Boolean =
        generation == ticket.generation && purpose == ticket.purpose &&
            (ticket.configFingerprint == null || ticket.configFingerprint == draftFingerprint(purpose, draft))

    fun edited(next: DraftState): AiConfigUiState = if (next == draft) this else copy(
        draft = next, generation = generation + 1, probing = false, probePhase = null,
        probeResult = null, probeNotes = emptyList(), probeFirstTokenMs = null, probeTotalMs = null,
        models = emptyList(), modelsLoading = false, modelQuery = "",
        modelVisibleCount = ModelListFilter.PAGE_SIZE, error = null, savedMessage = null,
    )

    fun withProbe(ticket: DraftRequestTicket, outcome: ProbeOutcome): AiConfigUiState =
        if (!accepts(ticket)) this else copy(probing = false, probePhase = null,
            probeResult = outcome.capabilities, probeNotes = outcome.notes,
            probeFirstTokenMs = outcome.firstTokenMs, probeTotalMs = outcome.totalMs)
}

/** No secret or URL is embedded in asynchronous request identities. */
data class DraftRequestTicket(val generation: Long, val purpose: ProfilePurpose, val serial: Long,
    val configFingerprint: String? = null)

/** Bind a network result to resolved operation URLs and provider parameters, without retaining secrets. */
private fun draftFingerprint(purpose: ProfilePurpose, draft: DraftState): String? {
    val normalized = EndpointResolver.normalizeConfiguration(draft.protocol, purpose, draft.endpoint,
        draft.paramsJson, draft.model, requireModel = false)
    if (normalized !is EndpointResolver.ConfigurationResult.Ok) return null
    val operations = EndpointOperation.entries.joinToString("\n") { operation ->
        val target = when (val resolved = EndpointResolver.resolve(draft.protocol, purpose, normalized.endpoint,
            normalized.paramsJson, operation, draft.model, stream = operation == EndpointOperation.CHAT_STREAM)) {
            is EndpointResolver.Resolution.Ok -> resolved.endpoint.url
            is EndpointResolver.Resolution.Invalid -> resolved.code
            is EndpointResolver.Resolution.Unsupported -> "UNSUPPORTED"
        }
        "${operation.name}:$target"
    }
    return sha256Hex("${purpose.name}\n${draft.protocol.name}\n${draft.model}\n${normalized.configHash}\n$operations")
}

data class DraftState(
    val id: String? = null,
    val protocol: WireProtocol = WireProtocol.OPENAI_CHAT,
    val endpoint: String = "",
    val model: String = "",
    val apiKey: String = "",
    val enabled: Boolean = true,
    val baseRevision: Long = 0,
    val paramsJson: String = "{}",
    val secretRef: String? = null,
    val clearApiKey: Boolean = false,
) {
    val keyEdit: KeyEdit get() = when {
        clearApiKey -> KeyEdit.Clear
        apiKey.isNotEmpty() -> KeyEdit.Replace(apiKey)
        else -> KeyEdit.Keep
    }
    fun isDirty(initial: DraftState): Boolean = copy(paramsJson = initial.paramsJson) != initial ||
        !EndpointDraftEditor.sameParams(paramsJson, initial.paramsJson)
    fun sameConfiguration(initial: DraftState): Boolean = protocol == initial.protocol &&
        endpoint == initial.endpoint && model == initial.model && EndpointDraftEditor.sameParams(paramsJson, initial.paramsJson) &&
        keyEdit == initial.keyEdit && secretRef == initial.secretRef
    override fun toString(): String = "DraftState(id=$id, protocol=$protocol, keyEdit=$keyEdit, revision=$baseRevision)"
}

/**
 * 模型列表的纯客户端搜索/分页（候选不标为已验证；列表失败仍可手输 model）。
 * 单测直接覆盖该纯函数，UI 只负责渲染。
 */
object ModelListFilter {
    const val PAGE_SIZE = 20

    /** 大小写无关的子串过滤；空白 query 返回原列表。 */
    fun filter(models: List<String>, query: String): List<String> {
        val keyword = query.trim()
        if (keyword.isEmpty()) return models
        return models.filter { it.contains(keyword, ignoreCase = true) }
    }

    /** 当前可见页；可见数至少 1，避免被错误状态清空。 */
    fun visible(filtered: List<String>, visibleCount: Int): List<String> =
        filtered.take(visibleCount.coerceAtLeast(1))

    /** “显示更多”按钮的剩余项数，0 表示没有更多。 */
    fun remaining(filtered: List<String>, visibleCount: Int): Int =
        (filtered.size - visibleCount.coerceAtLeast(1)).coerceAtLeast(0)
}

class AiConfigViewModel(
    private val database: AppDatabase,
    private val repository: ProfileRepository,
    private val runtime: ProfileRuntime,
    private val probe: CapabilityProbe,
    private val sessionManager: SessionManager,
    private val onProfileActivated: (userId: Long) -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(AiConfigUiState())
    val state: StateFlow<AiConfigUiState> = _state.asStateFlow()

    private var probeJob: Job? = null
    private var modelsJob: Job? = null
    private var probeSerial = 0L
    private var modelsSerial = 0L
    private var deleteSerial = 0L
    private var deleteInFlight = false
    private var activationSerial = 0L
    private var owner: SessionIdentity? = null
    private var settings: AiUserSettingsEntity? = null

    init {
        viewModelScope.launch {
            sessionManager.identity.collectLatest { identity ->
                if (owner != null && owner != identity) {
                    cancelNetwork()
                    deleteSerial++
                    deleteInFlight = false
                    activationSerial++
                    _state.value = AiConfigUiState(generation = _state.value.generation + 1)
                    settings = null
                }
                owner = identity
                val userId = identity.userId ?: return@collectLatest
                coroutineScope {
                    launch {
                        repository.observeProfiles(userId).collect { profiles ->
                            if (owner == identity) _state.update { it.copy(profiles = profiles) }
                        }
                    }
                    launch {
                        database.aiProfileDao().observeSettings(userId).collect { row ->
                            if (owner == identity) {
                                settings = row
                                _state.update { it.copy(activeProfileId = activeId(it.purpose)) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun activeId(purpose: ProfilePurpose): String? = when (purpose) {
        ProfilePurpose.CHAT -> settings?.activeChatProfileId
        ProfilePurpose.EMBEDDING -> settings?.activeEmbeddingProfileId
        ProfilePurpose.RERANK -> settings?.activeRerankProfileId
        ProfilePurpose.SEARCH -> settings?.activeSearchProfileId
    }

    private fun cancelNetwork() {
        probeSerial++; modelsSerial++
        probeJob?.cancel(); modelsJob?.cancel()
        probeJob = null; modelsJob = null
    }

    fun selectPurpose(purpose: ProfilePurpose) {
        val current = _state.value
        if (current.saving || purpose == current.purpose) return
        cancelNetwork()
        _state.value = AiConfigUiState(purpose = purpose, profiles = current.profiles,
            activeProfileId = activeId(purpose), generation = current.generation + 1)
    }

    fun editDraft(transform: (DraftState) -> DraftState) {
        val current = _state.value
        if (current.saving) return
        val next = transform(current.draft)
        if (next == current.draft) return
        cancelNetwork()
        _state.value = current.edited(next)
    }

    fun editApiKey(value: String) = editDraft { it.copy(apiKey = value, clearApiKey = false) }
    fun keepApiKey() = editDraft { it.copy(apiKey = "", clearApiKey = false) }
    fun clearApiKey() = editDraft { it.copy(apiKey = "", clearApiKey = true) }

    fun editEndpointMode(mode: EndpointConfigCodec.Mode) {
        val current = _state.value
        if (current.saving) return
        val decoded = EndpointConfigCodec.decode(current.draft.paramsJson) as? EndpointConfigCodec.DecodeResult.Ok
        if (mode == EndpointConfigCodec.Mode.LEGACY_BASE && decoded?.decoded?.configuration?.operationUrls?.isNotEmpty() == true) {
            _state.update { it.copy(error = "请先清除独立操作地址，再恢复历史地址模式") }
            return
        }
        editDraft { EndpointDraftEditor.withMode(it, mode) }
    }

    fun editOperationUrl(operation: EndpointOperation, url: String) =
        editDraft { EndpointDraftEditor.withOperationUrl(it, operation, url) }

    fun useSharedStreamEndpoint(enabled: Boolean) {
        val current = _state.value
        if (current.saving || current.purpose != ProfilePurpose.CHAT) return
        if (enabled && current.draft.protocol == WireProtocol.GEMINI_NATIVE) {
            _state.update { it.copy(error = "Gemini 流式请求需要独立操作地址，请填写真正的流式地址") }
            return
        }
        editDraft { EndpointDraftEditor.withSharedStream(it, enabled) }
    }

    fun loadProfile(profile: ModelProfile) {
        val current = _state.value
        if (current.saving || profile.purpose != current.purpose) return
        if (owner?.userId != null && profile.userId != owner?.userId) return
        cancelNetwork()
        val loaded = DraftState(id = profile.id, protocol = profile.protocol, endpoint = profile.endpoint,
            model = profile.model, enabled = profile.enabled, baseRevision = profile.configRevision,
            paramsJson = profile.paramsJson, secretRef = profile.secretRef)
        _state.value = AiConfigUiState(purpose = current.purpose, profiles = current.profiles,
            activeProfileId = current.activeProfileId, draft = loaded, draftBaseline = loaded,
            savedCapabilities = profile.capabilities, generation = current.generation + 1)
    }

    fun clearDraft() {
        val current = _state.value
        if (current.saving) return
        cancelNetwork()
        _state.value = AiConfigUiState(purpose = current.purpose, profiles = current.profiles,
            activeProfileId = current.activeProfileId, generation = current.generation + 1)
    }

    /** Draft validation and persistence use the same endpoint contract. */
    private fun draftProblem(draft: DraftState, requireModel: Boolean): String? {
        if (draft.clearApiKey && draft.apiKey.isNotEmpty()) return "清除密钥与输入新密钥不能同时使用"
        return when (val result = EndpointResolver.normalizeConfiguration(draft.protocol, _state.value.purpose,
            draft.endpoint, draft.paramsJson, draft.model, requireModel)) {
            is EndpointResolver.ConfigurationResult.Ok -> null
            is EndpointResolver.ConfigurationResult.Invalid -> result.reason
        }
    }

    private suspend fun requestIdentity(ticket: DraftRequestTicket): SessionIdentity {
        val identity = sessionManager.identity.first()
        check(identity.userId != null) { "session missing" }
        check(owner == null || owner == identity) { "session changed" }
        check(_state.value.accepts(ticket)) { "draft changed" }
        return identity
    }

    private suspend fun sameSession(identity: SessionIdentity?): Boolean = identity != null &&
        sessionManager.identity.first() == identity && owner == identity

    private suspend fun currentOperation(identity: SessionIdentity?, ticket: DraftRequestTicket): Boolean =
        sameSession(identity) && _state.value.accepts(ticket)

    /** No suspension is allowed between this final ownership check and the state mutation. */
    private fun ownedState(identity: SessionIdentity?, ticket: DraftRequestTicket, state: AiConfigUiState): Boolean =
        identity != null && owner == identity && state.accepts(ticket)

    private suspend fun requestKey(identity: SessionIdentity, draft: DraftState): String? {
        if (draft.clearApiKey) return null
        if (draft.apiKey.isNotEmpty()) return draft.apiKey
        val ref = draft.secretRef ?: return null
        val userId = identity.userId ?: error("session missing")
        val profile = draft.id?.let { repository.find(userId, it) } ?: error("profile missing")
        check(profile.secretRef == ref && profile.configRevision == draft.baseRevision) { "profile changed" }
        val old = EndpointResolver.normalizeConfiguration(profile.protocol, profile.purpose, profile.endpoint,
            profile.paramsJson, profile.model, requireModel = false)
        val next = EndpointResolver.normalizeConfiguration(draft.protocol, profile.purpose, draft.endpoint,
            draft.paramsJson, draft.model, requireModel = false)
        check(old is EndpointResolver.ConfigurationResult.Ok && next is EndpointResolver.ConfigurationResult.Ok &&
            originOf(old.endpoint) == originOf(next.endpoint)) { "key host changed" }
        return repository.resolveSecret(ref) ?: error("secret missing")
    }

    private fun networkError(error: Exception): String = when (error.message) {
        "key host changed" -> "地址协议、host 或端口已改变，请输入新密钥或明确清除密钥"
        "secret missing" -> "已保存的密钥不可用，请重新输入"
        "profile changed", "profile missing" -> "配置已改变，请重新载入后测试"
        "session missing", "session changed" -> "登录状态已改变，请重新打开配置"
        else -> "操作失败，请检查网络与配置后重试"
    }

    fun save() {
        val current = _state.value
        if (current.saving) return
        val problem = draftProblem(current.draft, true)
        if (problem != null) { _state.update { it.copy(error = problem) }; return }
        cancelNetwork()
        val ticket = DraftRequestTicket(current.generation, current.purpose, 0)
        _state.update { it.copy(saving = true, probing = false, modelsLoading = false,
            probePhase = null, error = null, savedMessage = null) }
        viewModelScope.launch {
            var identity = owner
            try {
                val requestOwner = requestIdentity(ticket)
                identity = requestOwner
                val userId = requestOwner.userId!!
                val result = runtime.saveAuthorized(userId, ProfileRepository.Draft(
                    id = current.draft.id, purpose = current.purpose, protocol = current.draft.protocol,
                    endpoint = current.draft.endpoint, model = current.draft.model,
                    apiKey = current.draft.apiKey.takeIf { it.isNotEmpty() }, paramsJson = current.draft.paramsJson,
                    capabilities = current.probeResult ?: if (current.draft.sameConfiguration(current.draftBaseline))
                        current.savedCapabilities else CapabilityProfile(),
                    enabled = current.draft.enabled, baseRevision = current.draft.baseRevision,
                    clearApiKey = current.draft.clearApiKey,
                ), System.currentTimeMillis())
                if (!currentOperation(identity, ticket)) return@launch
                when (result) {
                    is ProfileRepository.SaveResult.Ok -> {
                        val persisted = repository.find(userId, result.profileId) ?: error("profile missing")
                        if (!currentOperation(identity, ticket)) return@launch
                        check(persisted.configRevision == result.configRevision) { "profile changed" }
                        val baseline = current.draft.copy(id = result.profileId, baseRevision = result.configRevision,
                            apiKey = "", clearApiKey = false, endpoint = persisted.endpoint, paramsJson = persisted.paramsJson,
                            secretRef = persisted.secretRef)
                        _state.update { if (ownedState(identity, ticket, it)) it.copy(saving = false,
                            savedMessage = "已保存", draft = baseline, draftBaseline = baseline,
                            savedCapabilities = persisted.capabilities) else it }
                    }
                    is ProfileRepository.SaveResult.Invalid -> _state.update {
                        if (ownedState(identity, ticket, it)) it.copy(error = result.reason) else it }
                    is ProfileRepository.SaveResult.Conflict -> _state.update {
                        if (ownedState(identity, ticket, it)) it.copy(error = "配置已被其他操作修改，请重新载入") else it }
                    is ProfileRepository.SaveResult.Failed -> _state.update {
                        if (ownedState(identity, ticket, it)) it.copy(error = result.reason) else it }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (currentOperation(identity, ticket)) _state.update {
                    if (ownedState(identity, ticket, it)) it.copy(error = networkError(error)) else it }
            } finally {
                if (currentOperation(identity, ticket)) _state.update {
                    if (ownedState(identity, ticket, it)) it.copy(saving = false) else it }
            }
        }
    }

    /** Saving never activates or rebuilds an embedding index. */
    fun activate(profileId: String) {
        val current = _state.value
        if (current.activating || current.saving) return
        val ticket = DraftRequestTicket(current.generation, current.purpose, ++activationSerial)
        _state.update { it.copy(activating = true, error = null, savedMessage = null) }
        viewModelScope.launch {
            var identity = owner
            try {
                val requestOwner = requestIdentity(ticket)
                identity = requestOwner
                val userId = requestOwner.userId!!
                val changed = repository.activate(userId, current.purpose, profileId)
                if (!sameSession(identity)) return@launch
                if (changed && current.purpose == ProfilePurpose.EMBEDDING) onProfileActivated(userId)
                if (_state.value.accepts(ticket)) _state.update { if (ownedState(identity, ticket, it)) it.copy(
                    savedMessage = if (changed) "已设为当前${current.purpose.label()}配置" else null,
                    error = if (changed) null else "激活失败：请选择该用途下已启用的配置") else it }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (currentOperation(identity, ticket)) _state.update {
                    if (ownedState(identity, ticket, it)) it.copy(error = networkError(error)) else it }
            } finally {
                if (sameSession(identity) && ticket.serial == activationSerial) _state.update { it.copy(activating = false) }
            }
        }
    }

    fun delete(profileId: String) {
        val current = _state.value
        if (current.saving || deleteInFlight) return
        val ticket = DraftRequestTicket(current.generation, current.purpose, ++deleteSerial)
        deleteInFlight = true
        viewModelScope.launch {
            var identity = owner
            try {
                val requestOwner = requestIdentity(ticket)
                identity = requestOwner
                val userId = requestOwner.userId!!
                val profile = repository.find(userId, profileId)
                if (!currentOperation(identity, ticket) || ticket.serial != deleteSerial) return@launch
                if (profile?.purpose != current.purpose) return@launch
                val deleted = repository.delete(userId, profileId)
                if (!currentOperation(identity, ticket) || ticket.serial != deleteSerial) return@launch
                if (deleted && _state.value.draft.id == profileId) cancelNetwork()
                _state.update { state ->
                    if (!ownedState(identity, ticket, state) || ticket.serial != deleteSerial) state
                    else if (deleted && state.draft.id == profileId) AiConfigUiState(
                        purpose = state.purpose, profiles = state.profiles, activeProfileId = state.activeProfileId,
                        generation = state.generation + 1, savedMessage = "已删除")
                    else state.copy(savedMessage = if (deleted) "已删除" else null,
                        error = if (deleted) null else "删除失败：配置不存在")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (currentOperation(identity, ticket) && ticket.serial == deleteSerial) _state.update {
                    if (ownedState(identity, ticket, it)) it.copy(error = networkError(error)) else it }
            } finally {
                if (ticket.serial == deleteSerial) deleteInFlight = false
            }
        }
    }

    fun probeDraft() {
        val current = _state.value
        if (current.probing || current.saving) return
        val problem = draftProblem(current.draft, true)
        if (problem != null) { _state.update { it.copy(error = problem) }; return }
        val ticket = DraftRequestTicket(current.generation, current.purpose, ++probeSerial,
            draftFingerprint(current.purpose, current.draft))
        _state.update { it.copy(probing = true, probePhase = null, probeResult = null, probeNotes = emptyList(),
            probeFirstTokenMs = null, probeTotalMs = null, error = null, savedMessage = null) }
        probeJob = viewModelScope.launch {
            try {
                val identity = requestIdentity(ticket)
                val key = requestKey(identity, current.draft)
                if (sessionManager.identity.first() != identity) return@launch
                if (!_state.value.accepts(ticket) || ticket.serial != probeSerial) return@launch
                val outcome = probe.probeDraft(current.purpose, current.draft.protocol,
                    current.draft.endpoint, current.draft.model, key, onStage = { stage ->
                        if (_state.value.accepts(ticket) && ticket.serial == probeSerial)
                            _state.update { it.copy(probePhase = stage.label) }
                    }, paramsJson = current.draft.paramsJson)
                if (sessionManager.identity.first() == identity && ticket.serial == probeSerial)
                    _state.update { it.withProbe(ticket, outcome) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (_state.value.accepts(ticket) && ticket.serial == probeSerial)
                    _state.update { it.copy(error = networkError(error)) }
            } finally {
                if (_state.value.accepts(ticket) && ticket.serial == probeSerial)
                    _state.update { it.copy(probing = false, probePhase = null) }
            }
        }
    }

    fun cancelProbe() {
        val wasProbing = _state.value.probing
        probeSerial++; probeJob?.cancel(); probeJob = null
        // A screen disposal also cancels idle work. Completed evidence still belongs
        // to this draft and must survive a configuration change with the same VM.
        if (!wasProbing) return
        _state.update { it.copy(probing = false, probePhase = null, probeResult = null,
            probeFirstTokenMs = null, probeTotalMs = null, probeNotes = listOf("已取消：未完成的能力保持未测")) }
    }

    fun cancelModels() {
        modelsSerial++; modelsJob?.cancel(); modelsJob = null
        _state.update { it.copy(modelsLoading = false) }
    }

    fun selectModel(model: String) {
        val current = _state.value
        if (current.saving || model == current.draft.model || model !in current.models) return
        cancelNetwork()
        // The catalog was discovered from these same connection settings.
        // An explicit model choice only invalidates capability evidence.
        val edited = current.edited(current.draft.copy(model = model))
        _state.value = edited.copy(
            models = current.models,
            modelQuery = current.modelQuery,
            modelVisibleCount = current.modelVisibleCount,
        )
    }

    fun editModelQuery(query: String) {
        _state.update { it.copy(modelQuery = query, modelVisibleCount = ModelListFilter.PAGE_SIZE) }
    }
    fun showMoreModels() {
        _state.update { it.copy(modelVisibleCount = it.modelVisibleCount + ModelListFilter.PAGE_SIZE) }
    }

    fun fetchModels() {
        val current = _state.value
        if (current.modelsLoading || current.saving) return
        val problem = draftProblem(current.draft, false)
        if (problem != null) { _state.update { it.copy(error = problem) }; return }
        val ticket = DraftRequestTicket(current.generation, current.purpose, ++modelsSerial,
            draftFingerprint(current.purpose, current.draft))
        _state.update { it.copy(modelsLoading = true, error = null, savedMessage = null) }
        modelsJob = viewModelScope.launch {
            try {
                val identity = requestIdentity(ticket)
                val key = requestKey(identity, current.draft)
                if (sessionManager.identity.first() != identity) return@launch
                if (!_state.value.accepts(ticket) || ticket.serial != modelsSerial) return@launch
                val catalog = probe.fetchModelCatalog(current.draft.protocol,
                    current.draft.endpoint, current.draft.model, key,
                    paramsJson = current.draft.paramsJson, purpose = current.purpose)
                if (sessionManager.identity.first() != identity || !_state.value.accepts(ticket) || ticket.serial != modelsSerial)
                    return@launch
                val problem = if (catalog.unsupported) catalog.detail
                else if (catalog.failure == ProbeFailure.NONE) {
                    if (catalog.models.isEmpty()) "模型列表为空，可手动输入模型名称" else null
                } else "模型列表获取失败：" + listOfNotNull(catalog.detail, catalog.failure.label)
                    .filter { it.isNotBlank() }.distinct().joinToString("；")
                _state.update { it.copy(models = if (!catalog.unsupported && catalog.failure == ProbeFailure.NONE)
                    catalog.models else it.models,
                    modelQuery = "", modelVisibleCount = ModelListFilter.PAGE_SIZE, error = problem) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (_state.value.accepts(ticket) && ticket.serial == modelsSerial)
                    _state.update { it.copy(error = networkError(error)) }
            } finally {
                if (_state.value.accepts(ticket) && ticket.serial == modelsSerial)
                    _state.update { it.copy(modelsLoading = false) }
            }
        }
    }

    private fun originOf(endpoint: String): Triple<String, String, Int> {
        val uri = URI(endpoint)
        val scheme = uri.scheme.lowercase()
        return Triple(scheme, uri.host.lowercase(), if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80)
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val container = (context.applicationContext as com.example.myapplication.LearningApplication).container
            @Suppress("UNCHECKED_CAST")
            return AiConfigViewModel(
                database = container.database,
                repository = container.profileRepository,
                runtime = container.profileRuntime,
                probe = container.capabilityProbe,
                sessionManager = container.sessionManager,
                onProfileActivated = container::scheduleIndexing,
            ) as T
        }
    }
}
