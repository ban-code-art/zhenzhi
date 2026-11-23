package com.example.myapplication.data.reader.importer

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.room.withTransaction
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.session.SessionIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class ImportCancellationPendingException(cause: Exception) : Exception("Cancellation persisted; work cleanup pending", cause)

object ReaderImportScheduler {
    /** Cancellation wins only at the durable CAS; a publication transaction may win first. */
    suspend fun cancel(context: Context, jobId: String, expectedIdentity: SessionIdentity): Boolean {
        val application = context.applicationContext as? LearningApplication ?: return false
        val container = application.container
        val owner = expectedIdentity.userId ?: return false
        if (container.sessionManager.identity.first() != expectedIdentity) return false
        val before = container.database.withTransaction {
            if (container.sessionManager.identity.first() != expectedIdentity) return@withTransaction null
            val dao = container.database.readerJobDao()
            val job = dao.find(jobId) ?: return@withTransaction null
            if (job.userId != owner || job.kind != "IMPORT") return@withTransaction null
            if (dao.cancelImport(jobId, owner, System.currentTimeMillis()) != 1) return@withTransaction null
            job
        } ?: return false
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                WorkManager.getInstance(application).cancelUniqueWork("reader-import-$jobId").result.get(10, TimeUnit.SECONDS)
            } catch (failure: Exception) { throw ImportCancellationPendingException(failure) }
            finally {
                // RUNNING may still have an open parser/source stream. Its owner cleans after unwinding.
                if (before.status != "RUNNING") ReaderImportStagingCleanup.clean(container.documentStorage, jobId, false)
            }
        }
        return true
    }

    fun enqueue(context: Context, jobId: String) {
        val request = OneTimeWorkRequestBuilder<ReaderImportWorker>()
            .setInputData(workDataOf(ReaderImportWorker.KEY_JOB_ID to jobId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork("reader-import-$jobId", ExistingWorkPolicy.KEEP, request)
    }
}
