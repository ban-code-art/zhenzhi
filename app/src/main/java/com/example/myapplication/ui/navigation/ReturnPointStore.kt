package com.example.myapplication.ui.navigation

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

data class ReturnSession(val ownerUserId: Long, val sessionEpoch: Long)

data class ReturnPoint(
    val returnKey: String = "",
    val ownerUserId: Long,
    val sessionEpoch: Long,
    val routeInstanceKey: String,
    val callerRouteKey: String,
    val documentId: String? = null,
    val revision: String? = null,
    val locatorJson: String? = null,
    val selectionPayloadKey: String? = null,
    val positionStateJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
)

/** Immutable snapshots in the app's no-backup directory; keys never contain user content. */
class ReturnPointStore(
    private val root: File,
    private val currentSession: suspend () -> ReturnSession?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun put(point: ReturnPoint, selectionPayloadJson: String? = null): String {
        require(currentSession() == ReturnSession(point.ownerUserId, point.sessionEpoch)) { "stale return session" }
        require(point.routeInstanceKey.isNotBlank() && point.routeInstanceKey.length <= 256)
        require(point.callerRouteKey.isNotBlank() && point.callerRouteKey.length <= 1024)
        require(point.createdAt >= 0 && point.createdAt <= clock())
        require(point.returnKey.isEmpty()) { "snapshots are immutable; allocate a fresh key" }
        val key = UUID.randomUUID().toString()
        val saved = point.copy(returnKey = key, selectionPayloadKey = selectionPayloadJson?.let { key })
        withContext(Dispatchers.IO) {
            val bytes = encode(saved, selectionPayloadJson).toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_BYTES) { "return snapshot exceeds limit" }
            val dir = File(root, point.ownerUserId.toString())
            check(dir.isDirectory || dir.mkdirs()) { "cannot create return directory" }
            val pending = File(dir, "$key.tmp")
            try {
                pending.outputStream().use { stream -> stream.write(bytes); stream.fd.sync() }
                // Publish once, in the same directory. Partial writes never become readable snapshots.
                check(currentSession() == ReturnSession(point.ownerUserId, point.sessionEpoch)) { "session changed while saving" }
                if (!pending.renameTo(File(dir, "$key.json"))) throw IOException("cannot publish return snapshot")
            } finally {
                pending.delete()
            }
        }
        return key
    }

    suspend fun get(userId: Long, returnKey: String): ReturnPoint? = read(userId, returnKey)?.first

    suspend fun payload(userId: Long, returnKey: String): String? = read(userId, returnKey)?.second

    suspend fun remove(userId: Long, returnKey: String) {
        if (!safeKey.matches(returnKey) || currentSession()?.ownerUserId != userId) return
        withContext(Dispatchers.IO) { File(root, "$userId/$returnKey.json").delete() }
    }

    /** Caller supplies all live back-stack/task keys; age alone never expires a live point. */
    suspend fun collectGarbage(userId: Long, activeKeys: Set<String>) {
        val session = currentSession()?.takeIf { it.ownerUserId == userId } ?: return
        withContext(Dispatchers.IO) {
            File(root, userId.toString()).listFiles()?.forEach { file ->
                if (currentSession() != session) return@withContext
                val key = file.name.removeSuffix(".json")
                if (file.extension == "json" && safeKey.matches(key) && key !in activeKeys) {
                    val created = runCatching {
                        require(file.length() in 1..MAX_BYTES.toLong())
                        decode(Json.parseToJsonElement(file.readText()).jsonObject).createdAt
                    }
                        .getOrDefault(file.lastModified())
                    if (clock() - created > RETENTION_MS) file.delete()
                }
            }
        }
    }

    private suspend fun read(userId: Long, key: String): Pair<ReturnPoint, String?>? {
        if (!safeKey.matches(key)) return null
        val session = currentSession()?.takeIf { it.ownerUserId == userId } ?: return null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(root, "$userId/$key.json")
                if (!file.isFile || file.length() !in 1..MAX_BYTES.toLong()) return@withContext null
                val json = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
                val point = decode(json)
                if (point.returnKey != key || point.ownerUserId != userId || point.sessionEpoch != session.sessionEpoch ||
                    point.selectionPayloadKey?.let { it != key } == true) return@withContext null
                point to json.stringOrNull("selectionPayload")
            }.getOrNull()
        }
        return result.takeIf { currentSession() == session }
    }

    private fun encode(p: ReturnPoint, payload: String?) = buildJsonObject {
        put("version", 1); put("returnKey", p.returnKey); put("ownerUserId", p.ownerUserId)
        put("sessionEpoch", p.sessionEpoch); put("routeInstanceKey", p.routeInstanceKey)
        put("callerRouteKey", p.callerRouteKey); put("documentId", p.documentId); put("revision", p.revision)
        put("locatorJson", p.locatorJson); put("selectionPayloadKey", p.selectionPayloadKey)
        put("positionStateJson", p.positionStateJson); put("createdAt", p.createdAt); put("selectionPayload", payload)
    }

    private fun decode(j: JsonObject): ReturnPoint {
        require(j.getValue("version").jsonPrimitive.int == 1)
        return ReturnPoint(j.getValue("returnKey").jsonPrimitive.content,
            j.getValue("ownerUserId").jsonPrimitive.long, j.getValue("sessionEpoch").jsonPrimitive.long,
            j.getValue("routeInstanceKey").jsonPrimitive.content, j.getValue("callerRouteKey").jsonPrimitive.content,
            j.stringOrNull("documentId"), j.stringOrNull("revision"), j.stringOrNull("locatorJson"),
            j.stringOrNull("selectionPayloadKey"), j.getValue("positionStateJson").jsonPrimitive.content,
            j.getValue("createdAt").jsonPrimitive.long)
    }

    private fun JsonObject.stringOrNull(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull

    companion object {
        private val safeKey = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private const val MAX_BYTES = 8 * 1024 * 1024
        private const val RETENTION_MS = 24 * 60 * 60 * 1000L
    }
}
