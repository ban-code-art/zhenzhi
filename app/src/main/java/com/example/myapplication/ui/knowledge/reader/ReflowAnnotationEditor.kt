package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.data.reader.notes.AnnotationKind
import com.example.myapplication.data.reader.notes.AnnotationStyle
import com.example.myapplication.data.reader.notes.AnnotationTargetInput
import com.example.myapplication.data.reader.notes.AnnotationTargetValidation
import com.example.myapplication.data.reader.notes.AnnotationWriteCheckpoint
import com.example.myapplication.data.reader.notes.AnnotationWriteGuard
import com.example.myapplication.data.reader.notes.ReaderAnnotationView
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.DocumentAccess
import com.example.myapplication.domain.reader.DocumentSnapshot
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorValidator
import com.example.myapplication.domain.reader.ReaderSelectionEvent
import com.example.myapplication.domain.reader.ReaderSelectionFragment
import com.example.myapplication.domain.reader.ReaderSelectionPhase
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first

/** Production dependencies; the public VM constructor can accept this without exposing editor types. */
class ReflowAnnotationServices(
    val identityNow: suspend () -> SessionIdentity,
    val access: DocumentAccess,
    val create: suspend (AccessContext, String, AnnotationKind, ReaderLocator, String?, AnnotationStyle, String?, List<AnnotationTargetInput>, AnnotationWriteGuard) -> OperationResult<ReaderAnnotationView>,
    val update: suspend (AccessContext, String, Long, String?, AnnotationStyle, AnnotationWriteGuard) -> OperationResult<ReaderAnnotationView>,
    val observe: (Long, String) -> Flow<List<ReaderAnnotationView>>,
    val removeText: (suspend (AccessContext,String,Long,AnnotationWriteGuard) -> OperationResult<ReaderAnnotationView>)?,
    val restoreText: (suspend (AccessContext,String,Long,AnnotationWriteGuard) -> OperationResult<ReaderAnnotationView>)?,
) {
    constructor(identityNow: suspend () -> SessionIdentity, access: DocumentAccess,
        create: suspend (AccessContext, String, AnnotationKind, ReaderLocator, String?, AnnotationStyle, String?, List<AnnotationTargetInput>, AnnotationWriteGuard) -> OperationResult<ReaderAnnotationView>,
        update: suspend (AccessContext, String, Long, String?, AnnotationStyle, AnnotationWriteGuard) -> OperationResult<ReaderAnnotationView>,
        observe: (Long,String)->Flow<List<ReaderAnnotationView>>) : this(identityNow,access,create,update,observe,null,null)
    /** Preserve the original reflection constructor for isolated test/legacy hosts. */
    constructor(identityNow: suspend () -> SessionIdentity, access: DocumentAccess,
        create: suspend (AccessContext, String, AnnotationKind, ReaderLocator, String?, AnnotationStyle, String?, List<AnnotationTargetInput>, AnnotationWriteGuard) -> OperationResult<ReaderAnnotationView>,
        update: suspend (AccessContext, String, Long, String?, AnnotationStyle, AnnotationWriteGuard) -> OperationResult<ReaderAnnotationView>) :
        this(identityNow, access, create, update, { _, _ -> emptyFlow() })
}

internal data class ReflowAnnotationDraft(
    val draftId: String,
    val capture: ReflowSelectionCapture,
    val fragment: ReaderSelectionFragment,
    val kind: AnnotationKind,
    val noteText: String?,
    val style: AnnotationStyle,
    val identity: SessionIdentity,
    val originalSnapshot: DocumentSnapshot,
    val annotationId: String? = null,
    val baseRevision: Long? = null,
    val savedNoteText: String? = "",
    val savedStyle: AnnotationStyle = AnnotationStyle(),
    val fragments: List<ReaderSelectionFragment> = listOf(fragment),
) {
    val isDirty: Boolean get() = noteText != savedNoteText || style != savedStyle
    val quote: String get() = fragments.joinToString("\n") { it.quote }
}

internal data class ReflowAnnotationEditorState(
    val draft: ReflowAnnotationDraft? = null,
    val saving: Boolean = false,
    val error: String? = null,
    val saved: ReaderAnnotationView? = null,
    val outcomeUnknown: Boolean = false,
    val sourceUnavailable: Boolean = false,
    val conflictRevision: Long? = null,
) {
    val dirty: Boolean get() = draft?.isDirty == true
    val editBlocked: Boolean get() = saving || outcomeUnknown
    val draftId: String? get() = draft?.draftId
    val capture: ReflowSelectionCapture? get() = draft?.capture
    val fragment: ReaderSelectionFragment? get() = draft?.fragment
    val kind: AnnotationKind? get() = draft?.kind
    val noteText: String get() = draft?.noteText.orEmpty()
    val style: AnnotationStyle get() = draft?.style ?: AnnotationStyle()
}

/**
 * Only immutable local state and synchronous canonical proof run under monitor. In particular,
 * neither DataStore/Room IO nor a suspendable native/VM action can acquire this lock indirectly.
 */
internal class ReflowAnnotationEditor(
    private val services: ReflowAnnotationServices,
    private val validateOwner: (ReflowAnnotationDraft) -> Boolean,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val monitor = Any()
    private val mutableState = MutableStateFlow(ReflowAnnotationEditorState())
    val state = mutableState.asStateFlow()
    private data class Pending(val draft: ReflowAnnotationDraft, val context: AccessContext, val targets: List<AnnotationTargetInput>)
    private var pending: Pending? = null

    fun begin(capture: ReflowSelectionCapture, fragment: ReaderSelectionFragment, kind: AnnotationKind,
        identity: SessionIdentity, originalSnapshot: DocumentSnapshot): OperationResult<Unit> =
        begin(capture, listOf(fragment), kind, identity, originalSnapshot)

    fun begin(capture: ReflowSelectionCapture, fragments: List<ReaderSelectionFragment>, kind: AnnotationKind,
        identity: SessionIdentity, originalSnapshot: DocumentSnapshot): OperationResult<Unit> = synchronized(monitor) {
        if (state.value.draft != null) return@synchronized reject("ANNOTATION_EDITOR_ALREADY_OPEN")
        val token = capture.binding.token
        if (fragments.isEmpty()) return@synchronized reject("INVALID_ANNOTATION_DRAFT")
        val fragment = fragments.first()
        val locator = fragment.locator as? ReaderLocator.Reflow ?: return@synchronized reject("REFLOW_LOCATOR_REQUIRED")
        if (kind !in setOf(AnnotationKind.NOTE, AnnotationKind.HIGHLIGHT) ||
            !ReflowSelectionValidation.capture(capture) || identity.userId != token.userId || identity.epoch != token.sessionEpoch ||
            originalSnapshot.documentId != token.documentId || originalSnapshot.contentRevision != token.contentRevision ||
            originalSnapshot.ownerUserId?.let { it != token.userId } == true || originalSnapshot.accessEpoch < 0 ||
            !ReflowSelectionValidation.envelope(ReflowSelectionEnvelope(capture.binding, capture.eventSequence,
                ReaderSelectionEvent(token, capture.selectionId, ReaderSelectionPhase.STABLE, fragments, emptyList()))) ||
            fragments.any { !ReaderLocatorValidator.validate(it.locator).isValid } ||
            fragments.sumOf { it.quote.length.toLong() } + fragments.size - 1 > 8192) return@synchronized reject("INVALID_ANNOTATION_DRAFT")
        val id = newId()
        if (!ReflowSelectionValidation.identity(id)) return@synchronized reject("INVALID_ANNOTATION_DRAFT")
        val draft = ReflowAnnotationDraft(id, capture.copy(binding = capture.binding.copy(token = token.copy())),
            ReaderSelectionFragment(locator.copy(), fragment.quote), kind, "", AnnotationStyle(), identity.copy(),
            freezeAnnotationSnapshot(originalSnapshot), fragments = Collections.unmodifiableList(fragments.map {
                ReaderSelectionFragment(AnnotationTargetValidation.freezeLocator(it.locator), it.quote)
            }))
        // VM establishes STABLE ownership before this call and enters EDITING with this draft ID.
        // validateOwner deliberately runs only when saving, after that transition has committed.
        pending = null
        mutableState.value = ReflowAnnotationEditorState(draft = draft)
        ok()
    }

    fun changeNote(draftId: String, noteText: String): OperationResult<Unit> = synchronized(monitor) {
        val before = state.value
        val draft = before.draft?.takeIf { it.draftId == draftId } ?: return@synchronized reject("STALE_ANNOTATION_DRAFT")
        if (before.editBlocked) return@synchronized reject("ANNOTATION_SAVE_IN_PROGRESS")
        if (noteText.length > 1_000_000 || !ReaderLocatorValidator.isWellFormedUtf16(noteText)) return@synchronized reject("INVALID_ARGUMENT")
        if (noteText != draft.noteText) {
            pending = null
            mutableState.value = before.copy(draft = draft.copy(noteText = noteText), error = null)
        }
        ok()
    }

    /** Attach a persisted record only after the same native fragments were proved by begin. */
    fun attachExisting(draftId: String, view: ReaderAnnotationView): OperationResult<Unit> = synchronized(monitor) {
        val current=state.value
        val draft=current.draft ?: return@synchronized reject("STALE_ANNOTATION_DRAFT")
        val targets=draft.fragments.map { AnnotationTargetInput(it.locator,"EXACT") }
        if(draft.draftId!=draftId || draft.annotationId!=null || current.editBlocked || view.revision<=0 ||
            view.kind==AnnotationKind.INK || view.documentId!=draft.originalSnapshot.documentId ||
            view.locator!=draft.fragment.locator || view.quote!=draft.quote || view.targets!=targets)
            return@synchronized reject("ANNOTATION_SELECTION_MISMATCH")
        pending=null
        mutableState.value=current.copy(draft=draft.copy(kind=view.kind,annotationId=view.id,baseRevision=view.revision,
            noteText=view.noteText,style=view.style,savedNoteText=view.noteText,savedStyle=view.style),
            saved=AnnotationTargetValidation.freezeView(view))
        ok()
    }

    fun changeStyle(draftId: String, style: AnnotationStyle): OperationResult<Unit> = synchronized(monitor) {
        val before = state.value
        val draft = before.draft?.takeIf { it.draftId == draftId } ?: return@synchronized reject("STALE_ANNOTATION_DRAFT")
        if (before.editBlocked) return@synchronized reject("ANNOTATION_SAVE_IN_PROGRESS")
        if (style != draft.style) {
            pending = null
            mutableState.value = before.copy(draft = draft.copy(style = style.copy()), error = null)
        }
        ok()
    }

    fun dismiss(draftId: String): OperationResult<Unit> = synchronized(monitor) {
        if (state.value.draft?.draftId != draftId) return@synchronized reject("STALE_ANNOTATION_DRAFT")
        if (state.value.editBlocked) return@synchronized reject("ANNOTATION_SAVE_IN_PROGRESS")
        pending = null
        mutableState.value = ReflowAnnotationEditorState()
        ok()
    }

    fun invalidate(code: String) = synchronized(monitor) {
        pending = null
        mutableState.value = ReflowAnnotationEditorState(error = code)
    }

    /** Losing the native source revokes new writes, but never consumes an editor draft or receipt. */
    fun sourceLost(code: String) = synchronized(monitor) {
        mutableState.value = state.value.copy(sourceUnavailable = true, error = code)
    }

    /** Explicit user action after CAS conflict. Repository data only changes the saved baseline. */
    suspend fun reloadConflict(draftId: String): OperationResult<Unit> {
        val before = synchronized(monitor) {
            val current = state.value
            val draft = current.draft ?: return reject("STALE_ANNOTATION_DRAFT")
            if (draft.draftId != draftId || draft.annotationId == null || current.conflictRevision == null ||
                current.editBlocked || current.sourceUnavailable || !validateOwner(draft)) return reject("ANNOTATION_RELOAD_UNAVAILABLE")
            mutableState.value = current.copy(saving = true)
            current
        }
        val draft = checkNotNull(before.draft)
        try {
            if (services.identityNow() != draft.identity) return reject("SESSION_CHANGED")
            val context = AccessContext(checkNotNull(draft.identity.userId), draft.identity.epoch, newId())
            val opened = services.access.open(context, draft.originalSnapshot.documentId, draft.originalSnapshot.contentRevision)
            val expected = draft.originalSnapshot
            if (opened !is OperationResult.Ok || opened.value.documentId != expected.documentId ||
                opened.value.contentRevision != expected.contentRevision || opened.value.accessEpoch != expected.accessEpoch ||
                opened.value.ownerUserId != expected.ownerUserId) return reject("SOURCE_PROOF_STALE")
            val latest = services.observe(context.userId, draft.originalSnapshot.documentId).first()
                .singleOrNull { it.id == draft.annotationId } ?: return reject("ANNOTATION_NOT_FOUND")
            if (services.identityNow() != draft.identity) return reject("SESSION_CHANGED")
            val targets = draft.fragments.map { AnnotationTargetInput(it.locator, "EXACT") }
            if (latest.documentId != draft.originalSnapshot.documentId || latest.revision < checkNotNull(before.conflictRevision) ||
                latest.revision <= checkNotNull(draft.baseRevision) || latest.kind != draft.kind ||
                latest.locator != draft.fragment.locator || latest.quote != draft.quote || latest.targets != targets)
                return reject("ANNOTATION_RELOAD_MISMATCH")
            synchronized(monitor) {
                if (state.value.draft != draft || !validateOwner(draft)) return reject("STALE_ANNOTATION_DRAFT")
                pending = null
                mutableState.value = state.value.copy(draft = draft.copy(baseRevision = latest.revision,
                    savedNoteText = latest.noteText, savedStyle = latest.style), saving = false,
                    saved = AnnotationTargetValidation.freezeView(latest), conflictRevision = null, error = null)
            }
            return ok()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return OperationResult.Failed("ANNOTATION_RELOAD_FAILED", true) }
        finally {
            synchronized(monitor) {
                if (state.value.draft?.draftId == draftId) mutableState.value = state.value.copy(saving = false)
            }
        }
    }

    suspend fun save(draftId: String): OperationResult<ReaderAnnotationView> {
        val attempt = synchronized(monitor) {
            val before = state.value
            val draft = before.draft?.takeIf { it.draftId == draftId } ?: return reject("STALE_ANNOTATION_DRAFT")
            if (before.saving) return reject("ANNOTATION_SAVE_IN_PROGRESS")
            before.conflictRevision?.let { return OperationResult.Conflict(it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
            val receiptOnly = before.sourceUnavailable && before.outcomeUnknown && pending?.draft == draft
            if (before.sourceUnavailable && !receiptOnly) return reject("REFLOW_SELECTION_SOURCE_UNAVAILABLE")
            if (!receiptOnly && !validateOwner(draft)) return reject("STALE_ANNOTATION_DRAFT")
            val selected = pending ?: Pending(draft, AccessContext(checkNotNull(draft.identity.userId), draft.identity.epoch, newId()),
                AnnotationTargetValidation.freezeTargets(draft.fragments.map { AnnotationTargetInput(it.locator, "EXACT") }))
                .also { pending = it }
            mutableState.value = before.copy(saving = true, error = null)
            selected to receiptOnly
        }
        val (ticket, receiptOnly) = attempt
        try {
            val guard = AnnotationWriteGuard(ticket.draft.originalSnapshot.documentId, ticket.draft.originalSnapshot.contentRevision,
                ticket.draft.originalSnapshot.accessEpoch) { context, point, targets -> check(ticket, context, point, targets) }
            // A V2 receipt replay returns before its Guard. Without a receipt the same Guard
            // rejects BEFORE_WRITE, so recovery cannot create/update a row after source loss.
            val prepared = if (receiptOnly) prepareReceiptReplay(ticket)
                else check(ticket, ticket.context, AnnotationWriteCheckpoint.BEFORE_WRITE, ticket.targets)
            val result: OperationResult<ReaderAnnotationView> = if (prepared !is OperationResult.Ok) failure(prepared) else {
                val draft = ticket.draft
                if (draft.annotationId == null) services.create(ticket.context, draft.originalSnapshot.documentId, draft.kind,
                    draft.fragment.locator, draft.quote, draft.style, draft.noteText, ticket.targets, guard)
                else services.update(ticket.context, draft.annotationId, checkNotNull(draft.baseRevision), draft.noteText, draft.style, guard)
            }
            currentCoroutineContext().ensureActive()
            val current = services.identityNow()
            currentCoroutineContext().ensureActive()
            synchronized(monitor) {
                if (pending !== ticket || state.value.draft != ticket.draft) return result
                val before = state.value
                if (result is OperationResult.Ok && current == ticket.draft.identity &&
                    (before.sourceUnavailable || validateOwner(ticket.draft))) {
                    val view = result.value
                    if (!validResult(ticket, result)) {
                        mutableState.value = before.copy(saving = false, error = "ANNOTATION_RECEIPT_INVALID", outcomeUnknown = true)
                        return OperationResult.Failed("ANNOTATION_RECEIPT_INVALID", true)
                    }
                    val frozen = AnnotationTargetValidation.freezeView(view)
                    pending = null
                    mutableState.value = before.copy(draft = ticket.draft.copy(annotationId = view.id, baseRevision = view.revision,
                        savedNoteText = ticket.draft.noteText, savedStyle = ticket.draft.style), saving = false,
                        error = null, saved = frozen, outcomeUnknown = false)
                } else {
                    // An obsolete success remains a committed fact, but cannot change this panel's content/baseline.
                    val unknown = result is OperationResult.Ok || isUnknown(result)
                    mutableState.value = before.copy(saving = false, error = if (result is OperationResult.Ok) "STALE_ANNOTATION_RECEIPT" else code(result),
                        outcomeUnknown = before.outcomeUnknown || unknown,
                        // A replay conflict does not settle the original uncertain mutation. Keep
                        // its ticket replayable; rebasing it would allocate a second mutation.
                        conflictRevision = if(before.outcomeUnknown || unknown)null
                            else (result as? OperationResult.Conflict)?.currentRevision?.toLong() ?: before.conflictRevision)
                }
            }
            return result
        } catch (cancelled: CancellationException) {
            markUnknown(ticket, "ANNOTATION_SAVE_CANCELLED")
            throw cancelled
        } catch (_: Exception) {
            markUnknown(ticket, "ANNOTATION_SAVE_FAILED")
            return OperationResult.Failed("ANNOTATION_SAVE_FAILED", true)
        }
    }

    private suspend fun check(ticket: Pending, context: AccessContext, point: AnnotationWriteCheckpoint,
        targets: List<AnnotationTargetInput>): OperationResult<Unit> {
        // Both repository checkpoints use the same proof. Their names are not separate privilege levels.
        if (point !in AnnotationWriteCheckpoint.entries || context != ticket.context || targets != ticket.targets)
            return reject("INVALID_ANNOTATION_WRITE_REQUEST")
        currentCoroutineContext().ensureActive()
        if (services.identityNow() != ticket.draft.identity) return reject("SESSION_CHANGED")
        localCheck(ticket)?.let { return it }
        val opened = services.access.open(context, ticket.draft.originalSnapshot.documentId, ticket.draft.originalSnapshot.contentRevision)
        currentCoroutineContext().ensureActive()
        if (opened !is OperationResult.Ok) return failure(opened)
        val expected = ticket.draft.originalSnapshot
        if (opened.value.documentId != expected.documentId || opened.value.contentRevision != expected.contentRevision ||
            opened.value.accessEpoch != expected.accessEpoch || opened.value.ownerUserId != expected.ownerUserId)
            return reject("SOURCE_PROOF_STALE")
        if (services.identityNow() != ticket.draft.identity) return reject("SESSION_CHANGED")
        currentCoroutineContext().ensureActive()
        return localCheck(ticket) ?: ok()
    }

    private fun localCheck(ticket: Pending): OperationResult.Rejected? = synchronized(monitor) {
        if (state.value.sourceUnavailable) reject("REFLOW_SELECTION_SOURCE_UNAVAILABLE")
        else if (pending !== ticket || !state.value.saving || state.value.draft != ticket.draft || !validateOwner(ticket.draft))
            reject("STALE_ANNOTATION_DRAFT") else null
    }

    private suspend fun prepareReceiptReplay(ticket: Pending): OperationResult<Unit> {
        currentCoroutineContext().ensureActive()
        if (services.identityNow() != ticket.draft.identity) return reject("SESSION_CHANGED")
        currentCoroutineContext().ensureActive()
        return synchronized(monitor) {
            if (pending !== ticket || state.value.draft != ticket.draft || !state.value.saving ||
                !state.value.sourceUnavailable || !state.value.outcomeUnknown) reject("STALE_ANNOTATION_DRAFT")
            else ok()
        }
    }

    private fun validResult(ticket: Pending, result: OperationResult.Ok<ReaderAnnotationView>): Boolean {
        val draft = ticket.draft
        val view = result.value
        return result.operationId == ticket.context.requestId && view.id.isNotBlank() && view.revision == (draft.baseRevision ?: 0L) + 1L &&
            (draft.annotationId == null || view.id == draft.annotationId) && view.documentId == draft.originalSnapshot.documentId &&
            view.kind == draft.kind && view.locator == draft.fragment.locator && view.quote == draft.quote &&
            view.noteText == draft.noteText && view.style == draft.style && view.targets == ticket.targets
    }

    private fun markUnknown(ticket: Pending, error: String) = synchronized(monitor) {
        if (pending === ticket && state.value.draft?.draftId == ticket.draft.draftId)
            mutableState.value = state.value.copy(saving = false, error = error, outcomeUnknown = true)
    }

    private fun isUnknown(result: OperationResult<*>): Boolean = result is OperationResult.Failed && result.code != "STORAGE_FAILURE"
    private fun failure(result: OperationResult<*>): OperationResult<Nothing> = when (result) {
        is OperationResult.Rejected -> result
        is OperationResult.Failed -> result
        is OperationResult.Conflict -> result
        is OperationResult.Ok -> error("expected a failed operation")
    }
    private fun code(result: OperationResult<*>): String = when (result) {
        is OperationResult.Rejected -> result.code
        is OperationResult.Failed -> result.code
        is OperationResult.Conflict -> "ANNOTATION_REVISION_CONFLICT"
        is OperationResult.Ok -> ""
    }
    private fun reject(code: String) = OperationResult.Rejected(code)
    private fun ok() = OperationResult.Ok(Unit, "annotation-editor")
}

internal fun freezeAnnotationSnapshot(snapshot: DocumentSnapshot): DocumentSnapshot = snapshot.copy(
    sections = Collections.unmodifiableList(snapshot.sections.map { it.copy() }),
)
