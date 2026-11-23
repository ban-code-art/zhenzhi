package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.data.reader.notes.ReaderSelectionCodec
import com.example.myapplication.domain.reader.ReaderLocator
import kotlinx.serialization.json.*

/** This consumes a freshly evaluated DOM response, never the bridge's cached position. */
internal fun legacyBookmarkDomOwns(input: BookmarkPositionInput, raw: String, epoch: String): Boolean {
    val payload = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return false
    if ((payload["epoch"] as? JsonPrimitive)?.contentOrNull != epoch) return false
    fun matches(value: JsonElement?, locator: ReaderLocator): Boolean {
        val position = locator as? ReaderLocator.Reflow ?: return false
        val actual = ReaderSelectionCodec.decode(value?.toString()) ?: return false
        return actual.sectionId == position.sectionId && actual.blockId == position.blockId &&
            actual.canonicalStart == position.canonicalStart &&
            (position.canonicalEnd == position.canonicalStart ||
                actual.canonicalEnd == position.canonicalEnd && actual.quote == position.exactQuote)
    }
    val selected = input.selection
    val selection = payload["selection"] as? JsonObject
    val hasNativeSelection = (payload["hasNativeSelection"] as? JsonPrimitive)?.booleanOrNull ?: return false
    if (hasNativeSelection != !selected.isNullOrEmpty()) return false
    if (!selected.isNullOrEmpty()) {
        val fragments = (selection?.get("fragments") as? JsonArray)?.toList()
            ?: listOfNotNull(selection)
        return fragments.size == selected.size && fragments.zip(selected).all { (actual, expected) ->
            matches(actual, expected.locator)
        }
    }
    if (selection != null) return false
    if (!matches(payload["viewport"], input.viewport ?: return false)) return false
    val spans = ((payload["viewport"] as? JsonObject)?.get("visibleRanges") as? JsonArray).orEmpty()
    return spans.size == input.visibleRanges.size && spans.zip(input.visibleRanges).all { (actual, expected) -> matches(actual, expected) }
}
