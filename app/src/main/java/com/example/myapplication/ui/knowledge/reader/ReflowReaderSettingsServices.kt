package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.domain.reader.*
import kotlinx.coroutines.flow.Flow

class ReflowReaderSettingsServices(
    val identityNow: suspend () -> SessionIdentity,
    val observeDefaults: (Long) -> Flow<ReaderSettings>,
    val saveDocument: suspend (Long,String,String) -> OperationResult<Unit>,
    val saveDefaults: suspend (Long,ReaderSettings) -> OperationResult<Unit>,
    val currentRevision: suspend (String) -> String?,
    val saveDocumentRevisionBound: (suspend (Long, String, String, String?, suspend () -> Boolean) -> OperationResult<Unit>)?,
) {
    constructor(identityNow: suspend () -> SessionIdentity, observeDefaults: (Long) -> Flow<ReaderSettings>,
        saveDocument: suspend (Long,String,String) -> OperationResult<Unit>,
        saveDefaults: suspend (Long,ReaderSettings) -> OperationResult<Unit>, currentRevision: suspend (String) -> String?) :
        this(identityNow, observeDefaults, saveDocument, saveDefaults, currentRevision, null)

    suspend fun saveForRevision(userId: Long, documentId: String, json: String, expectedRevision: String?,
        identityValid: suspend () -> Boolean): OperationResult<Unit> {
        if (!identityValid()) return OperationResult.Rejected("SESSION_CHANGED")
        return saveDocumentRevisionBound?.invoke(userId, documentId, json, expectedRevision, identityValid)
            ?: saveDocument(userId, documentId, json)
    }
}

internal fun reflowSettingsUnsupported(settings: ReaderSettings): String? =
    when(settings.flowMode){ReaderFlowMode.PAGINATED,ReaderFlowMode.SCROLL->null}

/** Actual surface preferences. Unsupported requested mode remains visible separately in UI state. */
internal fun reflowLayoutSettings(settings: ReaderSettings) = ReaderLayoutPreferences(
    settings.fontScale,settings.lineHeight,settings.marginDp,null,settings.fontColor.resolve(settings.theme),
    when(settings.theme) { ReaderTheme.LIGHT -> "#F3F8F5";ReaderTheme.SEPIA -> "#F7F0E3";ReaderTheme.NIGHT -> "#1E2622" },
    settings.flowMode,
)
