package com.example.myapplication.ui.knowledge.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.ViewModelProvider
import com.example.myapplication.LearningApplication
import kotlinx.coroutines.flow.first

class ReflowReaderRuntimeViewModelFactory(
    private val application: LearningApplication,
    private val documentId: String,
    private val host: ReflowRuntimeHost,
    private val initialLocatorJson: String? = null,
    private val courseBookId: String? = null,
    private val courseChapterKey: String? = null,
    private val allowDevelopmentDraft: Boolean = false,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return createModel(modelClass, null)
    }
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
        if (courseBookId == null) createModel(modelClass, createReaderRuntimeRecovery(application, extras.createSavedStateHandle(), documentId, "REFLOW"))
        else createModel(modelClass, null, createCourseReaderRuntimeRecovery(application, extras.createSavedStateHandle(), courseBookId))

    @Suppress("UNCHECKED_CAST")
    private fun <T : ViewModel> createModel(modelClass: Class<T>, recovery: ReaderRuntimeRecovery?, courseRecovery: CourseReaderRuntimeRecovery? = null): T {
        require(modelClass.isAssignableFrom(ReflowReaderRuntimeViewModel::class.java))
        val container = application.container
        val preferenceStore = com.example.myapplication.data.reader.ReaderPreferenceStore(application)
        return ReflowReaderRuntimeViewModel(documentId, initialLocatorJson, container.sessionManager.identity,
            container.readerRepository::openSession, container.createIsolatedReflowRuntimeFactory(host),
            container.applicationScope, recovery,
            courseBookId?.let { bookId -> CourseReaderScope(bookId, courseChapterKey) { context ->
                container.courseReadingSequenceProvider.load(context, bookId, allowDevelopmentDraft)
            } }, courseRecovery,
            ReflowAnnotationServices(
                identityNow = { container.sessionManager.identity.first() },
                access = container.roomDocumentAccess,
                create = { context, document, kind, locator, quote, style, note, targets, guard ->
                    container.annotationRepository.create(context, document, kind, locator, quote, style,
                        noteText = note, targets = targets, writeGuard = guard)
                },
                update = { context, annotationId, revision, note, style, guard ->
                    container.annotationRepository.update(context, annotationId, revision,
                        noteText = note, style = style, writeGuard = guard)
                },
                observe = container.annotationRepository::observeAnnotations,
                removeText = { context,id,revision,guard -> container.annotationRepository.removeText(context,id,revision,guard) },
                restoreText = { context,id,revision,guard -> container.annotationRepository.restoreText(context,id,revision,guard) },
            ), ReflowBookmarkServices(
                identityNow = { container.sessionManager.identity.first() },
                access = container.roomDocumentAccess,
                observe = container.annotationRepository::observeBookmarks,
                ensure = container.annotationRepository::ensureBookmark,
                toggle = container.annotationRepository::toggleBookmark,
            ), ReflowReaderSettingsServices(
                identityNow = { container.sessionManager.identity.first() },
                observeDefaults = preferenceStore::observeDefaults,
                saveDocument = container.readerRepository::saveSettings,
                saveDefaults = preferenceStore::saveDefaults,
                currentRevision = { id -> container.database.readerCatalogDao().findDocument(id)?.contentRevision },
                saveDocumentRevisionBound = { user, id, json, revision, valid ->
                    container.readerRepository.saveSettings(user, id, json, revision, valid)
                },
            )) as T
    }
}

