package com.example.myapplication.ui.mine.ai

import com.example.myapplication.data.ai.config.EndpointConfigCodec
import com.example.myapplication.data.ai.config.EndpointOperation
import com.example.myapplication.data.ai.config.EndpointResolver
import com.example.myapplication.domain.ai.ProfilePurpose
import com.example.myapplication.domain.ai.WireProtocol
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** M13 projection consumes M15 resolutions; no URL construction belongs to this layer. */
data class EndpointDraftProjection(
    val mode: EndpointConfigCodec.Mode?,
    val operationUrls: Map<EndpointOperation, String>,
    val error: String?,
    val previews: Map<EndpointOperation, EndpointResolver.Resolution>,
)

/** Edits only persisted metadata, retaining every provider field and every URL's exact bytes. */
object EndpointDraftEditor {
    fun project(draft: DraftState, purpose: ProfilePurpose): EndpointDraftProjection {
        val decoded = decode(draft)
        val operations = when (purpose) {
            ProfilePurpose.CHAT -> listOf(EndpointOperation.CHAT_SINGLE, EndpointOperation.CHAT_STREAM, EndpointOperation.MODELS)
            ProfilePurpose.EMBEDDING -> listOf(EndpointOperation.EMBEDDING, EndpointOperation.MODELS)
            ProfilePurpose.RERANK -> listOf(EndpointOperation.RERANK, EndpointOperation.MODELS)
            ProfilePurpose.SEARCH -> listOf(EndpointOperation.SEARCH, EndpointOperation.MODELS)
        }
        val previews = operations.associateWith { operation ->
            EndpointResolver.resolve(draft.protocol, purpose, draft.endpoint, draft.paramsJson, operation,
                draft.model, stream = operation == EndpointOperation.CHAT_STREAM)
        }
        val normalized = EndpointResolver.normalizeConfiguration(draft.protocol, purpose, draft.endpoint,
            draft.paramsJson, draft.model, requireModel = false)
        val error = (normalized as? EndpointResolver.ConfigurationResult.Invalid)?.reason
            ?: previews.values.filterIsInstance<EndpointResolver.Resolution.Invalid>().firstOrNull()?.reason
        return EndpointDraftProjection(mode = decoded?.configuration?.mode,
            operationUrls = decoded?.configuration?.operationUrls.orEmpty(), error = error, previews = previews)
    }

    fun withMode(draft: DraftState, mode: EndpointConfigCodec.Mode): DraftState {
        val decoded = decode(draft) ?: return draft
        if (mode == decoded.configuration.mode) return draft
        // A legacy document cannot carry overrides. Never discard them during a mode change.
        if (mode == EndpointConfigCodec.Mode.LEGACY_BASE && decoded.configuration.operationUrls.isNotEmpty()) return draft
        return write(draft, decoded, mode, decoded.configuration.operationUrls)
    }

    fun withOperationUrl(draft: DraftState, operation: EndpointOperation, url: String): DraftState {
        val decoded = decode(draft) ?: return draft
        val urls = decoded.configuration.operationUrls
        val next = if (url.isBlank()) urls - operation else urls + (operation to url)
        if (next == urls) return draft
        val mode = decoded.configuration.mode.takeUnless { it == EndpointConfigCodec.Mode.LEGACY_BASE }
            ?: EndpointConfigCodec.Mode.BASE
        return write(draft, decoded, mode, next)
    }

    fun withSharedStream(draft: DraftState, enabled: Boolean): DraftState {
        if (enabled && draft.endpoint.isBlank()) return draft
        val decoded = decode(draft) ?: return draft
        if (decoded.configuration.mode != EndpointConfigCodec.Mode.EXACT_ENDPOINT ||
            draft.protocol == WireProtocol.GEMINI_NATIVE) return draft
        val stream = EndpointOperation.CHAT_STREAM
        if (!enabled && decoded.configuration.operationUrls[stream] != draft.endpoint) return draft
        return withOperationUrl(draft, stream, if (enabled) draft.endpoint else "")
    }

    /** Object key order and whitespace are irrelevant; invalid persisted input remains byte-distinct. */
    internal fun sameParams(left: String, right: String): Boolean {
        if (left == right) return true
        val lhs = (EndpointConfigCodec.decode(left) as? EndpointConfigCodec.DecodeResult.Ok)?.decoded ?: return false
        val rhs = (EndpointConfigCodec.decode(right) as? EndpointConfigCodec.DecodeResult.Ok)?.decoded ?: return false
        return lhs.params == rhs.params
    }

    private fun decode(draft: DraftState): EndpointConfigCodec.Decoded? =
        (EndpointConfigCodec.decode(draft.paramsJson) as? EndpointConfigCodec.DecodeResult.Ok)?.decoded

    private fun write(draft: DraftState, decoded: EndpointConfigCodec.Decoded, mode: EndpointConfigCodec.Mode,
        urls: Map<EndpointOperation, String>): DraftState {
        val params = if (mode == EndpointConfigCodec.Mode.LEGACY_BASE) {
            JsonObject(decoded.params - "_endpointConfig")
        } else {
            val metadata = buildJsonObject {
                put("version", 1)
                put("mode", mode.name)
                put("operationUrls", buildJsonObject { urls.forEach { (operation, url) -> put(operation.name, url) } })
            }
            JsonObject(decoded.params + ("_endpointConfig" to metadata))
        }
        return draft.copy(paramsJson = params.toString())
    }
}
