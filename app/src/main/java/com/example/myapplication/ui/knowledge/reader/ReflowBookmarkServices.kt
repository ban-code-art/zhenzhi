package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.data.reader.notes.BookmarkToggleResult
import com.example.myapplication.data.reader.notes.ReaderBookmarkView
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.DocumentAccess
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReaderLocator
import kotlinx.coroutines.flow.Flow

/** Existing document access and current-user repository transports for the isolated host. */
class ReflowBookmarkServices(
    val identityNow: suspend () -> SessionIdentity,
    val access: DocumentAccess,
    val observe: (Long, String) -> Flow<List<ReaderBookmarkView>>,
    val ensure: suspend (AccessContext, String, ReaderLocator, String) -> OperationResult<ReaderBookmarkView>,
    val toggle: suspend (AccessContext, String, ReaderLocator, String) -> OperationResult<BookmarkToggleResult>,
)
