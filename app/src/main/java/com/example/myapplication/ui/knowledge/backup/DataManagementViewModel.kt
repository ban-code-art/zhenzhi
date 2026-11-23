package com.example.myapplication.ui.knowledge.backup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.myapplication.data.reader.export.DataManagementPort
import com.example.myapplication.data.session.SessionIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Subscribes to app-scoped service state; clearing this VM never cancels the service's job. */
class DataManagementViewModel(
    port: DataManagementPort,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val controller = DataManagementController(port, savedStateHandle.keys()
        .filter { it.startsWith(DataManagementController.PREFIX) }
        .mapNotNull { key -> (savedStateHandle.get<Any?>(key) as? String)?.let { key to it } }.toMap())
    private val displayedState = MutableStateFlow(controller.displayState())
    val state = displayedState.asStateFlow()

    init {
        persist()
        viewModelScope.launch {
            port.state.collect { controller.observeState(it); persist() }
        }
    }

    fun handle(action: DataManagementAction) {
        try { controller.handle(action) } finally { persist() }
    }
    fun refreshDocuments() {
        val current = displayedState.value
        try {
            if (current.signedIn && !current.loading) controller.refreshDocuments(current.identity)
        } finally { persist() }
    }
    internal fun selectInitialDocument(documentId: String, expectedIdentity: SessionIdentity, requestId: String): Boolean =
        try { controller.selectInitialDocument(documentId, expectedIdentity, requestId) } finally { persist() }
    fun beginSavePicker(previewId: String): DataManagementPickerTicket? = controller.beginSavePicker(previewId).also { persist() }
    fun beginRestorePicker(): DataManagementPickerTicket? = controller.beginRestorePicker().also { persist() }
    fun pendingPicker() = controller.pendingPicker()
    fun completePicker(ticket: DataManagementPickerTicket, uri: String?) {
        try { controller.completePicker(ticket, uri) } finally { persist() }
    }

    private fun persist() {
        displayedState.value = controller.displayState()
        val saved = controller.savedStateSnapshot()
        savedStateHandle.keys().filter { it.startsWith(DataManagementController.PREFIX) && it !in saved }
            .forEach { savedStateHandle.remove<String>(it) }
        saved.forEach { (key, value) -> savedStateHandle[key] = value }
    }

    class Factory(private val port: DataManagementPort) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(DataManagementViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return DataManagementViewModel(port, extras.createSavedStateHandle()) as T
        }
    }
}
