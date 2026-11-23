package com.example.myapplication.ui.navigation

import com.example.myapplication.data.encyclopedia.EncyclopediaAgentLaunchContext
import com.example.myapplication.ui.ai.AgentLaunchContext
import kotlinx.serialization.json.*

/** Disk payload only. Never put this JSON in a route or SavedStateHandle. */
object ReturnPayloadCodec {
    fun encode(launch: AgentLaunchContext, referenceId: String? = null): String = buildJsonObject {
        put("documentId", launch.documentId); put("documentTitle", launch.documentTitle)
        put("sectionId", launch.sectionId); put("sectionTitle", launch.sectionTitle)
        put("quote", launch.quote); put("locator", launch.locatorJson); put("referenceId", referenceId)
        launch.encyclopediaContext?.let { put("encyclopediaContext", it.toJson()) }
    }.toString()

    fun decode(payload: String): AgentLaunchContext {
        val j = Json.parseToJsonElement(payload).jsonObject
        fun field(key: String) = j[key]?.jsonPrimitive?.contentOrNull
        val enc = EncyclopediaAgentLaunchContext.fromJson(j["encyclopediaContext"] as? JsonObject)
        return AgentLaunchContext(
            documentId = field("documentId"),
            documentTitle = field("documentTitle"),
            sectionId = field("sectionId"),
            sectionTitle = field("sectionTitle"),
            quote = field("quote"),
            locatorJson = field("locator"),
            encyclopediaContext = enc,
        )
    }

    fun referenceId(payload: String): String? =
        Json.parseToJsonElement(payload).jsonObject["referenceId"]?.jsonPrimitive?.contentOrNull
}
