package com.example.myapplication.ui.knowledge.reader

import android.annotation.SuppressLint
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.myapplication.data.reader.ReflowDomSelectionProjector
import com.example.myapplication.domain.reader.*
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLConnection
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import org.json.JSONObject
import kotlin.coroutines.resume

/** A bookmark viewport retains the first proven visible source range, never an empty quote point. */
internal fun reflowBookmarkViewportLocator(token: ReaderSessionToken, block: ContentBlock,
    start: Int, end: Int): ReaderLocator.Reflow? {
    if (block.canonicalStart < 0 || block.canonicalEnd.toLong() - block.canonicalStart != block.text.length.toLong() ||
        start < block.canonicalStart || end > block.canonicalEnd || end <= start ||
        end.toLong() - start > 8192 || !ReaderLocatorValidator.isWellFormedUtf16(block.text)) return null
    val quote = block.text.substring(start - block.canonicalStart, end - block.canonicalStart)
    val locator = ReaderLocator.Reflow(token.documentId, block.sectionId, token.contentRevision, null,
        block.nativeLocator, quote, canonicalStart = start, canonicalEnd = end, blockId = block.blockId)
    return locator.takeIf { ReaderLocatorValidator.validateForDocument(it, token.documentId, token.contentRevision).isValid }
}

/** Host-owned native view. Single-mode horizontal/vertical CSS fragments use production blocks. */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
class WebViewReflowReaderSurface(
    context: Context,
    createView: (Context) -> WebView = { WebView(it) },
) : ReflowReaderSurface {
    private val nonce = UUID.randomUUID().toString()
    private val scrollRevision = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var observedScrollRevision = -1L
    override val viewportNeedsRefresh: Boolean get() = scrollRevision.get() != observedScrollRevision
    @Volatile private var mainEntry: Pair<String, ByteArray>? = null
    @Volatile private var files: Map<String, File> = emptyMap()
    @Volatile private var closed = false
    @Volatile private var closing = false
    private var loadingStopped = false
    private var detached = false
    private var expectedUrl: String? = null
    private var opened = false
    private var loaded: CompletableDeferred<Unit>? = null
    private var visualRequestId = 0L
    private var preparedDocument: PreparedReflowDocument? = null
    private val selectionLock = Any()
    private val clipboard = context.applicationContext.getSystemService(ClipboardManager::class.java)
    private var copyCommitGate = ReflowSelectionCopyCommitGate(selectionLock,
        ReflowSelectionClipboardWriter { text -> checkNotNull(clipboard).setPrimaryClip(ClipData.newPlainText("选区", text)) })
    @Volatile private var presented = false
    @Volatile private var currentSelectionSource: NativeSelectionSource? = null
    val view: WebView
    internal val selectionPort: ReflowSelectionSurfacePort = NativeSelectionPort()

    internal constructor(context: Context, createView: (Context) -> WebView,
        clipboardWriter: ReflowSelectionClipboardWriter) : this(context, createView) {
        copyCommitGate = ReflowSelectionCopyCommitGate(selectionLock, clipboardWriter)
    }

    init {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Create reflow surface on main thread" }
        view = createView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            addJavascriptInterface(SelectionBridge(), "__isolatedReflowSelectionBridge")
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            // No unobserved user scroll/selection can change a committed position.
            setOnTouchListener { _, _ -> true }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                @Deprecated("Legacy navigation")
                override fun shouldOverrideUrlLoading(view: WebView, url: String) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val uri = request.url
                    val entry = mainEntry
                    if (request.method == "GET" && entry != null && uri.toString() == entry.first) {
                        return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(entry.second)).apply {
                            responseHeaders = mapOf("X-Content-Type-Options" to "nosniff", "Cache-Control" to "no-store")
                        }
                    }
                    val path = uri.path.orEmpty()
                    val prefix = "/$nonce/"
                    if (request.method != "GET" || uri.scheme != "https" || uri.host != "reader.invalid" ||
                        uri.port != -1 || uri.query != null || !path.startsWith(prefix)) return denied()
                    val relative = path.removePrefix(prefix)
                    if (relative.split('/').any { it.isEmpty() || it == "." || it == ".." } ||
                        relative.contains('\\') || relative.contains('\u0000')) return denied()
                    val file = files[relative] ?: return denied()
                    return try {
                        val mime = when (file.extension.lowercase()) {
                            "css" -> "text/css"
                            "woff" -> "font/woff"
                            "woff2" -> "font/woff2"
                            "svg" -> "image/svg+xml"
                            else -> URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
                        }
                        WebResourceResponse(mime, "UTF-8", file.inputStream()).apply {
                            responseHeaders = mapOf("X-Content-Type-Options" to "nosniff")
                        }
                    } catch (_: Exception) { denied() }
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        Log.e("ReflowSurface", "main load failed code=${error.errorCode} description=${error.description}")
                        loaded?.completeExceptionally(IllegalStateException("REFLOW_LOAD_ERROR_${error.errorCode}"))
                    }
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    if (url != expectedUrl) {
                        Log.e("ReflowSurface", "unexpected main document scheme=${Uri.parse(url.orEmpty()).scheme}")
                        loaded?.completeExceptionally(IllegalStateException("REFLOW_UNEXPECTED_DOCUMENT"))
                    } else loaded?.complete(Unit)
                }
            }
        }
    }

    override suspend fun open(document: PreparedReflowDocument, placement: ReaderPlacement, blockId: String?): ReflowViewport =
        withContext(Dispatchers.Main.immediate) {
            check(!closed && !closing && !opened) { "REFLOW_SURFACE_UNAVAILABLE" }
            opened = true
            withTimeout(10_000) {
                while (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) delay(20)
            }
            files = document.resourceFiles.toMap()
            preparedDocument = document
            val uri = Uri.Builder().scheme("https").authority("reader.invalid").appendPath(nonce).apply {
                document.sectionHref.split('/').forEach { appendPath(it) }
            }.build().toString()
            val htmlForWebView = ReflowDomSelectionProjector.prepareForWebView(document.html)
            loaded = CompletableDeferred()
            expectedUrl = uri
            mainEntry = uri to htmlForWebView.toByteArray(Charsets.UTF_8)
            view.loadUrl(uri)
            withTimeout(10_000) { loaded!!.await() }
            val ready = evaluate("(function(){return !!document.body && location.href===${literal(uri)} && document.readyState!=='loading';})()")
            check(ready == "true") { "REFLOW_DOCUMENT_NOT_READY" }
            val installed = evaluate(ReflowPaginationScript.install)
            check(installed == "true") {
                runCatching { JSONObject(installed).optString("error") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "REFLOW_INSTALL_FAILED"
            }
            check(evaluate(ReflowAnnotationDecorationScript.install) == "true") { "REFLOW_DECORATION_INSTALL_FAILED" }
            settle()
            when (placement) {
                ReaderPlacement.START -> command("api.page(0)")
                ReaderPlacement.END -> command("api.page(api.count()-1)")
                ReaderPlacement.AT_LOCATOR -> command("api.locate(${literal(requireNotNull(blockId))})")
            }
        }

    override suspend fun turn(direction: ReaderTurnDirection): ReflowViewport = withContext(Dispatchers.Main.immediate) {
        command("api.turn(${if (direction == ReaderTurnDirection.NEXT) 1 else -1})")
    }
    override suspend fun decorate(items: List<ReaderDecoration>): Boolean = withContext(Dispatchers.Main.immediate) {
        val document = preparedDocument ?: return@withContext false
        if (closed || closing || !opened) return@withContext false
        val payload = ReflowAnnotationDecorationScript.payload(document,items) ?: return@withContext false
        evaluate("window.__reflowAnnotations && window.__reflowAnnotations.set($payload)") == "true"
    }
    override val supportsExactLocations: Boolean get() = true
    override fun currentPosition(): ReaderLocator.Reflow? = synchronized(selectionLock) {
        currentSelectionSource?.takeIf { it.activated && !it.ended }?.bookmarkViewport?.locator as? ReaderLocator.Reflow
    }
    override suspend fun locate(locator: ReaderLocator.Reflow): ReflowViewport = withContext(Dispatchers.Main.immediate) {
        val document = checkNotNull(preparedDocument)
        require(locator.documentId == document.resources.target.documentId && locator.contentRevision == document.resources.target.contentRevision)
        command(checkNotNull(ReflowSelectionScript.locate(document, locator)) { "REFLOW_UNPROVEN_RANGE" })
    }
    override suspend fun locate(blockId: String): ReflowViewport = withContext(Dispatchers.Main.immediate) {
        command("api.locate(${literal(blockId)})")
    }
    override suspend fun applyPreferences(preferences: ReaderLayoutPreferences, blockId: String): ReflowViewport =
        withContext(Dispatchers.Main.immediate) {
            require(preferences.fontScale.isFinite() && preferences.fontScale in 0.5f..3f)
            require(preferences.lineHeight.isFinite() && preferences.lineHeight in 0.8f..4f)
            require(preferences.marginDp in 0..128)
            require(preferences.backgroundHex.matches(Regex("#[0-9a-fA-F]{6}")))
            require(preferences.foregroundHex == null || preferences.foregroundHex.matches(Regex("#[0-9a-fA-F]{6}")))
            // CSS assignment through DOM properties, never interpolation into a stylesheet.
            evaluate("""(function(){var api=window.__isolatedReflow;var c=api.content;
                c.style.fontSize='${preferences.fontScale * 100}%';c.style.lineHeight='${preferences.lineHeight}';
                c.style.fontFamily=${literal(preferences.fontFamily.orEmpty())};
                var foreground=${literal(preferences.foregroundHex.orEmpty())};
                // Explicit reader color must win over author inline !important declarations.
                // Preserve the original declarations so a null preference restores author intent.
                var originals=api.readerColorOriginals||(api.readerColorOriginals=new Map());
                if(foreground){
                    [c].concat(Array.from(c.querySelectorAll('*'))).forEach(function(node){
                        if(node.namespaceURI!=='http://www.w3.org/1999/xhtml')return;
                        if(!originals.has(node))originals.set(node,['color','-webkit-text-fill-color'].map(function(key){
                            return [key,node.style.getPropertyValue(key),node.style.getPropertyPriority(key)];
                        }));
                        node.style.setProperty('color',foreground,'important');
                        node.style.setProperty('-webkit-text-fill-color',foreground,'important');
                    });
                }else{
                    originals.forEach(function(values,node){values.forEach(function(value){
                        if(value[1])node.style.setProperty(value[0],value[1],value[2]);else node.style.removeProperty(value[0]);
                    });});originals.clear();
                }
                document.body.style.backgroundColor=${literal(preferences.backgroundHex)};
                api.viewport.style.inset='${preferences.marginDp}px';api.setFlowMode(${literal(preferences.flowMode.name)});api.invalidate();api.layout();return true;})()""")
            settle()
            command("api.locate(${literal(blockId)})")
        }

    override suspend fun refreshViewport(): ReflowViewport? = withContext(Dispatchers.Main.immediate) {
        if (closed || closing || !opened || !presented) null else {
            val revision = scrollRevision.get()
            command("").also { observedScrollRevision = revision }
        }
    }

    override suspend fun applyOpeningPreferences(preferences: ReaderLayoutPreferences, placement: ReaderPlacement, blockId: String): ReflowViewport =
        withContext(Dispatchers.Main.immediate) {
            val anchored = applyPreferences(preferences, blockId)
            when (placement) {
                ReaderPlacement.START -> command("api.page(0)")
                ReaderPlacement.END -> command("api.page(api.count()-1)")
                ReaderPlacement.AT_LOCATOR -> anchored
            }
        }

    override suspend fun close() = withContext(NonCancellable + Dispatchers.Main.immediate) {
        if (!closed) {
            synchronized(selectionLock) { closing = true; presented = false }
            currentSelectionSource?.let { endSelectionSource(it, "REFLOW_SURFACE_CLOSED") }
            preparedDocument = null
            loaded?.cancel()
            files = emptyMap()
            mainEntry = null
            if (!loadingStopped) { view.stopLoading(); loadingStopped = true }
            if (!detached) { (view.parent as? ViewGroup)?.removeView(view); detached = true }
            view.destroy()
            closed = true
        }
    }
    private suspend fun settle() {
        withTimeout(10_000) {
            while (evaluate("(function(){return (!document.fonts || document.fonts.status==='loaded') && Array.prototype.every.call(document.images,function(i){return i.complete;});})()") != "true") delay(20)
            // Let WebView perform style/layout and two frames before reading column geometry.
            delay(50)
            // Fonts/images may have changed fragments after the initial sizing pass.
            evaluate("(function(){window.__isolatedReflow.invalidate();return true;})()")
        }
    }
    private suspend fun command(action: String): ReflowViewport {
        check(!closed && !closing && opened) { "REFLOW_SURFACE_CLOSED" }
        val raw = evaluate("(function(){try{var api=window.__isolatedReflow;api.layout();$action;return api.report();}catch(e){return {error:String(e.message||e)};}})()")
        val value = JSONObject(raw)
        check(!value.has("error")) { value.getString("error") }
        val viewport = ReflowViewport(value.getString("blockId"), value.getInt("pageIndex"), value.getInt("pageCount"),
            if (value.isNull("scrollProgression")) null else value.getDouble("scrollProgression").toFloat(),
            value.getBoolean("atStart"), value.getBoolean("atEnd"))
        evaluate("(function(){if(window.__reflowAnnotations)window.__reflowAnnotations.refresh();return true;})()")
        awaitVisualReady()
        currentSelectionSource?.takeIf { it.activated && !it.ended }?.let { refreshBookmarkViewport(it) }
        return viewport
    }
    private suspend fun awaitVisualReady() {
        check(!closed && !closing && opened) { "REFLOW_SURFACE_CLOSED" }
        withTimeout(5_000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val requested = Math.addExact(visualRequestId, 1L).also { visualRequestId = it }
                // A staged host view has alpha=0. Wait until its final DOM can be drawn next,
                // rather than waiting for an actual native draw of this hidden candidate.
                view.postVisualStateCallback(requested, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (requestId != requested || !continuation.isActive) return
                        if (closed || closing) {
                            continuation.resumeWith(Result.failure(IllegalStateException("REFLOW_SURFACE_CLOSED")))
                        } else continuation.resume(Unit)
                    }
                })
            }
        }
        check(!closed && !closing) { "REFLOW_SURFACE_CLOSED" }
    }
    private suspend fun evaluate(script: String): String {
        check(!closed && !closing)
        return withTimeout(5_000) { awaitJavascript(script) }
    }

    /** The caller owns the timeout; copy uses the same scope for its permit and this wait. */
    private suspend fun awaitJavascript(script: String): String = suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript(script) { result ->
            if (continuation.isActive) continuation.resume(result ?: "null")
        }
    }

    private class NativeCopyAttempt(val request: ReflowSelectionCopyRequest) {
        var result: OperationResult<ReflowSelectionCopyReceipt>? = null
    }

    private class NativeSelectionSource(val binding: ReflowSelectionBinding, val document: PreparedReflowDocument) {
        // This is the only pending-event queue in the production selection path.
        val queue = Channel<ReflowSelectionEnvelope>(128)
        val copyEventGate = ReflowSelectionEventGate(binding)
        private val canonicalSource = SelectionCanonicalSource(binding.token.handle(), document.blocks)
        private val sourceLocators = document.blocks.associate { (it.sectionId to it.blockId) to it.nativeLocator }
        // Both fields are protected by selectionLock, independently of queue consumption.
        var latestEnvelope: ReflowSelectionEnvelope? = null
        var bookmarkViewport: BookmarkHostViewport? = null
        var copyAttempt: NativeCopyAttempt? = null
        @Volatile var activated = false
        @Volatile var ended = false
        var consumerJob: Job? = null
        fun validates(fragment: ReaderSelectionFragment): Boolean {
            val locator = fragment.locator as? ReaderLocator.Reflow ?: return false
            return canonicalSource.validate(binding.token, listOf(fragment)).isValid &&
                sourceLocators[locator.sectionId to locator.blockId] == locator.nativeLocator
        }
    }

    /** Called on WebView's bridge thread. Never repair or replace the callback's identity. */
    private inner class SelectionBridge {
        @JavascriptInterface fun onViewportChanged() { scrollRevision.incrementAndGet() }
        @JavascriptInterface fun commitCopy(raw: String): Boolean {
            val request = ReflowSelectionCopyCodec.decode(raw) ?: return false
            return synchronized(selectionLock) {
                val source = currentSelectionSource ?: return@synchronized false
                val attempt = source.copyAttempt?.takeIf { it.request == request && it.result == null }
                    ?: return@synchronized false
                val result = copyCommitGate.commit(source, request) { canCopy(source, request) }
                attempt.result = result
                result is OperationResult.Ok
            }
        }

        @JavascriptInterface fun isBindingActive(raw: String): Boolean {
            if (raw.length > 4096) return false
            val identity = ReflowSelectionCodec.boundedObject(raw) ?: return false
            if (identity.keys != setOf("token", "bindingId")) return false
            // This synchronous bridge query must never wait for Main, a native action, or IO.
            return synchronized(selectionLock) {
                val source = currentSelectionSource ?: return@synchronized false
                !closed && !closing && !source.ended && source.activated && presented &&
                    identity["bindingId"] == JsonPrimitive(source.binding.bindingId) &&
                    identity["token"] == ReflowSelectionValidation.tokenJson(source.binding.token)
            }
        }

        @JavascriptInterface fun postMessage(raw: String) {
            val envelope = ReflowSelectionCodec.decode(raw) ?: return
            val terminal = envelope.unavailableReason == "SEQUENCE_EXHAUSTED" ||
                envelope.unavailableReason == "SELECTION_ID_EXHAUSTED"
            val source = synchronized(selectionLock) {
                val accepted = currentSelectionSource?.takeIf {
                    !closed && !closing && !it.ended && it.activated && it.binding == envelope.binding &&
                        (presented || envelope.event == null)
                } ?: return
                val event = envelope.event
                if ((event == null || event.phase == ReaderSelectionPhase.CLEARED || event.fragments.all { accepted.validates(it) }) &&
                    accepted.copyEventGate.accept(envelope)) accepted.latestEnvelope = envelope
                accepted
            }
            val sent = source.queue.trySend(envelope)
            if (sent.isFailure) {
                // Even one lost callback invalidates this binding's ordering proof.
                endSelectionSource(source, "REFLOW_SELECTION_QUEUE_OVERFLOW")
            } else if (terminal) {
                endSelectionSource(source, "REFLOW_SELECTION_EXHAUSTED", discardPending = false)
            }
        }
    }

    private suspend fun refreshBookmarkViewport(source: NativeSelectionSource) {
        val raw = evaluate(selectionInvocation(source.binding, "return api.bookmarkViewport();"))
        val candidate = try {
            val value = JSONObject(raw)
            val block = source.document.blocks.singleOrNull { it.blockId == value.getString("blockId") }
                ?: return
            val offset = value.getInt("offset")
            val occurrence = value.getString("occurrence")
            if (offset !in block.canonicalStart..block.canonicalEnd || occurrence.isBlank()) return
            val ranges = value.getJSONArray("spans")
            if (ranges.length() !in 1..128) return
            val visibleRanges = (0 until ranges.length()).map { index ->
                val span = ranges.getJSONObject(index)
                val visibleBlock = source.document.blocks.single { it.sectionId == span.getString("sectionId") && it.blockId == span.getString("blockId") }
                val start = span.getInt("start"); val end = span.getInt("end")
                require(start >= visibleBlock.canonicalStart && end <= visibleBlock.canonicalEnd && end > start)
                requireNotNull(reflowBookmarkViewportLocator(source.binding.token, visibleBlock, start, end))
            }
            val first = visibleRanges.first()
            require(first.blockId == block.blockId && first.sectionId == block.sectionId && first.canonicalStart == offset)
            BookmarkHostViewport(occurrence, first, visibleRanges)
        } catch (_: Exception) { null }
        synchronized(selectionLock) {
            if (currentSelectionSource === source && !source.ended) source.bookmarkViewport = candidate
        }
    }

    private inner class NativeSelectionPort : ReflowSelectionSurfacePort {
        override fun bookmarkState(binding: ReflowSelectionBinding): ReflowBookmarkNativeState? = synchronized(selectionLock) {
            val source = currentSelectionSource?.takeIf { it.binding == binding && it.activated &&
                !it.ended && presented && !closed && !closing } ?: return@synchronized null
            val viewport = source.bookmarkViewport ?: return@synchronized null
            val envelope = source.latestEnvelope
            val event = envelope?.event
            if (envelope?.unavailableReason != null || event?.phase == ReaderSelectionPhase.DRAGGING) return@synchronized null
            if (event == null || event.phase == ReaderSelectionPhase.CLEARED) {
                ReflowBookmarkNativeState(binding, envelope?.eventSequence ?: 0L, null, null, viewport)
            } else if (event.phase == ReaderSelectionPhase.STABLE) {
                val fragment = event.fragments.firstOrNull() ?: return@synchronized null
                ReflowBookmarkNativeState(binding, envelope.eventSequence,
                    ReflowSelectionCapture(binding, envelope.eventSequence, event.selectionId), fragment, viewport, event.fragments.toList())
            } else null
        }

        override suspend fun proveBookmark(expected: ReflowBookmarkNativeState): Boolean = withContext(Dispatchers.Main.immediate) {
            if (bookmarkState(expected.binding) != expected) return@withContext false
            val locator = expected.viewport.locator as? com.example.myapplication.domain.reader.ReaderLocator.Reflow ?: return@withContext false
            val document = synchronized(selectionLock) { currentSelectionSource?.takeIf {
                it.binding == expected.binding && !it.ended
            }?.document } ?: return@withContext false
            val block = document.blocks.singleOrNull { it.sectionId == locator.sectionId && it.blockId == locator.blockId }
                ?: return@withContext false
            if (reflowBookmarkViewportLocator(expected.binding.token, block, locator.canonicalStart, locator.canonicalEnd) != locator ||
                expected.viewport.visibleRanges.firstOrNull() != locator) return@withContext false
            val payload = buildJsonObject {
                put("token", ReflowSelectionValidation.tokenJson(expected.binding.token)); put("bindingId", expected.binding.bindingId)
                put("eventSequence", expected.eventSequence.toString()); put("selectionId", expected.capture?.selectionId?.let(::JsonPrimitive) ?: JsonNull)
                put("viewport", buildJsonObject {
                    put("blockId", locator.blockId); put("offset", locator.canonicalStart); put("occurrence", expected.viewport.occurrenceId)
                    put("spans", buildJsonArray { expected.viewport.visibleRanges.forEach { range ->
                        val at = range as com.example.myapplication.domain.reader.ReaderLocator.Reflow
                        add(buildJsonObject { put("sectionId", at.sectionId); put("blockId", at.blockId); put("start", at.canonicalStart); put("end", at.canonicalEnd) })
                    } })
                })
                put("fragments", buildJsonArray { expected.fragments.forEach { fragment ->
                    val target = fragment.locator as com.example.myapplication.domain.reader.ReaderLocator.Reflow
                    add(buildJsonObject {
                        put("sectionId", target.sectionId); put("blockId", target.blockId); put("nativeLocator", target.nativeLocator)
                        put("quote", fragment.quote); put("canonicalStart", target.canonicalStart); put("canonicalEnd", target.canonicalEnd)
                    })
                } })
            }
            try {
                val proof = evaluate(selectionInvocation(expected.binding, "return api.proveBookmark($payload);")) == "true"
                proof && bookmarkState(expected.binding) == expected
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        }

        override suspend fun bind(token: ReaderSessionToken): ReflowSelectionBinding? =
            withContext(Dispatchers.Main.immediate) {
                currentSelectionSource?.let { endSelectionSource(it, "REFLOW_SELECTION_REBOUND") }
                synchronized(selectionLock) { currentSelectionSource = null }
                val document = preparedDocument ?: return@withContext null
                if (closed || closing || !opened || !ReflowSelectionValidation.token(token) ||
                    token.documentId != document.resources.target.documentId ||
                    token.contentRevision != document.resources.target.contentRevision ||
                    document.resources.snapshot.ownerUserId?.let { it != token.userId } == true) return@withContext null
                val binding = ReflowSelectionBinding(token, UUID.randomUUID().toString())
                val script = ReflowSelectionScript.install(document, binding) ?: return@withContext null
                val source = NativeSelectionSource(binding, document)
                synchronized(selectionLock) { currentSelectionSource = source }
                try {
                    if (evaluate(script) != "true" || !ownsSelectionSource(source)) {
                        endSelectionSource(source, "REFLOW_SELECTION_INSTALL_FAILED")
                        return@withContext null
                    }
                    val shown = evaluate(selectionInvocation(binding, "return api.setPresented($presented);"))
                    currentCoroutineContext().ensureActive()
                    if (shown != "true" || !ownsSelectionSource(source)) {
                        endSelectionSource(source, "REFLOW_SELECTION_PRESENTATION_FAILED")
                        return@withContext null
                    }
                    binding
                } catch (cancelled: CancellationException) {
                    endSelectionSource(source, "REFLOW_SELECTION_BIND_CANCELLED")
                    throw cancelled
                } catch (_: Exception) {
                    endSelectionSource(source, "REFLOW_SELECTION_INSTALL_FAILED")
                    null
                }
            }

        override fun events(binding: ReflowSelectionBinding): Flow<ReflowSelectionEnvelope> = flow {
            // A scoped receiver lets overflow interrupt a blocked downstream emit without
            // cancelling the reader owner or introducing another buffered channel.
            coroutineScope {
                val receiver = currentCoroutineContext().job
                val source = synchronized(selectionLock) {
                    currentSelectionSource?.takeIf {
                        it.binding == binding && !it.ended && !closed && !closing && it.consumerJob == null
                    }?.also { it.consumerJob = receiver }
                } ?: return@coroutineScope
                try { for (envelope in source.queue) emit(envelope) }
                finally {
                    synchronized(selectionLock) {
                        if (source.consumerJob === receiver) source.consumerJob = null
                    }
                }
            }
        }

        override suspend fun activate(binding: ReflowSelectionBinding): OperationResult<Unit> =
            withContext(Dispatchers.Main.immediate) {
                val source = synchronized(selectionLock) {
                    currentSelectionSource?.takeIf {
                        it.binding == binding && !it.ended && !closed && !closing && presented
                    }?.also { it.activated = true }
                } ?: return@withContext OperationResult.Rejected("REFLOW_SELECTION_SOURCE_UNAVAILABLE")
                try {
                    // The flag is set before evaluation: activation itself may emit a legal
                    // preexisting-range Unavailable before its acknowledgement arrives.
                    val accepted = evaluate(selectionInvocation(binding, "return api.activate();")) == "true"
                    currentCoroutineContext().ensureActive()
                    if (accepted && ownsSelectionSource(source) && presented) {
                        refreshBookmarkViewport(source)
                        nativeOk()
                    }
                    else {
                        endSelectionSource(source, "REFLOW_SELECTION_ACTIVATION_FAILED")
                        OperationResult.Rejected("REFLOW_SELECTION_SOURCE_UNAVAILABLE")
                    }
                } catch (cancelled: CancellationException) {
                    endSelectionSource(source, "REFLOW_SELECTION_ACTIVATION_CANCELLED")
                    throw cancelled
                } catch (_: Exception) {
                    endSelectionSource(source, "REFLOW_SELECTION_ACTIVATION_FAILED")
                    OperationResult.Failed("REFLOW_SELECTION_ACTIVATION_FAILED", false)
                }
            }

        override suspend fun setPresented(presented: Boolean) = withContext(Dispatchers.Main.immediate) {
            if (this@WebViewReflowReaderSurface.presented == presented) return@withContext
            synchronized(selectionLock) {
                this@WebViewReflowReaderSurface.presented = presented
                if (!presented) currentSelectionSource?.let { copyCommitGate.revoke(it) }
            }
            val source = currentSelectionSource?.takeUnless { it.ended } ?: return@withContext
            if (closed || closing) {
                endSelectionSource(source, "REFLOW_SURFACE_CLOSED")
                return@withContext
            }
            try {
                // SOURCE_HIDDEN is sent while JS still considers the source visible.
                // The bridge accepts its strict Unavailable branch after the native hide.
                val accepted = evaluate(selectionInvocation(source.binding, "return api.setPresented($presented);")) == "true"
                if (!accepted) {
                    endSelectionSource(source, "REFLOW_SELECTION_PRESENTATION_FAILED")
                    error("REFLOW_SELECTION_PRESENTATION_FAILED")
                }
            } catch (cancelled: CancellationException) {
                endSelectionSource(source, "REFLOW_SELECTION_PRESENTATION_CANCELLED")
                throw cancelled
            } catch (error: Exception) {
                endSelectionSource(source, "REFLOW_SELECTION_PRESENTATION_FAILED")
                throw error
            }
        }

        override suspend fun suspendBinding(binding: ReflowSelectionBinding) {
            val source = synchronized(selectionLock) {
                currentSelectionSource?.takeIf { it.binding == binding }
            } ?: return
            endSelectionSource(source, "REFLOW_SELECTION_SUSPENDED")
            withContext(Dispatchers.Main.immediate) {
                if (!closed && !closing) evaluate(selectionInvocation(binding, "api.stop();return true;"))
            }
        }

        override fun revokeBinding(handle: ReaderSessionHandle) {
            val source = synchronized(selectionLock) {
                currentSelectionSource?.takeIf { it.binding.token.handle() == handle }
            } ?: return
            endSelectionSource(source, "REFLOW_SELECTION_REVOKED")
        }

        override suspend fun confirmStable(capture: ReflowSelectionCapture): OperationResult<Unit> =
            selectionAction(capture, "confirmStable")

        override suspend fun copy(request: ReflowSelectionCopyRequest): OperationResult<ReflowSelectionCopyReceipt> =
            copySelection(request)

        override fun committedCopyReceipt(request: ReflowSelectionCopyRequest): ReflowSelectionCopyReceipt? =
            copyCommitGate.committedReceipt(request)

        override suspend fun temporaryFocusHandoff(capture: ReflowSelectionCapture): OperationResult<Unit> =
            selectionAction(capture, "temporaryFocusHandoff")

        override suspend fun compareAndClear(request: SelectionNativeClearRequest,
            capture: ReflowSelectionCapture): OperationResult<Unit> {
            if (request.token != capture.binding.token || request.selectionId != capture.selectionId)
                return OperationResult.Rejected("STALE_SELECTION")
            return selectionAction(capture, "compareAndClear")
        }
    }

    private suspend fun selectionAction(capture: ReflowSelectionCapture, method: String): OperationResult<Unit> =
        withContext(Dispatchers.Main.immediate) {
            if (!ReflowSelectionValidation.capture(capture)) return@withContext OperationResult.Rejected("STALE_SELECTION")
            val source = synchronized(selectionLock) {
                currentSelectionSource?.takeIf {
                    it.binding == capture.binding && !it.ended && it.activated && presented && !closed && !closing
                }
            } ?: return@withContext OperationResult.Rejected("STALE_SELECTION")
            val request = buildJsonObject {
                put("token", ReflowSelectionValidation.tokenJson(capture.binding.token))
                put("bindingId", capture.binding.bindingId)
                put("selectionId", capture.selectionId)
                put("eventSequence", capture.eventSequence.toString())
            }
            try {
                val accepted = evaluate(selectionInvocation(capture.binding, "return api.$method($request);")) == "true"
                currentCoroutineContext().ensureActive()
                if (accepted && ownsSelectionSource(source) && source.activated && presented) nativeOk()
                else OperationResult.Rejected("STALE_SELECTION")
            } catch (cancelled: CancellationException) {
                endSelectionSource(source, "REFLOW_SELECTION_ACTION_CANCELLED")
                throw cancelled
            } catch (_: Exception) {
                endSelectionSource(source, "REFLOW_SELECTION_ACTION_FAILED")
                OperationResult.Failed("REFLOW_SELECTION_NATIVE_FAILED", false)
            }
        }

    /** No JS wait, Main dispatch or document access occurs while holding the commit lock. */
    private fun canCopy(source: NativeSelectionSource, request: ReflowSelectionCopyRequest): Boolean {
        if (currentSelectionSource !== source || source.ended || !source.activated || !presented || closed || closing ||
            source.binding != request.capture.binding) return false
        val envelope = source.latestEnvelope ?: return false
        val event = envelope.event ?: return false
        return event.phase == ReaderSelectionPhase.STABLE && event.fragments == listOf(request.fragment) &&
            ReflowSelectionCapture(envelope.binding, envelope.eventSequence, event.selectionId) == request.capture
    }

    private suspend fun copySelection(request: ReflowSelectionCopyRequest): OperationResult<ReflowSelectionCopyReceipt> {
        if (!ReflowSelectionValidation.copyRequest(request)) return OperationResult.Rejected("INVALID_REFLOW_SELECTION_COPY_REQUEST")
        val callingJob = currentCoroutineContext().job
        val attempt = NativeCopyAttempt(request)
        var source: NativeSelectionSource? = null
        try {
            return withTimeout(5_000) {
                // Timeout cancellation makes this Job inactive before the caller can run finally.
                val permitJob = currentCoroutineContext().job
                synchronized(selectionLock) {
                    val current = currentSelectionSource ?: return@withTimeout OperationResult.Rejected("STALE_SELECTION")
                    if (!canCopy(current, request)) return@withTimeout OperationResult.Rejected("STALE_SELECTION")
                    if (current.copyAttempt != null) return@withTimeout OperationResult.Rejected("REFLOW_SELECTION_COPY_IN_PROGRESS")
                    when (val begun = copyCommitGate.begin(current, request, permitJob)) {
                        is OperationResult.Ok -> Unit
                        is OperationResult.Rejected -> return@withTimeout begun
                        is OperationResult.Failed -> return@withTimeout begun
                        is OperationResult.Conflict -> return@withTimeout begun
                    }
                    current.copyAttempt = attempt
                    source = current
                }
                withContext(Dispatchers.Main.immediate) {
                    val payload = ReflowSelectionCopyCodec.encode(request)
                    check(!closed && !closing)
                    awaitJavascript(selectionInvocation(request.capture.binding, "return api.copy($payload);"))
                    synchronized(selectionLock) {
                        val committed = copyCommitGate.committedReceipt(request)
                        if (committed != null) OperationResult.Ok(committed, request.operationId)
                        else attempt.result ?: OperationResult.Rejected("REFLOW_SELECTION_COPY_NOT_COMMITTED")
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            copyCommitGate.committedReceipt(request)?.let { return OperationResult.Ok(it, request.operationId) }
            if (cancelled is TimeoutCancellationException && callingJob.isActive)
                return OperationResult.Failed("REFLOW_SELECTION_COPY_TIMEOUT", true)
            throw cancelled
        } catch (_: Exception) {
            copyCommitGate.committedReceipt(request)?.let { return OperationResult.Ok(it, request.operationId) }
            return OperationResult.Failed("REFLOW_SELECTION_NATIVE_FAILED", false)
        } finally {
            source?.let { ownedSource ->
                synchronized(selectionLock) {
                    if (ownedSource.copyAttempt === attempt) {
                        ownedSource.copyAttempt = null
                        copyCommitGate.revoke(ownedSource)
                    }
                }
            }
        }
    }

    private fun ownsSelectionSource(source: NativeSelectionSource): Boolean = synchronized(selectionLock) {
        currentSelectionSource === source && !source.ended && !closed && !closing
    }

    private fun endSelectionSource(source: NativeSelectionSource, reason: String, discardPending: Boolean = true) {
        val receiver = synchronized(selectionLock) {
            copyCommitGate.revoke(source)
            source.ended = true
            source.activated = false
            source.latestEnvelope = null
            source.consumerJob
        }
        if (discardPending) {
            val cancelled = CancellationException(reason)
            source.queue.cancel(cancelled)
            receiver?.cancel(cancelled)
        } else source.queue.close()
        // Only this binding can be stopped; a late revoke/overflow cannot stop a new owner.
        view.post {
            if (!closed) runCatching {
                view.evaluateJavascript(selectionInvocation(source.binding, "api.stop();return true;")) { }
            }
        }
    }

    private fun selectionInvocation(binding: ReflowSelectionBinding, action: String): String =
        "(function(){var api=window.__isolatedReflowSelection;if(!api||api.bindingId!==${literal(binding.bindingId)})return false;$action})()"

    private fun nativeOk(): OperationResult<Unit> = OperationResult.Ok(Unit, "reflow-selection-native")
    private fun literal(value: String) = JsonPrimitive(value).toString()
    private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))

}
