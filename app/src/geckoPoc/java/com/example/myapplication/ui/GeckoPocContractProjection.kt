package com.example.myapplication.ui

import com.example.myapplication.domain.reader.*
import org.json.JSONObject

/** Only the controlled native fixture namespace is supported; nothing here writes reader state. */
internal object GeckoPocContractProjection {
    fun locator(value: JSONObject, token: ReaderSessionToken): ReaderLocator {
        val locator = ReaderLocatorCodec.decode(value.toString())
        require(ReaderLocatorValidator.validateForDocument(locator, token.documentId, token.contentRevision).isValid)
        when (locator) {
            is ReaderLocator.Reflow -> require(token.documentId.startsWith("epub:") &&
                locator.sectionId.startsWith("fixture:epub:") && locator.nativeLocator.startsWith("epubcfi("))
            is ReaderLocator.Fixed -> require(token.documentId.startsWith("pdf:") &&
                locator.sectionId == "fixture:pdf:page-${locator.pageIndex + 1}")
            else -> error("Unsupported fixture locator")
        }
        return locator
    }

    fun feature(event: JSONObject, token: ReaderSessionToken): ReaderFeatureReport {
        val value = event.getJSONObject("value")
        require(value.getString("status") == "ready")
        val locator = locator(value.getJSONObject("locator"), token)
        val caps = value.getJSONObject("capabilities")
        fun JSONObject.flag(key: String) = get(key) as Boolean
        val capabilities = ReaderCapabilities(caps.flag("selectableText"), caps.flag("images"),
            caps.flag("fixedLayout"), caps.flag("textExtraction"), caps.flag("ink"), caps.flag("search"))
        require(capabilities.fixedLayout == (locator is ReaderLocator.Fixed))
        require(!capabilities.ink && !capabilities.search && !value.flag("preciseSelectionGeometry"))
        require(value.flag("reflowable") == (locator is ReaderLocator.Reflow))
        return ReaderFeatureReport(token, locator, capabilities, value.flag("renderable"),
            value.flag("preciseSelectionGeometry"), value.flag("reflowable"),
            ReaderOcrState.valueOf(value.getString("ocrState")), event.getString("eventSequence").toLong())
    }

    fun selection(event: JSONObject, token: ReaderSessionToken, selectionId: String): ReaderSelectionEvent {
        val value = event.getJSONObject("value")
        val fragments = value.getJSONArray("fragments")
        val phase = ReaderSelectionPhase.valueOf(value.getString("phase"))
        require((phase == ReaderSelectionPhase.CLEARED) == (fragments.length() == 0))
        val selected = mutableListOf<ReaderSelectionFragment>()
        val screenRects = mutableListOf<ReaderScreenRect>()
        for (i in 0 until fragments.length()) {
            val fragment = fragments.getJSONObject(i)
            val locator = locator(fragment.getJSONObject("locator"), token)
            val quote = fragment.getString("quote")
            require(quote == when (locator) {
                is ReaderLocator.Reflow -> locator.exactQuote
                is ReaderLocator.Fixed -> locator.textQuote
                else -> null
            })
            selected += ReaderSelectionFragment(locator, quote)
        }
        val rects = value.getJSONArray("screenRects")
        val expectedRects = (0 until fragments.length()).sumOf { fragments.getJSONObject(it).getJSONArray("rects").length() }
        require(rects.length() == expectedRects)
        for (j in 0 until rects.length()) {
                val rect = rects.getJSONObject(j)
                val coordinates = listOf("left", "top", "right", "bottom").map { key ->
                    (rect.get(key) as Number).toFloat().also { require(it.isFinite()) }
                }
                require(coordinates[2] > coordinates[0] && coordinates[3] > coordinates[1])
                screenRects += ReaderScreenRect(coordinates[0], coordinates[1], coordinates[2], coordinates[3])
        }
        return ReaderSelectionEvent(token, selectionId, phase, selected.toList(), screenRects.toList())
    }
}
