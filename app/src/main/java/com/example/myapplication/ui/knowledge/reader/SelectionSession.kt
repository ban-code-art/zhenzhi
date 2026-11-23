package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.domain.reader.ContentBlock
import com.example.myapplication.domain.reader.ExtractionQuality
import com.example.myapplication.domain.reader.LocatorValidation
import com.example.myapplication.domain.reader.ReaderFeatureReport
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorValidator
import com.example.myapplication.domain.reader.ReaderScreenRect
import com.example.myapplication.domain.reader.ReaderSelectionEvent
import com.example.myapplication.domain.reader.ReaderSelectionFragment
import com.example.myapplication.domain.reader.ReaderSelectionPhase
import com.example.myapplication.domain.reader.ReaderSessionHandle
import com.example.myapplication.domain.reader.ReaderSessionToken
import java.util.Collections
import java.util.concurrent.CancellationException

internal enum class SelectionSessionPhase { DRAGGING, STABLE, EDITING }
internal enum class SelectionEmptyCause { TEMPORARY_FOCUS_LOSS, EXPLICIT_CLEAR }

internal data class SelectionSession(
    val token: ReaderSessionToken,
    val selectionId: String,
    val phase: SelectionSessionPhase,
    val fragments: List<ReaderSelectionFragment>,
    val screenRects: List<ReaderScreenRect>,
    val nativeRangePresent: Boolean,
    val activeAnnotationId: String? = null,
    val editingNoteDraftId: String? = null,
)

internal data class SelectionSessionState(
    val token: ReaderSessionToken? = null,
    val featureEventSequence: Long = -1,
    val session: SelectionSession? = null,
    val retiredSelectionIds: Set<String> = emptySet(),
)

internal sealed interface SelectionSessionInput {
    data class BindOpened(val report: ReaderFeatureReport) : SelectionSessionInput
    data class FeatureChanged(val report: ReaderFeatureReport) : SelectionSessionInput
    data class Selection(
        val event: ReaderSelectionEvent,
        val emptyCause: SelectionEmptyCause? = null,
    ) : SelectionSessionInput
    data class TemporaryFocusLost(
        val token: ReaderSessionToken,
        val selectionId: String,
    ) : SelectionSessionInput
    data class BeginEditing(
        val token: ReaderSessionToken,
        val selectionId: String,
        val editingNoteDraftId: String,
    ) : SelectionSessionInput
    data class FinishEditing(
        val token: ReaderSessionToken,
        val selectionId: String,
        val editingNoteDraftId: String,
    ) : SelectionSessionInput
    data class Clear(val token: ReaderSessionToken, val selectionId: String) : SelectionSessionInput
    data class Close(val handle: ReaderSessionHandle) : SelectionSessionInput
}

internal data class SelectionRejection(val code: String, val message: String)
/** A future executor must recheck both the native token and selection ID before clearing. */
internal data class SelectionNativeClearRequest(val token: ReaderSessionToken, val selectionId: String)
internal data class SelectionSessionReduction(
    val state: SelectionSessionState,
    val rejection: SelectionRejection? = null,
    val nativeClear: SelectionNativeClearRequest? = null,
)

/**
 * Reduces selection inputs without executing native or persistent actions.
 *
 * The owner serially commits returned states and supplies BindOpened only after a successful open.
 * States containing a session must come from this reducer. The mandatory source callback must
 * validate immutable local Reflow text without IO; it receives an unmodifiable fragment snapshot.
 */
internal class SelectionSessionReducer(
    private val validateSource: (ReaderSessionToken, List<ReaderSelectionFragment>) -> LocatorValidation,
) {
    fun reduce(state: SelectionSessionState, input: SelectionSessionInput): SelectionSessionReduction =
        when (input) {
            is SelectionSessionInput.BindOpened -> updateReport(state, input.report, opened = true)
            is SelectionSessionInput.FeatureChanged -> updateReport(state, input.report, opened = false)
            is SelectionSessionInput.Selection -> select(state, input)
            is SelectionSessionInput.TemporaryFocusLost ->
                loseFocus(state, input.token, input.selectionId)
            is SelectionSessionInput.BeginEditing -> beginEditing(state, input)
            is SelectionSessionInput.FinishEditing -> finishEditing(state, input)
            is SelectionSessionInput.Clear -> clear(state, input)
            is SelectionSessionInput.Close -> close(state, input)
        }

    private fun updateReport(
        state: SelectionSessionState,
        report: ReaderFeatureReport,
        opened: Boolean,
    ): SelectionSessionReduction {
        reportRejection(report)?.let { return SelectionSessionReduction(state, it) }
        val previous = state.token
        if (previous == null) {
            if (!opened) return rejected(state, "NOT_OPEN", "阅读会话尚未打开")
            return replaceToken(state, report)
        }
        if (report.token.handle() != previous.handle()) {
            if (!opened) return rejected(state, "HANDLE_MISMATCH", "阅读会话不匹配")
            return replaceToken(state, report)
        }
        if (report.token.layoutGeneration < previous.layoutGeneration) {
            return rejected(state, "STALE_LAYOUT", "布局已经更新")
        }
        if (report.token.layoutGeneration > previous.layoutGeneration) {
            // A new layout has its own feature sequence and its own selection-ID namespace.
            return replaceToken(state, report)
        }
        if (report.eventSequence < state.featureEventSequence ||
            (!opened && report.eventSequence == state.featureEventSequence)
        ) {
            return rejected(state, "STALE_FEATURE", "阅读能力报告已经过期")
        }
        if (report.eventSequence == state.featureEventSequence) {
            // Reports are not stored: idempotence means a valid equal token/sequence binding.
            return SelectionSessionReduction(state)
        }
        return SelectionSessionReduction(state.copy(featureEventSequence = report.eventSequence))
    }

    private fun replaceToken(
        state: SelectionSessionState,
        report: ReaderFeatureReport,
    ) = SelectionSessionReduction(
        state = SelectionSessionState(
            token = report.token,
            featureEventSequence = report.eventSequence,
            retiredSelectionIds = copyRetiredIds(emptySet()),
        ),
        nativeClear = nativeClearFor(state.session),
    )

    private fun reportRejection(report: ReaderFeatureReport): SelectionRejection? {
        if (!report.renderable) return SelectionRejection("NOT_RENDERABLE", "阅读内容尚不可用")
        if (SelectionInputValidation.tokenErrors(report.token).isNotEmpty()) {
            return SelectionRejection("INVALID_TOKEN", "阅读会话标识无效")
        }
        if (report.eventSequence < 0L) {
            return SelectionRejection("INVALID_FEATURE_SEQUENCE", "阅读能力报告序号无效")
        }
        if (!ReaderLocatorValidator.validateForDocument(
                report.currentLocator, report.token.documentId, report.token.contentRevision,
            ).isValid
        ) {
            return SelectionRejection("INVALID_CURRENT_LOCATOR", "当前阅读位置无效")
        }
        return null
    }

    private fun select(
        state: SelectionSessionState,
        input: SelectionSessionInput.Selection,
    ): SelectionSessionReduction {
        val event = input.event
        rejectIdentity(state, event.token, event.selectionId)?.let { return it }
        if (event.phase == ReaderSelectionPhase.CLEARED) {
            if (event.fragments.isNotEmpty() || event.screenRects.isNotEmpty()) {
                return rejected(state, "INVALID_CLEARED_PAYLOAD", "清空事件仍包含选区")
            }
            val cause = input.emptyCause
                ?: return rejected(state, "MISSING_EMPTY_CAUSE", "选区清空原因缺失")
            return when (cause) {
                SelectionEmptyCause.TEMPORARY_FOCUS_LOSS ->
                    loseFocus(state, event.token, event.selectionId)
                SelectionEmptyCause.EXPLICIT_CLEAR -> clearFromSource(state, event.selectionId)
            }
        }
        if (input.emptyCause != null) {
            return rejected(state, "UNEXPECTED_EMPTY_CAUSE", "非空选区不能携带清空原因")
        }
        if (event.selectionId in state.retiredSelectionIds) {
            return rejected(state, "RETIRED_SELECTION", "该选区已经失效")
        }
        val previous = state.session
        if (previous != null && previous.selectionId == event.selectionId &&
            previous.phase == SelectionSessionPhase.EDITING
        ) {
            return rejected(state, "EDITING_SNAPSHOT_LOCKED", "编辑期间保持当前选区快照")
        }
        val fragments = immutableListCopy(event.fragments)
        val rects = immutableListCopy(event.screenRects)
        if (SelectionInputValidation.fragmentErrors(event.token, fragments).isNotEmpty()) {
            return rejected(state, "INVALID_SELECTION", "选区文本或定位范围无效")
        }
        if (!SelectionInputValidation.screenRectsAreValid(rects)) {
            return rejected(state, "INVALID_SCREEN_RECTS", "选区显示范围无效")
        }
        sourceRejection(event.token, fragments)?.let { return SelectionSessionReduction(state, it) }
        val replacedId = previous?.selectionId?.takeIf { it != event.selectionId }
        return SelectionSessionReduction(
            state.copy(
                session = SelectionSession(
                    token = event.token,
                    selectionId = event.selectionId,
                    phase = if (event.phase == ReaderSelectionPhase.DRAGGING) {
                        SelectionSessionPhase.DRAGGING
                    } else {
                        SelectionSessionPhase.STABLE
                    },
                    fragments = fragments,
                    screenRects = rects,
                    nativeRangePresent = true,
                    activeAnnotationId = previous?.activeAnnotationId?.takeIf { replacedId == null },
                ),
                retiredSelectionIds = copyRetiredIds(state.retiredSelectionIds, replacedId),
            ),
        )
    }

    private fun loseFocus(
        state: SelectionSessionState,
        token: ReaderSessionToken,
        selectionId: String,
    ): SelectionSessionReduction {
        rejectIdentity(state, token, selectionId)?.let { return it }
        val session = state.session
            ?: return rejected(state, "NO_SELECTION", "当前没有选区")
        if (session.token != token || session.selectionId != selectionId) {
            return rejected(state, "SELECTION_MISMATCH", "选区标识不匹配")
        }
        return SelectionSessionReduction(
            state.copy(
                session = session.copy(
                    screenRects = immutableListCopy(emptyList()),
                    nativeRangePresent = false,
                ),
            ),
        )
    }

    private fun clearFromSource(
        state: SelectionSessionState,
        selectionId: String,
    ): SelectionSessionReduction {
        val session = state.session
        if (session != null && session.selectionId != selectionId) {
            return rejected(state, "SELECTION_MISMATCH", "选区标识不匹配")
        }
        // The source already cleared its range. Do not echo a second native clear.
        return SelectionSessionReduction(
            state.copy(
                session = null,
                retiredSelectionIds = copyRetiredIds(state.retiredSelectionIds, selectionId),
            ),
        )
    }

    private fun beginEditing(
        state: SelectionSessionState,
        input: SelectionSessionInput.BeginEditing,
    ): SelectionSessionReduction {
        rejectIdentity(state, input.token, input.selectionId)?.let { return it }
        if (SelectionInputValidation.identityErrors(input.editingNoteDraftId, "draftId").isNotEmpty()) {
            return rejected(state, "INVALID_DRAFT_ID", "编辑面板标识无效")
        }
        val session = state.session
            ?: return rejected(state, "NO_SELECTION", "当前没有选区")
        if (session.token != input.token || session.selectionId != input.selectionId) {
            return rejected(state, "SELECTION_MISMATCH", "选区标识不匹配")
        }
        if (session.phase != SelectionSessionPhase.STABLE) {
            return rejected(state, "SELECTION_NOT_STABLE", "当前选区尚不能编辑")
        }
        val fragments = immutableListCopy(session.fragments)
        if (SelectionInputValidation.fragmentErrors(input.token, fragments).isNotEmpty()) {
            return rejected(state, "INVALID_SELECTION", "选区文本或定位范围无效")
        }
        sourceRejection(input.token, fragments)?.let { return SelectionSessionReduction(state, it) }
        return SelectionSessionReduction(
            state.copy(
                session = session.copy(
                    phase = SelectionSessionPhase.EDITING,
                    fragments = fragments,
                    screenRects = immutableListCopy(emptyList()),
                    nativeRangePresent = false,
                    editingNoteDraftId = input.editingNoteDraftId,
                ),
            ),
        )
    }

    private fun finishEditing(
        state: SelectionSessionState,
        input: SelectionSessionInput.FinishEditing,
    ): SelectionSessionReduction {
        rejectIdentity(state, input.token, input.selectionId)?.let { return it }
        if (SelectionInputValidation.identityErrors(input.editingNoteDraftId, "draftId").isNotEmpty()) {
            return rejected(state, "INVALID_DRAFT_ID", "编辑面板标识无效")
        }
        val session = state.session
            ?: return rejected(state, "NO_SELECTION", "当前没有选区")
        if (session.token != input.token || session.selectionId != input.selectionId) {
            return rejected(state, "SELECTION_MISMATCH", "选区标识不匹配")
        }
        if (session.phase != SelectionSessionPhase.EDITING ||
            session.editingNoteDraftId != input.editingNoteDraftId
        ) {
            return rejected(state, "EDITING_PANEL_MISMATCH", "编辑面板已经失效")
        }
        val fragments = immutableListCopy(session.fragments)
        if (SelectionInputValidation.fragmentErrors(input.token, fragments).isNotEmpty()) {
            return rejected(state, "INVALID_SELECTION", "选区文本或定位范围无效")
        }
        sourceRejection(input.token, fragments)?.let { return SelectionSessionReduction(state, it) }
        return SelectionSessionReduction(
            state.copy(
                session = session.copy(
                    phase = SelectionSessionPhase.STABLE,
                    fragments = fragments,
                    screenRects = immutableListCopy(emptyList()),
                    nativeRangePresent = false,
                    editingNoteDraftId = null,
                ),
            ),
        )
    }

    private fun clear(
        state: SelectionSessionState,
        input: SelectionSessionInput.Clear,
    ): SelectionSessionReduction {
        rejectIdentity(state, input.token, input.selectionId)?.let { return it }
        val session = state.session
            ?: return rejected(state, "NO_SELECTION", "当前没有选区")
        if (session.token != input.token || session.selectionId != input.selectionId) {
            return rejected(state, "SELECTION_MISMATCH", "选区标识不匹配")
        }
        return SelectionSessionReduction(
            state.copy(
                session = null,
                retiredSelectionIds = copyRetiredIds(state.retiredSelectionIds, session.selectionId),
            ),
            nativeClear = nativeClearFor(session),
        )
    }

    private fun close(
        state: SelectionSessionState,
        input: SelectionSessionInput.Close,
    ): SelectionSessionReduction {
        val token = state.token ?: return rejected(state, "NOT_OPEN", "阅读会话尚未打开")
        if (token.handle() != input.handle) {
            return rejected(state, "HANDLE_MISMATCH", "阅读会话不匹配")
        }
        // Close owns the five-field resource handle, including its current layout.
        return SelectionSessionReduction(SelectionSessionState(), nativeClear = nativeClearFor(state.session))
    }

    private fun rejectIdentity(
        state: SelectionSessionState,
        token: ReaderSessionToken,
        selectionId: String,
    ): SelectionSessionReduction? {
        val current = state.token ?: return rejected(state, "NOT_OPEN", "阅读会话尚未打开")
        if (token != current) return rejected(state, "STALE_TOKEN", "阅读会话或布局已经更新")
        if (SelectionInputValidation.tokenErrors(token).isNotEmpty()) {
            return rejected(state, "INVALID_TOKEN", "阅读会话标识无效")
        }
        if (SelectionInputValidation.identityErrors(selectionId, "selectionId").isNotEmpty()) {
            return rejected(state, "INVALID_SELECTION_ID", "选区标识无效")
        }
        return null
    }

    private fun sourceRejection(
        token: ReaderSessionToken,
        fragments: List<ReaderSelectionFragment>,
    ): SelectionRejection? = try {
        if (validateSource(token, fragments).isValid) null
        else SelectionRejection("SOURCE_INVALID", "选区来源无法确认")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        SelectionRejection("SOURCE_EXCEPTION", "选区来源校验暂不可用")
    }

    private fun nativeClearFor(session: SelectionSession?) =
        session?.let { SelectionNativeClearRequest(it.token, it.selectionId) }

    private fun rejected(state: SelectionSessionState, code: String, message: String) =
        SelectionSessionReduction(state, SelectionRejection(code, message))
}

/**
 * Read-only proof against the supplied immutable Reflow block text, bound to a resource handle.
 *
 * The owner must establish the actual Reflow origin; ContentBlock alone cannot identify unmarked
 * OCR. This helper rejects marked fixed/OCR/non-verified blocks. Native locators receive structural
 * validation only: passing here does not prove a real CFI/DOM range or adapter navigation support.
 */
internal class SelectionCanonicalSource(
    handle: ReaderSessionHandle,
    blocks: List<ContentBlock>,
) {
    private data class BlockKey(val sectionId: String, val blockId: String)
    private data class BlockProof(
        val key: BlockKey,
        val canonicalStart: Int,
        val canonicalEnd: Int,
        val text: String,
        val errors: List<String>,
    )

    private val sourceHandle = handle.copy()
    private val blocksByKey: Map<BlockKey, BlockProof>
    private val sourceErrors: List<String>

    init {
        // Retain only required immutable scalars, never ContentBlock or headingPath aliases.
        val snapshots = immutableListCopy(blocks).map { snapshot(it) }
        val errors = SelectionInputValidation.handleErrors(sourceHandle).toMutableList()
        if (snapshots.isEmpty()) errors += "source has no declared blocks"
        val index = LinkedHashMap<BlockKey, BlockProof>()
        snapshots.forEach { proof ->
            if (index.containsKey(proof.key)) {
                errors += "source contains a duplicate section/block key"
            } else {
                index[proof.key] = proof
            }
        }
        snapshots.groupBy { it.key.sectionId }.values.forEach { sectionBlocks ->
            var priorEnd: Int? = null
            sectionBlocks.filter { it.canonicalEnd > it.canonicalStart }
                .sortedWith(compareBy<BlockProof> { it.canonicalStart }.thenBy { it.canonicalEnd })
                .forEach { proof ->
                    val previousEnd = priorEnd
                    if (previousEnd != null && proof.canonicalStart < previousEnd) {
                        errors += "source contains overlapping canonical ranges within one section"
                    }
                    if (previousEnd == null || proof.canonicalEnd > previousEnd) {
                        priorEnd = proof.canonicalEnd
                    }
                }
        }
        blocksByKey = Collections.unmodifiableMap(index)
        sourceErrors = immutableListCopy(errors)
    }

    fun validate(
        token: ReaderSessionToken,
        fragments: List<ReaderSelectionFragment>,
    ): LocatorValidation {
        val tokenErrors = SelectionInputValidation.tokenErrors(token)
        if (tokenErrors.isNotEmpty()) return validation(tokenErrors)
        if (token.handle() != sourceHandle) {
            return validation(listOf("selection token does not match the bound source handle"))
        }
        if (sourceErrors.isNotEmpty()) return validation(sourceErrors)
        val snapshot = immutableListCopy(fragments)
        val errors = SelectionInputValidation.fragmentErrors(token, snapshot).toMutableList()
        if (errors.isNotEmpty()) return validation(errors)
        snapshot.forEach { fragment ->
            val locator = fragment.locator as ReaderLocator.Reflow
            val proof = blocksByKey[BlockKey(locator.sectionId, locator.blockId)]
            if (proof == null) {
                errors += "declared selection section/block is absent from the supplied source"
                return@forEach
            }
            if (proof.errors.isNotEmpty()) {
                errors += proof.errors
                return@forEach
            }
            if (locator.canonicalStart < proof.canonicalStart ||
                locator.canonicalEnd > proof.canonicalEnd
            ) {
                errors += "selection range is not wholly contained in its declared block"
                return@forEach
            }
            val start = locator.canonicalStart - proof.canonicalStart
            val end = locator.canonicalEnd - proof.canonicalStart
            if (start < 0 || end > proof.text.length || end <= start) {
                errors += "selection range is outside the original block text"
                return@forEach
            }
            if (!isUtf16Boundary(proof.text, start) || !isUtf16Boundary(proof.text, end)) {
                errors += "selection range splits a UTF-16 surrogate pair"
                return@forEach
            }
            val originalQuote = proof.text.substring(start, end)
            if (fragment.quote != originalQuote || locator.exactQuote != originalQuote) {
                errors += "selection quote and exactQuote must equal the original substring"
            }
            locator.prefix?.let { prefix ->
                if (prefix.length > start) {
                    errors += "prefix extends beyond the supplied declared block"
                } else {
                    val prefixStart = start - prefix.length
                    if (!isUtf16Boundary(proof.text, prefixStart) ||
                        proof.text.substring(prefixStart, start) != prefix
                    ) {
                        errors += "prefix does not match adjacent original block text"
                    }
                }
            }
            locator.suffix?.let { suffix ->
                if (suffix.length > proof.text.length - end) {
                    errors += "suffix extends beyond the supplied declared block"
                } else {
                    val suffixEnd = end + suffix.length
                    if (!isUtf16Boundary(proof.text, suffixEnd) ||
                        proof.text.substring(end, suffixEnd) != suffix
                    ) {
                        errors += "suffix does not match adjacent original block text"
                    }
                }
            }
        }
        return validation(errors)
    }

    private fun snapshot(block: ContentBlock): BlockProof {
        val errors = mutableListOf<String>()
        errors += SelectionInputValidation.identityErrors(block.sectionId, "source sectionId")
        errors += SelectionInputValidation.identityErrors(block.blockId, "source blockId")
        errors += SelectionInputValidation.identityErrors(block.nativeLocator, "source nativeLocator")
        if (block.extractionQuality != ExtractionQuality.VERIFIED_TEXT) {
            errors += "selection source text is not VERIFIED_TEXT"
        }
        if (block.fixedPageIndex != null || block.fixedRect != null) {
            errors += "marked fixed-page source cannot prove a Reflow selection"
        }
        if (!ReaderLocatorValidator.isWellFormedUtf16(block.text)) {
            errors += "original source text contains an unpaired UTF-16 surrogate"
        }
        val span = block.canonicalEnd.toLong() - block.canonicalStart.toLong()
        if (block.canonicalStart < 0 || span < 0L || span != block.text.length.toLong()) {
            errors += "source canonical span must equal its original UTF-16 text length"
        }
        return BlockProof(
            BlockKey(block.sectionId, block.blockId),
            block.canonicalStart,
            block.canonicalEnd,
            block.text,
            immutableListCopy(errors),
        )
    }

    private fun isUtf16Boundary(text: String, offset: Int): Boolean {
        if (offset < 0 || offset > text.length) return false
        return offset == 0 || offset == text.length ||
            !(Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset]))
    }

    private fun validation(errors: List<String>) = LocatorValidation(immutableListCopy(errors))
}

private object SelectionInputValidation {
    private const val MAX_ID_UTF16_UNITS = 512
    private const val MAX_QUOTE_UTF16_UNITS = 8192

    fun tokenErrors(token: ReaderSessionToken): List<String> {
        val errors = handleErrors(token.handle()).toMutableList()
        if (token.layoutGeneration < 0L) errors += "layoutGeneration must be >= 0"
        return errors
    }

    fun handleErrors(handle: ReaderSessionHandle): List<String> {
        val errors = mutableListOf<String>()
        if (handle.userId <= 0L) errors += "userId must be positive"
        if (handle.sessionEpoch < 0L) errors += "sessionEpoch must be >= 0"
        errors += identityErrors(handle.documentId, "documentId")
        errors += identityErrors(handle.contentRevision, "contentRevision")
        errors += identityErrors(handle.sessionId, "sessionId")
        return errors
    }

    fun identityErrors(value: String, field: String): List<String> {
        val errors = mutableListOf<String>()
        if (value.isBlank()) errors += field + " must not be blank"
        if (value.length > MAX_ID_UTF16_UNITS) errors += field + " is too long"
        if (value.any(Char::isISOControl)) errors += field + " contains control characters"
        if (!ReaderLocatorValidator.isWellFormedUtf16(value)) {
            errors += field + " contains an unpaired UTF-16 surrogate"
        }
        return errors
    }

    fun fragmentErrors(
        token: ReaderSessionToken,
        fragments: List<ReaderSelectionFragment>,
    ): List<String> {
        if (fragments.isEmpty()) return listOf("selection must contain at least one Reflow fragment")
        val errors = mutableListOf<String>()
        var totalQuoteUnits = 0
        fragments.forEach { fragment ->
            val locator = fragment.locator
            errors += ReaderLocatorValidator.validateForDocument(
                locator, token.documentId, token.contentRevision,
            ).errors
            if (fragment.quote.isBlank()) errors += "fragment quote must not be blank"
            if (!ReaderLocatorValidator.isWellFormedUtf16(fragment.quote)) {
                errors += "fragment quote contains an unpaired UTF-16 surrogate"
            }
            if (fragment.quote.length > MAX_QUOTE_UTF16_UNITS) {
                errors += "fragment quote exceeds 8192 UTF-16 units"
            }
            // Subtract from a bounded total before adding; never overflow an accumulated length.
            if (fragment.quote.length > MAX_QUOTE_UTF16_UNITS - totalQuoteUnits) {
                errors += "selection quotes exceed a total of 8192 UTF-16 units"
            } else {
                totalQuoteUnits += fragment.quote.length
            }
            if (locator !is ReaderLocator.Reflow) {
                errors += "selection requires Reflow locators"
            } else {
                errors += identityErrors(locator.sectionId, "sectionId")
                errors += identityErrors(locator.blockId, "blockId")
                errors += identityErrors(locator.nativeLocator, "nativeLocator")
                val rangeLength = locator.canonicalEnd.toLong() - locator.canonicalStart.toLong()
                if (locator.canonicalStart < 0 || rangeLength <= 0L) {
                    errors += "selection canonical range must be nonnegative and nonempty"
                }
                if (rangeLength != fragment.quote.length.toLong()) {
                    errors += "selection canonical range length must equal its quote UTF-16 length"
                }
                if (locator.exactQuote != fragment.quote) {
                    errors += "fragment quote must equal locator exactQuote"
                }
                if (locator.exactQuote.length > MAX_QUOTE_UTF16_UNITS) {
                    errors += "locator exactQuote exceeds 8192 UTF-16 units"
                }
            }
        }
        return errors
    }

    fun screenRectsAreValid(rects: List<ReaderScreenRect>): Boolean = rects.all {
        it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite() &&
            it.right > it.left && it.bottom > it.top
    }
}

private fun <T> immutableListCopy(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun copyRetiredIds(values: Set<String>, retiredId: String? = null): Set<String> {
    val copy = LinkedHashSet(values)
    if (retiredId != null) copy.add(retiredId)
    return Collections.unmodifiableSet(copy)
}
