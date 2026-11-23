package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.domain.reader.LocatorValidation
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReaderSelectionEvent
import com.example.myapplication.domain.reader.ReaderSelectionFragment
import com.example.myapplication.domain.reader.ReaderSessionHandle
import com.example.myapplication.domain.reader.ReaderSessionToken
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorValidator
import com.example.myapplication.domain.reader.ReaderSelectionPhase
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.*

internal data class ReflowSelectionBinding(val token: ReaderSessionToken, val bindingId: String)

internal data class ReflowSelectionEnvelope(
    val binding: ReflowSelectionBinding,
    val eventSequence: Long,
    val event: ReaderSelectionEvent? = null,
    val emptyCause: SelectionEmptyCause? = null,
    val unavailableReason: String? = null,
)

internal data class ReflowSelectionCapture(
    val binding: ReflowSelectionBinding,
    val eventSequence: Long,
    val selectionId: String,
)

/** Native candidates require a fresh read-only JS proof before use. */
internal data class ReflowBookmarkNativeState(
    val binding: ReflowSelectionBinding,
    val eventSequence: Long,
    val capture: ReflowSelectionCapture?,
    val fragment: ReaderSelectionFragment?,
    val viewport: BookmarkHostViewport,
    val fragments: List<ReaderSelectionFragment> = listOfNotNull(fragment),
)

internal interface ReflowSelectionSurfacePort {
    fun bookmarkState(binding: ReflowSelectionBinding): ReflowBookmarkNativeState? = null
    suspend fun proveBookmark(expected: ReflowBookmarkNativeState): Boolean = false
    suspend fun bind(token: ReaderSessionToken): ReflowSelectionBinding?
    fun events(binding: ReflowSelectionBinding): Flow<ReflowSelectionEnvelope>
    suspend fun activate(binding: ReflowSelectionBinding): OperationResult<Unit>
    suspend fun setPresented(presented: Boolean)
    suspend fun suspendBinding(binding: ReflowSelectionBinding)
    fun revokeBinding(handle: ReaderSessionHandle)
    suspend fun confirmStable(capture: ReflowSelectionCapture): OperationResult<Unit>
    suspend fun copy(request: ReflowSelectionCopyRequest): OperationResult<ReflowSelectionCopyReceipt> =
        OperationResult.Rejected("REFLOW_SELECTION_COPY_UNAVAILABLE")
    fun committedCopyReceipt(request: ReflowSelectionCopyRequest): ReflowSelectionCopyReceipt? = null
    suspend fun temporaryFocusHandoff(capture: ReflowSelectionCapture): OperationResult<Unit>
    suspend fun compareAndClear(request: SelectionNativeClearRequest, capture: ReflowSelectionCapture): OperationResult<Unit>
}

internal interface ReflowSelectionPort {
    fun readingOrder(token: ReaderSessionToken): BookmarkReadingOrder? = null
    fun bookmarkState(token: ReaderSessionToken): ReflowBookmarkNativeState? = null
    suspend fun proveBookmark(expected: ReflowBookmarkNativeState): Boolean = false
    /** Original prepared source identity. A current catalog lookup must never replace this baseline. */
    fun originalSnapshot(token: ReaderSessionToken): com.example.myapplication.domain.reader.DocumentSnapshot? = null
    fun events(token: ReaderSessionToken): Flow<ReflowSelectionEnvelope>
    fun isActive(token: ReaderSessionToken): Boolean
    suspend fun activate(token: ReaderSessionToken): OperationResult<Unit>
    fun validateSource(token: ReaderSessionToken, fragments: List<ReaderSelectionFragment>): LocatorValidation
    suspend fun confirmStable(capture: ReflowSelectionCapture): OperationResult<Unit>
    suspend fun copy(request: ReflowSelectionCopyRequest): OperationResult<ReflowSelectionCopyReceipt> =
        OperationResult.Rejected("REFLOW_SELECTION_COPY_UNAVAILABLE")
    fun committedCopyReceipt(request: ReflowSelectionCopyRequest): ReflowSelectionCopyReceipt? = null
    suspend fun temporaryFocusHandoff(capture: ReflowSelectionCapture): OperationResult<Unit>
    suspend fun compareAndClear(request: SelectionNativeClearRequest, capture: ReflowSelectionCapture): OperationResult<Unit>
}

internal interface ReflowSelectionPortOwner {
    val selectionPort: ReflowSelectionPort
}

/** Complete copy transport; the same iterative budget protects both parser entry points. */
internal object ReflowSelectionCopyCodec {
    fun decode(raw: String): ReflowSelectionCopyRequest? {
        val root = ReflowSelectionCodec.boundedObject(raw) ?: return null
        return try {
            if (root.keys != setOf("version", "operationId", "token", "bindingId", "eventSequence", "selectionId", "fragment")) return null
            val version = root["version"] as? JsonPrimitive ?: return null
            if (version.isString || version.content != "1") return null
            val tokenObject = root["token"] as? JsonObject ?: return null
            if (tokenObject.keys != setOf("userId", "sessionEpoch", "documentId", "contentRevision", "sessionId", "layoutGeneration")) return null
            val token = ReaderSessionToken(
                ReflowSelectionCodec.decimal(tokenObject, "userId") ?: return null,
                ReflowSelectionCodec.decimal(tokenObject, "sessionEpoch") ?: return null,
                ReflowSelectionCodec.string(tokenObject, "documentId") ?: return null,
                ReflowSelectionCodec.string(tokenObject, "contentRevision") ?: return null,
                ReflowSelectionCodec.string(tokenObject, "sessionId") ?: return null,
                ReflowSelectionCodec.decimal(tokenObject, "layoutGeneration") ?: return null,
            )
            val binding = ReflowSelectionBinding(token, ReflowSelectionCodec.string(root, "bindingId") ?: return null)
            val capture = ReflowSelectionCapture(binding, ReflowSelectionCodec.decimal(root, "eventSequence") ?: return null,
                ReflowSelectionCodec.string(root, "selectionId") ?: return null)
            val fragment = root["fragment"] as? JsonObject ?: return null
            if (fragment.keys != setOf("sectionId", "blockId", "nativeLocator", "quote", "canonicalStart", "canonicalEnd")) return null
            val quote = ReflowSelectionCodec.string(fragment, "quote") ?: return null
            val locator = ReaderLocator.Reflow(token.documentId, ReflowSelectionCodec.string(fragment, "sectionId") ?: return null,
                token.contentRevision, null, ReflowSelectionCodec.string(fragment, "nativeLocator") ?: return null, quote,
                canonicalStart = ReflowSelectionCodec.integer(fragment, "canonicalStart") ?: return null,
                canonicalEnd = ReflowSelectionCodec.integer(fragment, "canonicalEnd") ?: return null,
                blockId = ReflowSelectionCodec.string(fragment, "blockId") ?: return null)
            ReflowSelectionCopyRequest(ReflowSelectionCodec.string(root, "operationId") ?: return null,
                capture, ReaderSelectionFragment(locator, quote)).takeIf(ReflowSelectionValidation::copyRequest)
        } catch (_: Exception) { null }
    }

    fun encode(request: ReflowSelectionCopyRequest): JsonObject {
        val locator = request.fragment.locator as ReaderLocator.Reflow
        return buildJsonObject {
            put("version", 1); put("operationId", request.operationId)
            put("token", ReflowSelectionValidation.tokenJson(request.capture.binding.token))
            put("bindingId", request.capture.binding.bindingId)
            put("eventSequence", request.capture.eventSequence.toString()); put("selectionId", request.capture.selectionId)
            put("fragment", buildJsonObject {
                put("sectionId", locator.sectionId); put("blockId", locator.blockId); put("nativeLocator", locator.nativeLocator)
                put("quote", request.fragment.quote); put("canonicalStart", locator.canonicalStart); put("canonicalEnd", locator.canonicalEnd)
            })
        }
    }
}

/** Versioned native transport: identities are strings so JavaScript never rounds Long values. */
internal object ReflowSelectionCodec {
    private const val MAX_JSON_CONTAINER_DEPTH = 32

    fun decode(raw: String): ReflowSelectionEnvelope? {
        val root = boundedObject(raw) ?: return null
        return try {
            val version = root["version"] as? JsonPrimitive ?: return null
            if (version.isString || version.content != "1") return null
            val tokenObject = root["token"] as? JsonObject ?: return null
            if (tokenObject.keys != setOf("userId", "sessionEpoch", "documentId", "contentRevision", "sessionId", "layoutGeneration")) return null
            val token = ReaderSessionToken(
                decimal(tokenObject, "userId") ?: return null,
                decimal(tokenObject, "sessionEpoch") ?: return null,
                string(tokenObject, "documentId") ?: return null,
                string(tokenObject, "contentRevision") ?: return null,
                string(tokenObject, "sessionId") ?: return null,
                decimal(tokenObject, "layoutGeneration") ?: return null,
            )
            val binding = ReflowSelectionBinding(token, string(root, "bindingId") ?: return null)
            val sequence = decimal(root, "eventSequence") ?: return null
            val base = setOf("version", "token", "bindingId", "eventSequence")
            val envelope = if ("unavailableReason" in root) {
                if (root.keys != base + "unavailableReason") return null
                ReflowSelectionEnvelope(binding, sequence, unavailableReason = string(root, "unavailableReason") ?: return null)
            } else {
                val phaseName = string(root, "phase") ?: return null
                val phase = ReaderSelectionPhase.values().firstOrNull { it.name == phaseName } ?: return null
                val expected = base + setOf("selectionId", "phase", "fragments", "screenRects") +
                    if (phase == ReaderSelectionPhase.CLEARED) setOf("emptyCause") else emptySet()
                if (root.keys != expected) return null
                val id = string(root, "selectionId") ?: return null
                val fragments = root["fragments"] as? JsonArray ?: return null
                val rects = root["screenRects"] as? JsonArray ?: return null
                if (rects.isNotEmpty()) return null
                val cause = if (phase == ReaderSelectionPhase.CLEARED) {
                    val name = string(root, "emptyCause") ?: return null
                    SelectionEmptyCause.values().firstOrNull { it.name == name } ?: return null
                } else null
                val values = if (phase == ReaderSelectionPhase.CLEARED) {
                    if (fragments.isNotEmpty()) return null
                    emptyList()
                } else {
                    if (fragments.isEmpty() || fragments.size > 128) return null
                    fragments.map { element ->
                    val fragment = element as? JsonObject ?: return null
                    if (fragment.keys != setOf("sectionId", "blockId", "nativeLocator", "quote", "canonicalStart", "canonicalEnd")) return null
                    val quote = string(fragment, "quote") ?: return null
                    val locator = ReaderLocator.Reflow(
                        token.documentId, string(fragment, "sectionId") ?: return null,
                        token.contentRevision, null, string(fragment, "nativeLocator") ?: return null, quote,
                        canonicalStart = integer(fragment, "canonicalStart") ?: return null,
                        canonicalEnd = integer(fragment, "canonicalEnd") ?: return null,
                        blockId = string(fragment, "blockId") ?: return null,
                    )
                    ReaderSelectionFragment(locator, quote)
                    }
                }
                ReflowSelectionEnvelope(binding, sequence, ReaderSelectionEvent(token, id, phase, values, emptyList()), cause)
            }
            envelope.takeIf(ReflowSelectionValidation::envelope)
        } catch (_: Exception) { null }
    }

    internal fun boundedObject(raw: String): JsonObject? {
        if (raw.length > 131_072 || !withinJsonNestingBudget(raw)) return null
        return try { Json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }
    }

    /** Scan before the recursive parser; a root object or array counts as depth one. */
    private fun withinJsonNestingBudget(raw: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (character in raw) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                continue
            }
            when (character) {
                '"' -> inString = true
                '{', '[' -> {
                    depth++
                    if (depth > MAX_JSON_CONTAINER_DEPTH) return false
                }
                '}', ']' -> {
                    depth--
                    if (depth < 0) return false
                }
            }
        }
        return depth == 0 && !inString
    }

    internal fun string(value: JsonObject, name: String): String? =
        (value[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    internal fun decimal(value: JsonObject, name: String): Long? =
        string(value, name)?.let(ReflowSelectionValidation::decimal)
    internal fun integer(value: JsonObject, name: String): Int? =
        (value[name] as? JsonPrimitive)?.takeIf { !it.isString && it.content.matches(Regex("0|[1-9][0-9]*")) }?.content?.toIntOrNull()
}

/** One gate per native binding; collectors never own or reset its sequence watermark. */
internal class ReflowSelectionEventGate(private val binding: ReflowSelectionBinding) {
    private var watermark = -1L
    private var currentId: String? = null
    private var creationCounter = 0L
    private val retired = HashSet<String>()

    @Synchronized fun accept(envelope: ReflowSelectionEnvelope): Boolean {
        if (envelope.binding != binding || envelope.eventSequence <= watermark || !ReflowSelectionValidation.envelope(envelope)) return false
        val event = envelope.event
        if (event != null) {
            val id = event.selectionId
            val counter = ReflowSelectionValidation.selectionCounter(binding, id) ?: return false
            if (id in retired) return false
            if (event.phase == ReaderSelectionPhase.CLEARED) {
                if (currentId != id) return false
                if (envelope.emptyCause == SelectionEmptyCause.EXPLICIT_CLEAR) {
                    retired += id
                    currentId = null
                }
            } else if (currentId != id) {
                if (counter <= creationCounter) return false
                currentId?.let { retired += it }
                currentId = id
                creationCounter = counter
            }
        }
        watermark = envelope.eventSequence
        return true
    }
}

/** Shared validation also covers typed internal envelopes before they reach the T01 reducer. */
internal object ReflowSelectionValidation {
    fun decimal(value: String): Long? =
        value.takeIf { it.matches(Regex("0|[1-9][0-9]*")) }?.toLongOrNull()
    fun identity(value: String): Boolean = value.isNotBlank() && value.length <= 512 &&
        value.none(Char::isISOControl) && ReaderLocatorValidator.isWellFormedUtf16(value)
    fun token(value: ReaderSessionToken): Boolean = value.userId > 0 && value.sessionEpoch >= 0 && value.layoutGeneration >= 0 &&
        identity(value.documentId) && identity(value.contentRevision) && identity(value.sessionId)
    fun binding(value: ReflowSelectionBinding): Boolean = token(value.token) && identity(value.bindingId) &&
        runCatching { UUID.fromString(value.bindingId).toString() == value.bindingId }.getOrDefault(false)
    fun selectionCounter(binding: ReflowSelectionBinding, id: String): Long? {
        val prefix = binding.bindingId + "-"
        if (!identity(id) || !id.startsWith(prefix)) return null
        return decimal(id.removePrefix(prefix))?.takeIf { it > 0 }
    }
    fun capture(value: ReflowSelectionCapture): Boolean = binding(value.binding) && value.eventSequence >= 0 &&
        selectionCounter(value.binding, value.selectionId) != null
    fun copyRequest(value: ReflowSelectionCopyRequest): Boolean = identity(value.operationId) && capture(value.capture) &&
        envelope(ReflowSelectionEnvelope(value.capture.binding, value.capture.eventSequence,
            ReaderSelectionEvent(value.capture.binding.token, value.capture.selectionId, ReaderSelectionPhase.STABLE,
                listOf(value.fragment), emptyList())))
    fun copyReceipt(request: ReflowSelectionCopyRequest, receipt: ReflowSelectionCopyReceipt): Boolean =
        receipt.operationId == request.operationId && receipt.capture == request.capture
    fun envelope(value: ReflowSelectionEnvelope): Boolean {
        if (!binding(value.binding) || value.eventSequence < 0) return false
        val event = value.event
        if (event == null) return value.emptyCause == null && value.unavailableReason?.let(::identity) == true
        if (value.unavailableReason != null || event.token != value.binding.token ||
            selectionCounter(value.binding, event.selectionId) == null || event.screenRects.isNotEmpty()) return false
        if (event.phase == ReaderSelectionPhase.CLEARED) return value.emptyCause != null && event.fragments.isEmpty()
        if (value.emptyCause != null || event.fragments.isEmpty() || event.fragments.size > 128 ||
            event.fragments.sumOf { it.quote.length.toLong() } > 8192) return false
        if (!event.fragments.all { fragment ->
            val locator = fragment.locator as? ReaderLocator.Reflow ?: return false
            identity(locator.sectionId) && identity(locator.blockId) && identity(locator.nativeLocator) &&
                locator.prefix == null && locator.suffix == null && locator.progression == null &&
                fragment.quote.isNotBlank() && fragment.quote.length <= 8192 && ReaderLocatorValidator.isWellFormedUtf16(fragment.quote) &&
                locator.exactQuote == fragment.quote && locator.canonicalStart >= 0 &&
                locator.canonicalEnd.toLong() - locator.canonicalStart == fragment.quote.length.toLong() &&
                ReaderLocatorValidator.validateForDocument(locator, event.token.documentId, event.token.contentRevision).isValid
        }) return false
        // Native ranges emit at most one ordered slice per source block. Extending
        // the transport to multiple fragments must not accept duplicate or overlapping
        // ranges that could otherwise duplicate quoted text in downstream operations.
        val blocks = mutableSetOf<Pair<String, String>>()
        val nativeLocators = mutableSetOf<Pair<String, String>>()
        val sectionEnds = mutableMapOf<String, Int>()
        for (fragment in event.fragments) {
            val locator = fragment.locator as ReaderLocator.Reflow
            if (!blocks.add(locator.sectionId to locator.blockId) ||
                !nativeLocators.add(locator.sectionId to locator.nativeLocator)) return false
            val previousEnd = sectionEnds[locator.sectionId]
            if (previousEnd != null && locator.canonicalStart < previousEnd) return false
            sectionEnds[locator.sectionId] = locator.canonicalEnd
        }
        return true
    }
    fun tokenJson(value: ReaderSessionToken): JsonObject = buildJsonObject {
        put("userId", value.userId.toString()); put("sessionEpoch", value.sessionEpoch.toString())
        put("documentId", value.documentId); put("contentRevision", value.contentRevision)
        put("sessionId", value.sessionId); put("layoutGeneration", value.layoutGeneration.toString())
    }
}

internal object UnavailableReflowSelectionSurfacePort : ReflowSelectionSurfacePort {
    override suspend fun bind(token: ReaderSessionToken): ReflowSelectionBinding? = null
    override fun events(binding: ReflowSelectionBinding): Flow<ReflowSelectionEnvelope> = emptyFlow()
    override suspend fun activate(binding: ReflowSelectionBinding): OperationResult<Unit> = unavailable()
    override suspend fun setPresented(presented: Boolean) = Unit
    override suspend fun suspendBinding(binding: ReflowSelectionBinding) = Unit
    override fun revokeBinding(handle: ReaderSessionHandle) = Unit
    override suspend fun confirmStable(capture: ReflowSelectionCapture): OperationResult<Unit> = unavailable()
    override suspend fun temporaryFocusHandoff(capture: ReflowSelectionCapture): OperationResult<Unit> = unavailable()
    override suspend fun compareAndClear(request: SelectionNativeClearRequest, capture: ReflowSelectionCapture): OperationResult<Unit> = unavailable()
    private fun unavailable(): OperationResult<Unit> = OperationResult.Rejected("REFLOW_SELECTION_SOURCE_UNAVAILABLE")
}

internal object UnavailableReflowSelectionPort : ReflowSelectionPort {
    override fun events(token: ReaderSessionToken): Flow<ReflowSelectionEnvelope> = emptyFlow()
    override fun isActive(token: ReaderSessionToken): Boolean = false
    override suspend fun activate(token: ReaderSessionToken): OperationResult<Unit> = unavailable()
    override fun validateSource(token: ReaderSessionToken, fragments: List<ReaderSelectionFragment>): LocatorValidation =
        LocatorValidation(listOf("REFLOW_SELECTION_SOURCE_UNAVAILABLE"))
    override suspend fun confirmStable(capture: ReflowSelectionCapture): OperationResult<Unit> = unavailable()
    override suspend fun temporaryFocusHandoff(capture: ReflowSelectionCapture): OperationResult<Unit> = unavailable()
    override suspend fun compareAndClear(request: SelectionNativeClearRequest, capture: ReflowSelectionCapture): OperationResult<Unit> = unavailable()
    private fun unavailable(): OperationResult<Unit> = OperationResult.Rejected("REFLOW_SELECTION_SOURCE_UNAVAILABLE")
}
