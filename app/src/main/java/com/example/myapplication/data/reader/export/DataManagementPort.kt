package com.example.myapplication.data.reader.export

import com.example.myapplication.data.session.SessionIdentity
import kotlinx.coroutines.flow.StateFlow

/**
 * App-scoped real service owns jobs and publishes authoritative state. Methods enqueue commands;
 * every command must recheck expectedIdentity, capability, preview/plan and commit preconditions.
 * UI recreation/reopen never starts a replacement job. No production fake/default port is supplied.
 */
interface DataManagementPort {
    val state: StateFlow<DataManagementState>
    /** Reload the current owner's visible document catalog without replacing other service state. */
    fun refreshDocuments(expectedIdentity: SessionIdentity) = Unit
    /** Publish exact requestId/expectedIdentity resolution and authoritative selection in one state. */
    fun changeSelection(selection: DataManagementSelection, expectedIdentity: SessionIdentity, requestId: String)
    fun previewExport(expectedIdentity: SessionIdentity)
    fun saveExport(previewId: String, destinationUri: String, expectedIdentity: SessionIdentity)
    fun prepareRestore(sourceUri: String, expectedIdentity: SessionIdentity)
    fun chooseConflict(policy: DataManagementConflictPolicy, expectedIdentity: SessionIdentity)
    fun confirmRestore(planId: String, expectedIdentity: SessionIdentity)
    fun cancel(jobId: String, expectedIdentity: SessionIdentity)
    fun reopen(jobId: String, expectedIdentity: SessionIdentity)
    fun dismissReport(expectedIdentity: SessionIdentity)
}
