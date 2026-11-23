package com.example.myapplication.data.reader

import androidx.room.withTransaction
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReadingSequence

/**
 * Metadata bridge for isolated course navigation. No renderer, progress writer or review mutation.
 * The seeder owns approval evidence; this binding consumes its atomically published metadata.
 */
class RoomCourseReadingSequenceProvider(
    database: AppDatabase,
    repository: ReaderDocumentRepository,
    identity: suspend () -> SessionIdentity,
) {
    private val provider = CourseReadingSequenceProvider(
        readCatalog = { bookId ->
            database.withTransaction {
                val dao = database.courseBookDao()
                dao.findBook(bookId)?.let { CourseCatalogSnapshot(it, dao.listAllChapters(bookId)) }
            }
        },
        readDocument = { context, documentId ->
            when (val result = repository.openSession(context.userId, documentId)) {
                is OperationResult.Ok -> OperationResult.Ok(result.value.readingSequence(), context.requestId)
                is OperationResult.Rejected -> result
                is OperationResult.Failed -> result
                is OperationResult.Conflict -> result
            }
        },
        identity = identity,
    )

    suspend fun load(
        context: AccessContext,
        bookId: String,
        allowDevelopmentDraft: Boolean = false,
    ): OperationResult<ReadingSequence> = provider.load(context, bookId, allowDevelopmentDraft)
}
