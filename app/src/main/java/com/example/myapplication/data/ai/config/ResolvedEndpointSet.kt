package com.example.myapplication.data.ai.config

/** Operations are explicit: an exact chat resource cannot imply a models or stream URL. */
enum class EndpointOperation { CHAT_STREAM, CHAT_SINGLE, MODELS, EMBEDDING, RERANK, SEARCH }

/** A URL that has already been resolved and validated. Adapters must use it unchanged. */
data class FinalEndpoint(val url: String, val operation: EndpointOperation, val configHash: String)

data class ResolvedEndpointSet(
    val version: Int = 1,
    val configHash: String,
    val urls: Map<EndpointOperation, FinalEndpoint>,
)
