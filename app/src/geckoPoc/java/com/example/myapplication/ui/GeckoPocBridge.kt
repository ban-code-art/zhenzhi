package com.example.myapplication.ui

import com.example.myapplication.domain.reader.ReaderSessionToken
import com.example.myapplication.domain.reader.ReaderFeatureReport
import com.example.myapplication.domain.reader.ReaderSelectionEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.json.JSONObject

/** Wire validation and ephemeral projection for the opt-in fixture, never a persisted reader state. */
internal class GeckoPocBridge(private val token: () -> ReaderSessionToken) {
    private var sequence = -1L
    private val projection = linkedMapOf<String, JSONObject>()
    private var firstFeature: JSONObject? = null
    private var accepted = 0L
    private var rejected = 0L
    private var feature: ReaderFeatureReport? = null
    private var firstTypedFeature: ReaderFeatureReport? = null
    private var selection: ReaderSelectionEvent? = null
    private var selectionNumber = 0L
    private var activeSelectionId: String? = null
    private var events: GeckoPocLayoutEvents? = null

    private fun layoutEvents() = events ?: GeckoPocLayoutEvents(token()).also { events = it }
    fun featureEvents(request: ReaderSessionToken): Flow<ReaderFeatureReport> =
        if (request == token()) layoutEvents().features() else emptyFlow()
    fun selectionEvents(request: ReaderSessionToken): Flow<ReaderSelectionEvent> =
        if (request == token()) layoutEvents().selections() else emptyFlow()
    fun finishLayout(request: ReaderSessionToken = token()) {
        if (request == token()) layoutEvents().finish()
    }

    fun resetLayout() {
        val previous = events
        events = GeckoPocLayoutEvents(token())
        sequence = -1L
        projection.clear()
        firstFeature = null
        feature = null
        firstTypedFeature = null
        selection = null
        activeSelectionId = null
        // Finishing may synchronously invoke a collector's onCompletion subscription.
        previous?.finish()
    }

    fun matches(value: JSONObject?): Boolean = runCatching { decodeToken(value!!) == token() }.getOrDefault(false)

    fun firstReport(): JSONObject? = firstFeature?.let { JSONObject(it.toString()) }
    fun firstFeatureReport(): ReaderFeatureReport? = firstTypedFeature
    fun featureReport(): ReaderFeatureReport? = feature
    fun selectionEvent(): ReaderSelectionEvent? = selection

    fun receive(event: JSONObject, copy: (String) -> Unit) {
        val valid = runCatching {
            val kind = event.get("kind") as String
            require(kind in setOf("feature", "selection", "copy"))
            require(matches(event.optJSONObject("token")))
            val next = decimal(event.get("eventSequence"))
            require(next > sequence)
            require(firstFeature != null || (kind == "feature" && next == 0L))
            val value = event.getJSONObject("value")
            var projectedFeature: ReaderFeatureReport? = null
            var projectedSelection: ReaderSelectionEvent? = null
            when (kind) {
                "feature" -> {
                    require(value.getString("status") in setOf("ready", "failed", "closed"))
                    if (value.getString("status") == "ready") projectedFeature = GeckoPocContractProjection.feature(event, token())
                }
                "selection" -> {
                    validateSelection(value)
                    val id = activeSelectionId ?: "${token().sessionId}:${token().layoutGeneration}:${selectionNumber + 1}"
                    projectedSelection = GeckoPocContractProjection.selection(event, token(), id)
                }
                "copy" -> require(value.get("text") is String && value.getString("text").length <= 8192)
            }
            // Validate every field before advancing the cursor or performing any side effect.
            if (kind == "copy") copy(value.getString("text"))
            projection[kind] = JSONObject(event.toString())
            if (kind == "feature") {
                feature = projectedFeature
                if (firstFeature == null) {
                    firstFeature = JSONObject(event.toString())
                    firstTypedFeature = projectedFeature
                }
            }
            if (projectedSelection != null) {
                if (activeSelectionId == null) selectionNumber++
                activeSelectionId = if (projectedSelection.phase.name == "CLEARED") null else projectedSelection.selectionId
                selection = projectedSelection
            }
            sequence = next
            accepted++
            projectedFeature?.let { layoutEvents().publish(it) }
            projectedSelection?.let { layoutEvents().publish(it) }
            if (kind == "feature" && value.getString("status") != "ready") finishLayout()
        }.isSuccess
        if (!valid) rejected++
    }

    fun snapshot(): JSONObject = JSONObject()
        .put("acceptedEvents", accepted).put("rejectedEvents", rejected)
        .put("firstFeature", firstFeature ?: JSONObject.NULL)
        .put("layoutEvents", layoutEvents().snapshot())
        .put("projected", JSONObject().apply {
            put("token", encodeToken(token()))
            put("eventSequence", sequence.toString())
            for (kind in listOf("feature", "selection", "copy")) put(kind, projection[kind] ?: JSONObject.NULL)
        })

    private fun validateSelection(value: JSONObject) {
        val phase = value.getString("phase")
        require(phase in setOf("STABLE", "DRAGGING", "CLEARED"))
        val fragments = value.getJSONArray("fragments")
        require(fragments.length() <= 64 && (phase == "CLEARED") == (fragments.length() == 0))
        for (i in 0 until fragments.length()) {
            val fragment = fragments.getJSONObject(i)
            require(fragment.get("quote") is String && fragment.getString("quote").isNotBlank())
            val rects = fragment.getJSONArray("rects")
            require(rects.length() in 1..1024)
            for (j in 0 until rects.length()) {
                val rect = rects.getJSONObject(j)
                val numbers = listOf("left", "top", "right", "bottom").map {
                    val number = rect.get(it) as Number
                    number.toDouble().also { n -> require(n.isFinite()) }
                }
                require(numbers[2] > numbers[0] && numbers[3] > numbers[1])
            }
        }
    }

    companion object {
        private val decimalPattern = Regex("0|[1-9][0-9]{0,18}")
        private fun decimal(value: Any): Long {
            require(value is String && value.matches(decimalPattern))
            return value.toLong().also { require(it >= 0) }
        }
        fun encodeToken(token: ReaderSessionToken) = JSONObject()
            .put("userId", token.userId.toString()).put("sessionEpoch", token.sessionEpoch.toString())
            .put("documentId", token.documentId).put("contentRevision", token.contentRevision)
            .put("sessionId", token.sessionId).put("layoutGeneration", token.layoutGeneration.toString())

        private fun decodeToken(value: JSONObject): ReaderSessionToken {
            require(value.length() == 6)
            fun text(key: String) = (value.get(key) as String).also { require(it.isNotBlank() && it.length <= 1024) }
            return ReaderSessionToken(decimal(value.get("userId")), decimal(value.get("sessionEpoch")),
                text("documentId"), text("contentRevision"), text("sessionId"), decimal(value.get("layoutGeneration")))
        }
    }
}
