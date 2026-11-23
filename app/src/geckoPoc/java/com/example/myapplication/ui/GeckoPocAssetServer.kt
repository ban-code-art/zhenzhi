package com.example.myapplication.ui

import android.content.res.AssetManager
import android.net.Uri
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Bounded loopback server for immutable debug assets. Supports actual PDF.js range requests. */
internal class GeckoPocAssetServer(private val assets: AssetManager, private val legacyPdf: Boolean = false) : Closeable {
    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newFixedThreadPool(4)
    private val active = CopyOnWriteArrayList<Socket>()
    val origin = "http://127.0.0.1:${socket.localPort}"
    val requests = CopyOnWriteArrayList<JSONObject>()
    private val acceptor = Thread({
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (_: java.io.IOException) { break }
            active += client
            try { workers.execute { serve(client) } }
            catch (_: java.util.concurrent.RejectedExecutionException) { active -= client; client.close() }
        }
    }, "M03-Gecko-assets").apply { isDaemon = true; start() }

    private fun serve(client: Socket) {
        client.use {
            try {
                it.soTimeout = 5000
                val reader = it.getInputStream().bufferedReader(Charsets.US_ASCII)
                val first = reader.readLine()?.split(' ') ?: return
                require(first.size == 3 && first[0] in setOf("GET", "HEAD"))
                val headers = mutableMapOf<String, String>()
                var total = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    total += line.length
                    require(total <= 16384)
                    if (line.isEmpty()) break
                    val key = line.substringBefore(':').lowercase()
                    headers[key] = line.substringAfter(':').trim()
                }
                val path = Uri.parse(first[1]).path.orEmpty()
                val valid = headers["host"] == "127.0.0.1:${socket.localPort}" &&
                    (path.startsWith("/m03/") || path.startsWith("/m03-gecko/")) &&
                    path.split('/').none { part -> part == ".." || part == "." } && '\\' !in path && '\u0000' !in path
                if (!valid) { send(it, 403, "text/plain", "Blocked".toByteArray(), false); return }
                val assetPath = when (path) {
                    "/m03/index-gecko.html" -> "m03-gecko/index.html"
                    "/m03/poc.js" -> if (legacyPdf) "m03/poc.js" else "m03-gecko/poc.js"
                    else -> path.removePrefix("/")
                }
                val bytes = try { assets.open(assetPath).use { stream -> stream.readBytes() } }
                    catch (_: java.io.IOException) { send(it, 404, "text/plain", "Missing asset".toByteArray(), false); return }
                val mime = when (path.substringAfterLast('.')) {
                    "html" -> "text/html; charset=utf-8"
                    "mjs", "js" -> "text/javascript; charset=utf-8"
                    "css" -> "text/css; charset=utf-8"
                    "pdf" -> "application/pdf"
                    "epub" -> "application/epub+zip"
                    "wasm" -> "application/wasm"
                    else -> "application/octet-stream"
                }
                var range: IntRange? = null
                headers["range"]?.let { value ->
                    val match = Regex("bytes=([0-9]+)-([0-9]*)").matchEntire(value)
                    if (match == null) { send(it, 416, "text/plain", ByteArray(0), false); return }
                    val start = match.groupValues[1].toLongOrNull() ?: Long.MAX_VALUE
                    val end = match.groupValues[2].toLongOrNull() ?: bytes.size.toLong() - 1
                    if (start >= bytes.size || end < start) { send(it, 416, "text/plain", ByteArray(0), false); return }
                    range = start.toInt()..end.coerceAtMost(bytes.size.toLong() - 1).toInt()
                }
                val status = if (range == null) 200 else 206
                requests += JSONObject().put("path", path).put("status", status).put("sourceBytes", bytes.size)
                val payload = range?.let { r -> bytes.copyOfRange(r.first, r.last + 1) } ?: bytes
                val extra = range?.let { r -> "Content-Range: bytes ${r.first}-${r.last}/${bytes.size}\r\n" }.orEmpty()
                send(it, status, mime, payload, first[0] == "HEAD", extra)
            } catch (e: Exception) {
                requests += JSONObject().put("error", e.toString())
            } finally { active -= client }
        }
    }

    private fun send(client: Socket, status: Int, mime: String, bytes: ByteArray, head: Boolean, extra: String = "") {
        val header = "HTTP/1.1 $status ${if (status in 200..299) "OK" else "Blocked"}\r\n" +
            "Content-Type: $mime\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\n" +
            "Accept-Ranges: bytes\r\nConnection: close\r\n$extra\r\n"
        client.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); if (!head) write(bytes); flush() }
    }

    override fun close() {
        socket.close()
        for (client in active) runCatching { client.close() }
        workers.shutdownNow()
        workers.awaitTermination(2, TimeUnit.SECONDS)
        acceptor.join(1000)
    }

    fun lifecycleSnapshot(): JSONObject = JSONObject()
        .put("closed", socket.isClosed).put("acceptorAlive", acceptor.isAlive)
        .put("workersTerminated", workers.isTerminated)
        .put("activeConnections", active.count { !it.isClosed })
}
