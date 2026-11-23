package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.data.reader.importer.ReaderFormat
import com.example.myapplication.domain.reader.ReaderCapabilities

/** Disabled reasons for font scale, line height and font color controls only. */
enum class ReaderTypographyRestriction {
    PDF_ORIGINAL, FIXED_LAYOUT, UNSUPPORTED_FORMAT, CAPABILITIES_UNAVAILABLE,
}

/** A single reason is the source of truth, so enabled and disabled cannot disagree. */
data class ReaderTypographyAvailability(val restriction: ReaderTypographyRestriction?) {
    val enabled: Boolean get() = restriction == null
}

/**
 * Read-only UI policy; does not grant engine capabilities or change saved preferences.
 * Background, zoom, annotation styling and immersive mode remain independent.
 * A caller must pass capabilities from the active document, not a previous session.
 */
object ReaderSettingsPolicy {
    fun typography(
        format: ReaderFormat,
        capabilities: ReaderCapabilities?,
    ): ReaderTypographyAvailability {
        val restriction = when (format) {
            // Original PDF text cannot be recolored/reflowed, including scanned pages.
            ReaderFormat.PDF -> ReaderTypographyRestriction.PDF_ORIGINAL
            ReaderFormat.CBZ -> ReaderTypographyRestriction.FIXED_LAYOUT
            ReaderFormat.UNKNOWN -> ReaderTypographyRestriction.UNSUPPORTED_FORMAT
            ReaderFormat.EPUB, ReaderFormat.TXT, ReaderFormat.MARKDOWN,
            ReaderFormat.HTML, ReaderFormat.MHTML, ReaderFormat.FB2,
            ReaderFormat.MOBI, ReaderFormat.AZW, ReaderFormat.AZW3 -> when {
                capabilities == null -> ReaderTypographyRestriction.CAPABILITIES_UNAVAILABLE
                capabilities.fixedLayout -> ReaderTypographyRestriction.FIXED_LAYOUT
                else -> null
            }
        }
        return ReaderTypographyAvailability(restriction)
    }
}
