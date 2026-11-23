package com.example.myapplication.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.net.Uri
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import com.example.myapplication.domain.reader.ReaderSessionHandle
import com.example.myapplication.domain.reader.ReaderSessionToken
import com.example.myapplication.domain.reader.ReaderFeatureReport
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError

/** Optional debug-only host. No production repository, reader progress or session contract. */
class GeckoReaderPocActivity : Activity() {
    lateinit var geckoView: GeckoView
        private set
    private lateinit var session: GeckoSession
    private lateinit var server: GeckoPocAssetServer
    private var port: WebExtension.Port? = null
    private var extension: WebExtension? = null
    private var failure: String? = null
    private val replies = ConcurrentHashMap<String, String>()
    private val denied = CopyOnWriteArrayList<String>()
    private val sessionId = sessionIds.incrementAndGet()
    private val handle by lazy {
        ReaderSessionHandle(Process.myUid().toLong() / 100000, 0,
            "${intent.getStringExtra("engine")}:${intent.getStringExtra("sample")}",
            "m03-controlled-fixture", "${Process.myPid()}-$sessionId")
    }
    private var layoutGeneration = 0L
    private var layoutCommandId = 0L
    private var closed = false
    private var closeCount = 0
    private class PendingLayout(val token: ReaderSessionToken, val waiting: CancellableContinuation<ReaderFeatureReport>) {
        var acknowledged = false
    }
    private val pendingLayouts = mutableMapOf<String, PendingLayout>()
    private val pendingCommands = mutableMapOf<String, ReaderSessionToken>()
    private val bridge = GeckoPocBridge { lifecycleToken() }
    private var bridgeBound = false
    private val cleanupErrors = mutableListOf<String>()

    private fun trace(event: String) {
        if (intent.getBooleanExtra("lowCapture", false)) return
        val record = JSONObject().put("sessionId", sessionId).put("event", event)
            .put("uptimeMs", SystemClock.uptimeMillis()).put("pid", Process.myPid())
            .put("engine", intent.getStringExtra("engine")).put("sample", intent.getStringExtra("sample"))
            .put("sessionOpen", if (::session.isInitialized) session.isOpen else false)
            .put("bridgeReady", bridgeReady())
        Log.i("M03GeckoLifecycle", record.toString())
        File(filesDir, "m03-gecko-evidence").apply { mkdirs() }
            .resolve("lifecycle.jsonl").appendText(record.toString() + "\n")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(Process.myUid() / 100000 == 10) { "Gecko PoC requires isolated user10" }
        trace("create")
        @Suppress("DEPRECATION")
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        server = GeckoPocAssetServer(assets, intent.getBooleanExtra("legacyPdf", false))
        val captureCrash = intent.getBooleanExtra("crashHandler", false)
        check(runtimeInstance == null || runtimeCrashCapture == captureCrash) { "Restart process to change crash capture" }
        val runtime = runtimeInstance ?: GeckoRuntime.create(applicationContext,
            GeckoRuntimeSettings.Builder().consoleOutput(!intent.getBooleanExtra("lowCapture", false)).remoteDebuggingEnabled(false)
                .extras(Bundle().apply {
                    if (intent.getBooleanExtra("nativeLog", false))
                        putString("env0", "MOZ_LOG=AccessibleCaret:5")
                }).apply { if (captureCrash) crashHandler(GeckoPocCrashService::class.java) }.build())
            .also { runtimeInstance = it; runtimeCrashCapture = captureCrash }
        session = GeckoSession()
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(s: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
                val allowed = request.uri.startsWith(server.origin + "/m03/") || request.uri == "about:blank"
                if (!allowed) denied += request.uri
                return GeckoResult.fromValue(if (allowed) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }
            override fun onLoadError(s: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                if (!closed) failure = "LOAD_ERROR:$uri:$error"
                return null
            }
        }
        session.open(runtime)
        trace("session-open")
        geckoView = GeckoView(this).apply { setSession(this@GeckoReaderPocActivity.session) }
        setContentView(geckoView)
        runtime.webExtensionController.ensureBuiltIn("resource://android/assets/m03-gecko-extension/", "m03-poc@app1.local")
            .accept({ ext ->
                if (closed || isFinishing || isDestroyed) return@accept
                val installed = ext ?: run { failure = "EXTENSION_NULL"; return@accept }
                extension = installed
                trace("extension-ready")
                session.webExtensionController.setMessageDelegate(installed, object : WebExtension.MessageDelegate {
                    override fun onConnect(p: WebExtension.Port) {
                        if (closed || port != null || p.sender.session !== session || !p.sender.isTopLevel ||
                            !p.sender.url.startsWith(server.origin + "/m03/index-gecko.html")) {
                            p.disconnect()
                            return
                        }
                        port = p
                        trace("port-connected")
                        if (captureCrash && intent.getBooleanExtra("nativeCrashProbe", false)) {
                            File(filesDir, "r24-native-probe.json").writeText(JSONObject()
                                .put("pid", Process.myPid()).put("deliberate", true)
                                .put("timeMs", System.currentTimeMillis()).toString())
                            geckoView.postDelayed({ org.mozilla.gecko.GeckoThread.crash() }, 1500)
                        }
                        p.setDelegate(object : WebExtension.PortDelegate {
                            override fun onPortMessage(message: Any, connectedPort: WebExtension.Port) {
                                if (closed || connectedPort !== port || message !is JSONObject) return
                                if (message.has("kind")) {
                                    bridge.receive(message) { text ->
                                        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                                            .setPrimaryClip(ClipData.newPlainText("M03 Gecko PoC", text))
                                    }
                                    completeLayouts()
                                    return
                                }
                                if (message.has("id")) {
                                    val id = message.opt("id") as? String ?: return
                                    val expected = pendingCommands[id] ?: return
                                    if (expected != lifecycleToken() || !bridge.matches(message.optJSONObject("token"))) return
                                    pendingCommands.remove(id)
                                    if (id == "bridge-bind") {
                                        if (message.has("error")) failure = "BRIDGE_BIND_FAILED:${message.optString("error")}" else bridgeBound = true
                                        return
                                    }
                                    if (id.startsWith("lifecycle-")) {
                                        val pending = pendingLayouts[id]
                                        if (pending?.waiting?.isActive == true) {
                                            if (message.has("error")) {
                                                pendingLayouts.remove(id)
                                                bridge.finishLayout(pending.token)
                                                pending.waiting.resumeWithException(IllegalStateException(message.getString("error")))
                                            } else {
                                                pending.acknowledged = true
                                                completeLayouts()
                                            }
                                        }
                                    } else replies[id] = message.toString()
                                }
                            }
                            override fun onDisconnect(p: WebExtension.Port) {
                                if (port === p) {
                                    port = null
                                    bridgeBound = false
                                    bridge.finishLayout()
                                    pendingCommands.clear()
                                    cancelLayouts("Bridge disconnected")
                                }
                            }
                        })
                        send("bridge-bind", "bind")
                    }
                }, "m03Poc")
                val engine = intent.getStringExtra("engine") ?: "epub"
                val sample = intent.getStringExtra("sample") ?: "controlled.epub"
                require(engine in setOf("epub", "pdf") && sample.matches(Regex("[\\w.-]+")))
                val url = Uri.parse(server.origin + "/m03/index-gecko.html").buildUpon()
                    .appendQueryParameter("engine", engine).appendQueryParameter("sample", sample).build()
                session.loadUri(url.toString())
            }, { error -> if (!closed) failure = "EXTENSION_FAILED:$error" })
    }

    fun bridgeReady(): Boolean = !closed && port != null && bridgeBound

    /** Fixture identity only: this is not a production ReaderEngineAdapter. */
    fun lifecycleToken() = ReaderSessionToken(handle.userId, handle.sessionEpoch, handle.documentId,
        handle.contentRevision, handle.sessionId, layoutGeneration)

    suspend fun applyPocPreferences(token: ReaderSessionToken): ReaderSessionToken = applyPocReport(token).token

    suspend fun applyPocReport(token: ReaderSessionToken): ReaderFeatureReport {
        checkMainThread()
        check(!closed && token == lifecycleToken()) { "Stale or closed fixture session" }
        check(pendingLayouts.isEmpty()) { "Relayout already pending" }
        check(bridgeReady()) { "Bridge not ready" }
        return suspendCancellableCoroutine { waiting ->
            if (!waiting.isActive) return@suspendCancellableCoroutine
            val id = "lifecycle-${++layoutCommandId}"
            // Invalidate the previous geometry as soon as the real layout command is dispatched.
            advanceLayout()
            val requestToken = lifecycleToken()
            pendingLayouts[id] = PendingLayout(requestToken, waiting)
            waiting.invokeOnCancellation { runOnUiThread {
                pendingLayouts.remove(id)
                pendingCommands.remove(id)
                bridge.finishLayout(requestToken)
            } }
            try {
                send(id, "relayout")
            } catch (error: Exception) {
                pendingLayouts.remove(id)
                bridge.finishLayout(requestToken)
                if (waiting.isActive) waiting.resumeWithException(error)
            }
        }
    }

    private fun checkMainThread() = check(Looper.myLooper() === Looper.getMainLooper()) {
        "Gecko fixture lifecycle must run on main thread"
    }

    private fun completeLayouts() {
        val first = bridge.firstReport() ?: return
        if (!bridge.matches(first.optJSONObject("token")) || first.optString("eventSequence") != "0") return
        val ready = pendingLayouts.filterValues { it.acknowledged && it.token == lifecycleToken() }
        for ((id, pending) in ready) {
            pendingLayouts.remove(id)
            if (!pending.waiting.isActive) continue
            val report = bridge.firstFeatureReport()
            if (first.getJSONObject("value").optString("status") == "ready" && report != null) pending.waiting.resume(report)
            else pending.waiting.resumeWithException(IllegalStateException("Layout did not become ready"))
        }
    }

    private fun cancelLayouts(reason: String) {
        val pending = pendingLayouts.values.toList()
        pendingLayouts.clear()
        pending.forEach { it.waiting.cancel(CancellationException(reason)) }
    }

    private fun advanceLayout() {
        check(layoutGeneration < Long.MAX_VALUE) { "Fixture layout generation exhausted" }
        layoutGeneration++
        bridge.resetLayout()
        pendingCommands.clear()
        replies.clear()
    }

    private fun send(id: String, op: String, extras: JSONObject = JSONObject()) {
        checkMainThread()
        check(!closed) { "Fixture session closed" }
        check(!pendingCommands.containsKey(id) && !replies.containsKey(id)) { "Duplicate bridge request id" }
        val connected = checkNotNull(port) { "Bridge not ready" }
        val token = lifecycleToken()
        pendingCommands[id] = token
        try {
            connected.postMessage(extras.put("id", id).put("op", op).put("token", GeckoPocBridge.encodeToken(token)))
        } catch (error: Exception) {
            pendingCommands.remove(id)
            throw error
        }
    }

    fun bridgeSnapshot(): String = bridge.snapshot().put("bound", bridgeBound)
        .put("pendingCommands", pendingCommands.size).toString()

    fun pocFeatureReport() = bridge.featureReport()
    fun pocSelectionEvent() = bridge.selectionEvent()
    fun pocFeatureEvents(token: ReaderSessionToken) = bridge.featureEvents(token)
    fun pocSelectionEvents(token: ReaderSessionToken) = bridge.selectionEvents(token)

    fun inspectPocLocator(id: String, locator: String) {
        check(id.matches(Regex("[0-9]{1,20}")) && locator.length <= 65536)
        val value = JSONObject(locator)
        GeckoPocContractProjection.locator(value, lifecycleToken())
        send(id, "inspectLocator", JSONObject().put("locator", value))
    }

    /** Debug fault transport: sends a captured envelope through the actual WebExtension port. */
    fun replayBridgeEvent(id: String, envelope: String) {
        check(id.matches(Regex("[0-9]{1,20}")) && envelope.length <= 65536)
        send(id, "replayEvent", JSONObject().put("event", JSONObject(envelope)))
    }

    fun lifecycleSnapshot(): String = JSONObject()
        .put("closed", closed).put("closeCount", closeCount)
        .put("sessionOpen", ::session.isInitialized && session.isOpen)
        .put("viewAttached", ::geckoView.isInitialized && geckoView.session != null)
        .put("bridgeReady", bridgeReady()).put("pendingLayouts", pendingLayouts.size)
        .put("pendingReplies", replies.size).put("layoutGeneration", layoutGeneration)
        .put("origin", if (::server.isInitialized) server.origin else "")
        .put("server", if (::server.isInitialized) server.lifecycleSnapshot() else JSONObject.NULL)
        .put("cleanupErrors", JSONArray(cleanupErrors)).toString()

    /** Stable ownership survives relayout; a handle from another open cannot release this host. */
    fun closeSession(owner: ReaderSessionHandle) {
        checkMainThread()
        if (owner != handle || closed) return
        closed = true
        closeCount++
        fun release(name: String, action: () -> Unit) {
            runCatching(action).onFailure { error ->
                cleanupErrors += "$name:$error"
                Log.e("M03GeckoLifecycle", "Failed to release $name", error)
            }
        }
        cancelLayouts("Fixture session closed")
        pendingCommands.clear()
        bridgeBound = false
        bridge.resetLayout()
        bridge.finishLayout()
        val connected = port
        port = null
        release("port") { connected?.disconnect() }
        if (::session.isInitialized) {
            release("message-delegate") {
                extension?.let { session.webExtensionController.setMessageDelegate(it, null, "m03Poc") }
            }
            extension = null
            release("navigation-delegate") { session.navigationDelegate = null }
        }
        if (::geckoView.isInitialized) release("view") { geckoView.releaseSession(); trace("view-released") }
        if (::session.isInitialized) release("session") {
            if (session.isOpen) session.close()
            trace("session-closed")
        }
        if (::server.isInitialized) release("server") { server.close(); trace("server-closed") }
        replies.clear()
    }

    fun command(id: String, op: String) {
        check(id.matches(Regex("[0-9]{1,20}")))
        check(op in OPERATIONS)
        check(!closed) { "Fixture session closed" }
        if (op == "relayout") { check(pendingLayouts.isEmpty()) { "Relayout already pending" }; advanceLayout() }
        send(id, op)
    }
    fun commandAt(id: String, x: Double, y: Double) {
        check(id.matches(Regex("[0-9]{1,20}")) && x.isFinite() && y.isFinite())
        send(id, "selectionDiagnostics", JSONObject().put("point", JSONObject().put("x", x).put("y", y)))
    }
    fun response(id: String): String? = replies.remove(id)
    fun diagnostics(): String = JSONObject().put("failure", failure).put("bridgeReady", bridgeReady())
        .put("sessionId", sessionId).put("pid", Process.myPid())
        .put("origin", server.origin).put("requests", JSONArray(server.requests))
        .put("deniedNavigations", JSONArray(denied)).put("version", "143.0.20251003115653").toString()

    override fun onDestroy() {
        try {
            trace("destroy-enter")
            closeSession(handle)
        } finally { super.onDestroy() }
    }

    companion object {
        private var runtimeInstance: GeckoRuntime? = null
        private var runtimeCrashCapture = false
        private val sessionIds = AtomicLong()
        private val OPERATIONS = setOf("state", "layout", "nativeDragTarget", "nativeDragTargetInterior", "nativeDragTargetOutside", "selectionDiagnostics", "turn", "relayout", "copy", "clear", "close", "probeNetwork",
            "collectSelection", "visibleTextPoint", "selectProgrammatically", "holdEvents", "holdNextLayoutEvents", "releaseEvents", "disconnectBridge")
    }
}
