package com.example.myapplication.ui.knowledge.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.data.reader.export.DataManagementPort
import com.example.myapplication.data.reader.export.DataManagementState
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.ui.components.LocalMainDockBottomInset
import com.example.myapplication.ui.components.UiNoticeHost
import com.example.myapplication.ui.components.UiNoticeVisuals
import com.example.myapplication.ui.navigation.unifiedPageBack
import java.util.UUID

/** Requires a real app-scoped port. This isolated route does not add an app entry or fake service. */
@Composable
fun DataManagementRoute(port: DataManagementPort, onBack: () -> Unit) {
    DataManagementRouteContent(port, null, onBack)
}

/** Production context adds actual session ownership and an optional single navigation request. */
@Composable
internal fun DataManagementRoute(port: DataManagementPort, entryContext: DataManagementEntryContext, onBack: () -> Unit) {
    DataManagementRouteContent(port, entryContext, onBack)
}

@Composable
private fun DataManagementRouteContent(port: DataManagementPort, entryContext: DataManagementEntryContext?, onBack: () -> Unit) {
    val factory = remember(port) { DataManagementViewModel.Factory(port) }
    val model: DataManagementViewModel = viewModel(factory = factory)
    val state by model.state.collectAsState()
    val owner = if (entryContext == null) state.identity else entryContext.currentIdentity
    val request = entryContext?.initialRequest
    var initialHandled by rememberSaveable(request?.id) { mutableStateOf(false) }
    var initialMessage by rememberSaveable(request?.id) { mutableStateOf<String?>(null) }
    var initialMessageOwner by rememberSaveable(request?.id) { mutableStateOf<String?>(null) }
    var routeFailure by remember(owner) { mutableStateOf<DataManagementNotice?>(null) }

    fun initialFeedback(message: String) {
        initialMessage = message
        initialMessageOwner = owner?.dataManagementKey()
    }
    LaunchedEffect(port, state.identity, state.loading, state.busy, owner, request?.id) {
        if (owner != state.identity) return@LaunchedEffect
        val reservedForInitialSelection = request != null && !initialHandled &&
            initialDocumentDecision(request, state, owner) == DataManagementInitialDecision.Select
        // A single-book request queues its refresh itself, before the authoritative selection ACK.
        if (!reservedForInitialSelection) runCatching { model.refreshDocuments() }.onFailure {
            routeFailure = DataManagementNotice(UUID.randomUUID().toString(), state.identity, "文档列表暂时未能读取，请稍后重新打开。")
        }
    }
    LaunchedEffect(request?.id, owner, state.identity, state.loading, state.busy, state.step,
        state.documents, state.selectionPending, state.selectionResolution, state.error, state.capabilities) {
        if (request == null) return@LaunchedEffect
        if (!initialHandled) when (val decision = initialDocumentDecision(request, state, owner)) {
            DataManagementInitialDecision.Wait -> Unit
            is DataManagementInitialDecision.KeepCurrent -> {
                initialHandled = true // Never queue it for the next SELECTION state or account.
                initialFeedback(decision.message)
            }
            DataManagementInitialDecision.Select -> {
                initialHandled = true // Consume before the command can publish/re-enter synchronously.
                runCatching { model.selectInitialDocument(request.documentId, request.identity, request.id) }
                    .onSuccess { issued ->
                        if (!issued) initialFeedback("原操作状态已变化，未加入单书预选。请检查当前内容后重试。")
                    }
                    .onFailure { initialFeedback("单书选择暂时未能保存，原操作已保留，请稍后重试。") }
            }
        }
        val resolution = state.selectionResolution
        if (initialHandled && owner == request.identity && state.identity == request.identity &&
            resolution?.requestId == request.id && resolution.identity == request.identity && !resolution.accepted
        ) {
            val document = state.documents.firstOrNull { it.id == request.documentId }
            initialFeedback(when {
                document == null -> "当前账号无法访问这本文档，或文档已被移除。原选择已保留，请从书库重新选择。"
                !document.available -> document.unavailableReason ?: "这本文档尚未就绪，暂时无法导出。原选择已保留。"
                else -> state.error?.message ?: "所选内容暂时不可用，原选择已保留。"
            })
        }
    }
    val entryNotice = dataManagementIdentityFromKey(initialMessageOwner)?.let { identity ->
        initialMessage?.let { DataManagementNotice("data.entry.${request?.id}", identity, it) }
    }
    val ledger = remember(port) { DataManagementFeedbackRegistry.forPort(port) }
    // Keep the original rememberSaveable launcher scope while identity is unknown.
    // A published different owner/epoch gets its own scope and cannot receive old callbacks.
    val registryIdentity = dataManagementPickerRegistryIdentity(state, model.pendingPicker())
    key(registryIdentity) {
        DataManagementPickerRoute(model, state, owner, ledger, entryNotice, routeFailure, onBack)
    }
}

/** Composition/registration scope only; Controller still validates every queued receipt. */
internal fun dataManagementPickerRegistryIdentity(state: DataManagementState, pending: DataManagementPickerTicket?): SessionIdentity =
    if (state.loading && state.identity.userId == null && state.identity.epoch == 0L && pending != null) pending.identity else state.identity

@Composable
private fun DataManagementPickerRoute(
    model: DataManagementViewModel,
    state: DataManagementState,
    owner: SessionIdentity?,
    ledger: DataManagementFeedbackLedger,
    entryNotice: DataManagementNotice?,
    routeFailure: DataManagementNotice?,
    onBack: () -> Unit,
) {
    val initialTicket = model.pendingPicker()?.takeIf {
        it.identity == state.identity || (state.loading && state.identity.userId == null && state.identity.epoch == 0L)
    }
    var saveTicket by remember { mutableStateOf(initialTicket?.takeIf { it.kind == DataManagementPickerKind.SAVE_EXPORT }) }
    var restoreTicket by remember { mutableStateOf(initialTicket?.takeIf { it.kind == DataManagementPickerKind.OPEN_RESTORE }) }
    val snackbar = remember(owner) { SnackbarHostState() }
    val currentOwner by rememberUpdatedState(owner)
    val dockBottomInset = LocalMainDockBottomInset.current
    var localFailure by remember(owner) { mutableStateOf<DataManagementNotice?>(null) }
    fun showFailure(identity: SessionIdentity) {
        if (currentOwner == identity) localFailure = DataManagementNotice(
            UUID.randomUUID().toString(), identity, "操作暂时不可用，请稍后重试。",
        )
    }
    fun completePicker(ticket: DataManagementPickerTicket, uri: String?) {
        // A known session change revokes immediately; unknown startup still queues the original
        // ticket so the existing controller can validate it after service identity initialization.
        val acceptedUri = uri.takeIf { currentOwner == null || currentOwner == ticket.identity }
        runCatching { model.completePicker(ticket, acceptedUri) }.onFailure { showFailure(ticket.identity) }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val captured = saveTicket
        saveTicket = null
        if (captured != null) completePicker(captured, uri?.toString())
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val captured = restoreTicket
        restoreTicket = null
        if (captured != null) completePicker(captured, uri?.toString())
    }
    val notices = buildList {
        addAll(listOfNotNull(localFailure, routeFailure, entryNotice))
        if (owner == state.identity) addAll(ledger.notices(state))
    }.filter { it.identity == owner }
    LaunchedEffect(snackbar, owner, notices.map { it.id }) {
        snackbar.currentSnackbarData?.dismiss()
        for (notice in notices) {
            if (!ledger.claim(notice, currentOwner)) continue
            val result = snackbar.showSnackbar(UiNoticeVisuals(
                eventId = notice.id,
                ownerKey = notice.identity,
                message = notice.message,
                actionLabel = if (notice.reportId != null) "完成" else null,
                withDismissAction = true,
                duration = if (notice.reportId != null) SnackbarDuration.Long else SnackbarDuration.Short,
            ))
            if (result == SnackbarResult.ActionPerformed && notice.reportId != null && currentOwner == notice.identity) {
                val latest = model.state.value
                if (latest.identity == notice.identity && latest.report?.jobId == notice.reportId && !latest.busy) {
                    runCatching { model.handle(DataManagementAction.DismissReport) }.onFailure { showFailure(notice.identity) }
                }
            }
        }
    }
    val visibleState = if (owner == state.identity) state else DataManagementState(
        identity = owner ?: SessionIdentity(null, 0L), loading = true, capabilities = state.capabilities,
    )
    val requestBack = unifiedPageBack(onBack) // Leaving is independent from cancelling the job.
    Box(Modifier.fillMaxSize()) {
        DataManagementScreen(visibleState, onAction = { action ->
            val origin = model.state.value.identity
            if (currentOwner != origin) return@DataManagementScreen
            runCatching {
                when (action) {
                    is DataManagementAction.SaveExport -> model.beginSavePicker(action.previewId)?.let { ticket ->
                        saveTicket = ticket
                        try { save.launch("学习备份.zip") } catch (error: Exception) {
                            saveTicket = null
                            model.completePicker(ticket, null)
                            throw error
                        }
                    }
                    DataManagementAction.PickRestore -> model.beginRestorePicker()?.let { ticket ->
                        restoreTicket = ticket
                        try { restore.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
                        catch (error: Exception) {
                            restoreTicket = null
                            model.completePicker(ticket, null)
                            throw error
                        }
                    }
                    else -> model.handle(action)
                }
            }.onFailure { showFailure(origin) }
        }, onBack = requestBack, entryMessage = entryNotice?.takeIf { it.identity == owner }?.message)
        UiNoticeHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).testTag("data-management-notice")
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp + dockBottomInset),
            ownerKey = owner,
        )
    }
}
