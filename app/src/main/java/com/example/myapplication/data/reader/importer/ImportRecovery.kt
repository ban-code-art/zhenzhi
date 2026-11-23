package com.example.myapplication.data.reader.importer

import android.content.Context
import com.example.myapplication.data.local.AppDatabase
import java.io.File

class ImportRecovery(
    private val context: Context,
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun recover() {
        val dao = database.readerJobDao()
        dao.findRecoverable().forEach { job ->
            val staged = job.stagedPath?.let(::File)
            if (staged != null && staged.isFile) {
                ReaderImportScheduler.enqueue(context, job.id)
            } else {
                dao.markFailed(
                    jobId = job.id,
                    stage = job.stage,
                    errorCode = "SOURCE_PERMISSION_LOST",
                    now = clock(),
                )
            }
        }
    }
}