package com.example.myapplication.ui.knowledge.backup

import com.example.myapplication.data.reader.export.DataManagementError
import com.example.myapplication.data.reader.export.DataManagementPort
import com.example.myapplication.data.reader.export.DataManagementState
import com.example.myapplication.data.session.SessionIdentity
import java.security.MessageDigest
import java.util.WeakHashMap

internal data class DataManagementNotice(
    val id: String,
    val identity: SessionIdentity,
    val message: String,
    /** Only the explicit notice action completes a report; visual dismissal leaves it readable. */
    val reportId: String? = null,
)

/** Shared by the same real application port, including separate Settings/Shelf ViewModels. */
internal object DataManagementFeedbackRegistry {
    private val ledgers = WeakHashMap<DataManagementPort, DataManagementFeedbackLedger>()

    @Synchronized
    fun forPort(port: DataManagementPort): DataManagementFeedbackLedger =
        ledgers.getOrPut(port) { DataManagementFeedbackLedger() }
}

/** Owns presentation receipts only, never a job, URI, report or service cancellation. */
internal class DataManagementFeedbackLedger {
    private val consumed = linkedSetOf<String>()
    private data class ErrorEvent(val error: DataManagementError, val context: String, val id: String)
    private val lastErrors = linkedMapOf<SessionIdentity, ErrorEvent>()
    private var nextError = 0L

    fun notices(state: DataManagementState): List<DataManagementNotice> {
        if (!state.signedIn) return emptyList()
        val identity = state.identity
        val owner = identity.dataManagementKey()
        return buildList {
            val error = state.error
            if (error == null) lastErrors.remove(identity)
            else {
                val context = fingerprint(listOf(
                    owner, state.step.name, state.job?.takeIf { it.identity == identity }?.id.orEmpty(),
                    state.exportPreview?.takeIf { it.identity == identity }?.id.orEmpty(),
                    state.restorePlan?.takeIf { it.identity == identity }?.id.orEmpty(),
                    state.selectionResolution?.takeIf { it.identity == identity }?.requestId.orEmpty(),
                    error.message, error.retryable.toString(),
                ))
                val previous = lastErrors[identity]
                val event = if (previous != null && previous.error === error && previous.context == context) previous else {
                    ErrorEvent(error, context, "data.$owner.error.$context.${++nextError}")
                        .also { lastErrors[identity] = it }
                }
                add(DataManagementNotice(event.id, identity, error.message))
            }
            if (state.recoveryIssues.isNotEmpty()) {
                val signature = fingerprint(state.recoveryIssues.map { "${it.retryable}:${it.message}" })
                add(DataManagementNotice("data.$owner.recovery.$signature", identity,
                    "${state.recoveryIssues.size} 条上次恢复记录需要检查，记录已保留，未重新恢复。"))
            }
            state.report?.takeIf { it.identity == identity }?.let { report ->
                add(DataManagementNotice("data.$owner.report.${report.jobId}.${report.result.name}", identity,
                    "${report.productTitle()}，请查看本次实际处理明细。", report.jobId))
            }
        }
    }

    /** Claim before suspension: leaving/re-entering during display cannot replay the same event. */
    fun claim(notice: DataManagementNotice, currentIdentity: SessionIdentity?): Boolean {
        if (notice.identity != currentIdentity || !consumed.add(notice.id)) return false
        if (consumed.size > 256) consumed.remove(consumed.first())
        return true
    }

    private fun fingerprint(values: List<String>): String {
        val bytes = values.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
