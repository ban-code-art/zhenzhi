package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.domain.reader.ReaderLocator

/** Display progression changes with layout and IME; it is not part of the selected source range. */
internal fun sameAnnotationSource(first: ReaderLocator?, second: ReaderLocator?): Boolean {
    fun stable(locator: ReaderLocator?): ReaderLocator? = when (locator) {
        is ReaderLocator.Reflow -> locator.copy(progression = null)
        is ReaderLocator.Fixed -> locator.copy(progression = null)
        is ReaderLocator.Note -> locator.copy(progression = null)
        null -> null
    }
    return first != null && second != null && stable(first) == stable(second)
}
