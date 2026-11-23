package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.data.reader.notes.AnnotationTargetInput
import com.example.myapplication.data.reader.notes.ReaderAnnotationView
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorCodec

/** Compare the persisted Locator v1 representation; only display progression is ignored.
 * The codec deliberately omits empty prefix/suffix, which Room reads back as null.
 */
internal fun legacySameAnnotationSource(first: ReaderLocator?, second: ReaderLocator?): Boolean {
    if (first == null || second == null) return false
    val storedFirst = runCatching { ReaderLocatorCodec.decode(ReaderLocatorCodec.encode(first)) }.getOrNull() ?: return false
    val storedSecond = runCatching { ReaderLocatorCodec.decode(ReaderLocatorCodec.encode(second)) }.getOrNull() ?: return false
    return sameAnnotationSource(storedFirst, storedSecond)
}

/** A same-quote selection elsewhere, reordered targets or changed precision is another source. */
internal fun legacySelectionMatchesAnnotation(annotation: ReaderAnnotationView, quote: String,
    expected: List<AnnotationTargetInput>): Boolean {
    if (expected.isEmpty() || annotation.kind == com.example.myapplication.data.reader.notes.AnnotationKind.INK ||
        annotation.documentId != expected.first().locator.documentId || annotation.quote != quote ||
        !legacySameAnnotationSource(annotation.locator, expected.first().locator)) return false
    val actual = annotation.targets.ifEmpty {
        listOfNotNull(annotation.locator?.let { AnnotationTargetInput(it, "UNRESOLVED") })
    }
    return actual.size == expected.size && actual.zip(expected).all { (stored, selected) ->
        stored.precision == selected.precision && legacySameAnnotationSource(stored.locator, selected.locator)
    }
}
