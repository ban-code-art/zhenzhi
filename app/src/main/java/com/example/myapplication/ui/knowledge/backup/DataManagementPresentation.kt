package com.example.myapplication.ui.knowledge.backup

import com.example.myapplication.data.reader.export.DataManagementOperation
import com.example.myapplication.data.reader.export.DataManagementReport
import com.example.myapplication.data.reader.export.DataManagementResult
import com.example.myapplication.data.reader.export.DataManagementState
import com.example.myapplication.data.reader.export.DataManagementStep
import com.example.myapplication.data.session.SessionIdentity

/** A navigation request belongs to the account/epoch at entry, never the next signed-in account. */
internal data class DataManagementInitialDocumentRequest(
    val id: String,
    val documentId: String,
    val identity: SessionIdentity,
)

/** Null currentIdentity means the actual session is still being read, not a signed-out account. */
internal data class DataManagementEntryContext(
    val currentIdentity: SessionIdentity?,
    val initialRequest: DataManagementInitialDocumentRequest? = null,
)

internal sealed interface DataManagementInitialDecision {
    data object Wait : DataManagementInitialDecision
    data object Select : DataManagementInitialDecision
    data class KeepCurrent(val message: String) : DataManagementInitialDecision
}

internal fun initialDocumentDecision(
    request: DataManagementInitialDocumentRequest,
    state: DataManagementState,
    currentIdentity: SessionIdentity?,
): DataManagementInitialDecision {
    if (currentIdentity == null) return DataManagementInitialDecision.Wait
    if (currentIdentity != request.identity) return DataManagementInitialDecision.KeepCurrent(
        "账号已变化，原单书预选已取消。请从当前账号的书库重新选择。",
    )
    if (state.loading && state.identity.userId == null && state.identity.epoch == 0L) {
        return DataManagementInitialDecision.Wait
    }
    if (state.identity != request.identity) return DataManagementInitialDecision.Wait
    if (state.loading) return DataManagementInitialDecision.Wait
    if (!state.signedIn) return DataManagementInitialDecision.KeepCurrent("登录后才能导出学习数据，请登录后从书库重新打开。")
    if (!state.canEditSelection || state.step != DataManagementStep.SELECTION || state.selectionPending) {
        val message = when {
            state.busy -> "当前已有导出或恢复任务，已打开原任务，未更改文档选择。"
            state.step == DataManagementStep.REPORT -> "已打开上次操作的报告。完成并关闭报告后，可从书库重新导出这本书。"
            state.step == DataManagementStep.EXPORT_PREVIEW -> "当前已有导出预览，未更改原选择。请先处理或调整这份预览。"
            state.step == DataManagementStep.RESTORE_PREVIEW -> "当前已有恢复方案，未更改原操作。请先处理这份方案。"
            else -> "当前正在处理原选择，未加入新的单书预选。请稍后从书库重新打开。"
        }
        return DataManagementInitialDecision.KeepCurrent(message)
    }
    if (state.capabilities.forVersion(state.selection.version)?.exportSupported != true) {
        return DataManagementInitialDecision.KeepCurrent("当前备份版本尚不可用，未更改原选择。请选择受支持的版本后重试。")
    }
    val document = state.documents.firstOrNull { it.id == request.documentId }
    if (document == null && state.error != null) return DataManagementInitialDecision.KeepCurrent(
        "文档列表暂时未能完整读取，未更改选择。请处理当前错误后从书库重试。",
    )
    if (document?.available == false) return DataManagementInitialDecision.KeepCurrent(
        document.unavailableReason ?: "这本文档尚未就绪，暂时无法导出。原选择已保留。",
    )
    // A loaded catalog may predate a new import. The controller queues refresh before selection;
    // only the service's exact request acknowledgement can declare that absent document invalid.
    return DataManagementInitialDecision.Select
}

internal fun knownDataManagementIdentity(state: DataManagementState): SessionIdentity? =
    state.identity.takeIf { it.userId != null || it.epoch != 0L || !state.loading }

internal fun SessionIdentity.dataManagementKey(): String = "${userId ?: "signed-out"}:$epoch"

internal fun dataManagementIdentityFromKey(key: String?): SessionIdentity? {
    val values = key?.split(':', limit = 2) ?: return null
    if (values.size != 2) return null
    val userId: Long? = when (values[0]) {
        "signed-out" -> null
        else -> values[0].toLongOrNull()?.takeIf { it > 0L } ?: return null
    }
    val epoch = values[1].toLongOrNull()?.takeIf { it >= 0L } ?: return null
    return SessionIdentity(userId, epoch)
}

internal fun DataManagementOperation.productName(): String = if (this == DataManagementOperation.EXPORT) "导出" else "恢复"

internal fun DataManagementReport.productTitle(): String = when (result) {
    DataManagementResult.COMPLETED -> "${operation.productName()}完成"
    DataManagementResult.PARTIAL -> "${operation.productName()}部分完成"
    DataManagementResult.FAILED -> "${operation.productName()}未完成"
    DataManagementResult.CANCELLED -> "任务已取消"
}
