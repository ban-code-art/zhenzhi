package com.example.myapplication.domain.reader

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

sealed interface ReaderLocator {
    val schemaVersion: Int
    val documentId: String
    val sectionId: String
    val contentRevision: String
    val progression: Float?

    data class Reflow(
        override val documentId: String,
        override val sectionId: String,
        override val contentRevision: String,
        override val progression: Float?,
        val nativeLocator: String,
        val exactQuote: String,
        val prefix: String? = null,
        val suffix: String? = null,
        val canonicalStart: Int,
        val canonicalEnd: Int,
        val blockId: String,
    ) : ReaderLocator {
        override val schemaVersion: Int get() = CURRENT_SCHEMA_VERSION
    }

    data class Fixed(
        override val documentId: String,
        override val sectionId: String,
        override val contentRevision: String,
        override val progression: Float?,
        val pageIndex: Int,
        val pageBox: NormalizedRect,
        val rotationDegrees: Int,
        val rects: List<NormalizedRect>,
        val textQuote: String? = null,
    ) : ReaderLocator {
        override val schemaVersion: Int get() = CURRENT_SCHEMA_VERSION
    }

    data class Note(
        override val documentId: String,
        override val sectionId: String,
        override val contentRevision: String,
        override val progression: Float?,
        val notebookId: String,
        val noteRevision: Int,
        val blockId: String? = null,
        val canonicalStart: Int? = null,
        val canonicalEnd: Int? = null,
    ) : ReaderLocator {
        override val schemaVersion: Int get() = CURRENT_SCHEMA_VERSION
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        val SUPPORTED_ROTATIONS = setOf(0, 90, 180, 270)
    }
}

data class LocatorValidation(val errors: List<String>) {
    val isValid: Boolean get() = errors.isEmpty()
}

object ReaderLocatorValidator {
    private const val FLOAT_TOLERANCE = 0.0001f
    private const val MAX_ID_LENGTH = 512
    private const val MAX_CONTEXT_CODE_POINTS = 64
    private const val MAX_QUOTE_CODE_POINTS = 8192

    fun validate(locator: ReaderLocator): LocatorValidation {
        val errors = mutableListOf<String>()
        if (locator.schemaVersion != ReaderLocator.CURRENT_SCHEMA_VERSION) {
            errors += "unsupported locator schema version: ${locator.schemaVersion}"
        }
        validateIdentity(locator.documentId, "documentId", errors)
        validateIdentity(locator.sectionId, "sectionId", errors)
        validateIdentity(locator.contentRevision, "contentRevision", errors)
        locator.progression?.let {
            if (!it.isFinite() || it < 0f || it > 1f) {
                errors += "progression must be finite and within 0..1"
            }
        }

        when (locator) {
            is ReaderLocator.Reflow -> {
                validateIdentity(locator.nativeLocator, "nativeLocator", errors)
                validateQuote(locator.exactQuote, "exactQuote", errors)
                validateOptionalContext(locator.prefix, "prefix", errors)
                validateOptionalContext(locator.suffix, "suffix", errors)
                if (locator.canonicalStart < 0) errors += "canonicalStart must be >= 0"
                if (locator.canonicalEnd < locator.canonicalStart) {
                    errors += "canonicalEnd must be >= canonicalStart"
                }
                validateIdentity(locator.blockId, "blockId", errors)
            }

            is ReaderLocator.Fixed -> {
                if (locator.pageIndex < 0) errors += "pageIndex must be >= 0"
                if (locator.rotationDegrees !in ReaderLocator.SUPPORTED_ROTATIONS) {
                    errors += "rotationDegrees must be one of 0/90/180/270"
                }
                validateRect(locator.pageBox, "pageBox", errors)
                locator.rects.forEachIndexed { index, rect -> validateRect(rect, "rects[$index]", errors) }
                locator.textQuote?.let { validateQuote(it, "textQuote", errors) }
            }

            is ReaderLocator.Note -> {
                validateIdentity(locator.notebookId, "notebookId", errors)
                if (locator.noteRevision < 0) errors += "noteRevision must be >= 0"
                locator.blockId?.let { validateIdentity(it, "blockId", errors) }
                val start = locator.canonicalStart
                val end = locator.canonicalEnd
                if ((start == null) != (end == null)) {
                    errors += "note canonicalStart and canonicalEnd must be provided together"
                }
                if (start != null && start < 0) errors += "note canonicalStart must be >= 0"
                if (start != null && end != null && end < start) {
                    errors += "note canonicalEnd must be >= canonicalStart"
                }
                if (locator.blockId.isNullOrBlank() && start == null) {
                    errors += "note requires blockId or a canonical character range"
                }
            }
        }
        return LocatorValidation(errors)
    }

    /**
     * Verifies that a persisted locator still belongs to the document and,
     * when supplied, the exact immutable content revision being opened.
     */
    fun validateForDocument(
        locator: ReaderLocator,
        expectedDocumentId: String,
        expectedContentRevision: String? = null,
    ): LocatorValidation {
        val errors = validate(locator).errors.toMutableList()
        if (locator.documentId != expectedDocumentId) {
            errors += "locator documentId does not match the opened document"
        }
        if (expectedContentRevision != null && locator.contentRevision != expectedContentRevision) {
            errors += "locator contentRevision does not match the opened revision"
        }
        return LocatorValidation(errors)
    }

    fun isWellFormedUtf16(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val unit = value[index]
            when {
                Character.isHighSurrogate(unit) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                        return false
                    }
                    index += 2
                }

                Character.isLowSurrogate(unit) -> return false
                else -> index += 1
            }
        }
        return true
    }

    private fun validateIdentity(value: String, field: String, errors: MutableList<String>) {
        if (value.isBlank()) errors += "$field must not be blank"
        if (value.length > MAX_ID_LENGTH) errors += "$field is too long"
        if (value.any(Char::isISOControl)) errors += "$field contains control characters"
    }

    private fun validateQuote(value: String, field: String, errors: MutableList<String>) {
        if (value.isBlank()) {
            errors += "$field must not be blank"
            return
        }
        if (!isWellFormedUtf16(value)) {
            errors += "$field contains an unpaired UTF-16 surrogate"
        }
        if (value.codePointCount(0, value.length) > MAX_QUOTE_CODE_POINTS) {
            errors += "$field exceeds $MAX_QUOTE_CODE_POINTS Unicode code points"
        }
    }

    private fun validateOptionalContext(value: String?, field: String, errors: MutableList<String>) {
        if (value == null || value.isEmpty()) return
        if (!isWellFormedUtf16(value)) {
            errors += "$field contains an unpaired UTF-16 surrogate"
        }
        if (value.codePointCount(0, value.length) > MAX_CONTEXT_CODE_POINTS) {
            errors += "$field exceeds $MAX_CONTEXT_CODE_POINTS Unicode code points"
        }
    }

    private fun validateRect(rect: NormalizedRect, field: String, errors: MutableList<String>) {
        val values = listOf(rect.left, rect.top, rect.right, rect.bottom)
        if (values.any { !it.isFinite() }) {
            errors += "$field contains a non-finite value"
            return
        }
        if (rect.left < -FLOAT_TOLERANCE || rect.top < -FLOAT_TOLERANCE ||
            rect.right > 1f + FLOAT_TOLERANCE || rect.bottom > 1f + FLOAT_TOLERANCE
        ) {
            errors += "$field must be normalized within 0..1"
        }
        if (rect.width <= FLOAT_TOLERANCE || rect.height <= FLOAT_TOLERANCE) {
            errors += "$field must have positive width and height"
        }
    }
}