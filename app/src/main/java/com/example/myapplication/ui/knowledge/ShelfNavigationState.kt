package com.example.myapplication.ui.knowledge

import com.example.myapplication.data.reader.ReaderShelfItem
import java.util.Locale
import kotlinx.serialization.json.*

/** Only navigation is saved; documents and jobs are always re-observed for the current identity. */
data class ShelfNavigationState(
    val ownerUserId: Long,
    val sessionEpoch: Long,
    val query: String = "",
    val sort: String = "RECENT_READ",
    val anchorKey: String? = null,
    val index: Int = 0,
    val offset: Int = 0,
) {
    fun capture(index: Int, offset: Int, anchorKey: String?, itemCount: Int): ShelfNavigationState =
        if (itemCount <= 0) this else copy(index = index.coerceIn(0, itemCount - 1),
            offset = offset.coerceAtLeast(0), anchorKey = anchorKey?.take(512))

    fun encode(): String = buildJsonObject {
        put("ownerUserId", ownerUserId); put("sessionEpoch", sessionEpoch)
        put("query", query); put("sort", sort); put("index", index); put("offset", offset)
        anchorKey?.let { put("anchorKey", it) }
    }.toString()

    companion object {
        const val SAVED_KEY = "importedShelfNavigation"
        val SORTS = listOf("RECENT_READ", "TITLE_ASC", "TITLE_DESC")
        fun project(items: List<ReaderShelfItem>, query: String, sort: String): List<ReaderShelfItem> {
            val needle = query.trim()
            val filtered = items.filter { it.kind == "IMPORTED" && (needle.isBlank() ||
                it.title.contains(needle, true) || it.author?.contains(needle, true) == true) }
            val title = compareBy<ReaderShelfItem> { it.title.lowercase(Locale.ROOT) }
            val order = when (sort) {
                "TITLE_ASC" -> title
                "TITLE_DESC" -> title.reversed()
                else -> compareByDescending<ReaderShelfItem> { it.lastReadAt }
            }
            return filtered.sortedWith(order.thenBy { it.documentId })
        }

        fun restore(raw: String?, ownerUserId: Long, sessionEpoch: Long): ShelfNavigationState {
            val empty = ShelfNavigationState(ownerUserId, sessionEpoch)
            if (raw.isNullOrBlank() || raw.length > 32768) return empty
            return runCatching {
                val root = Json.parseToJsonElement(raw).jsonObject
                if (root["ownerUserId"]?.jsonPrimitive?.longOrNull != ownerUserId ||
                    root["sessionEpoch"]?.jsonPrimitive?.longOrNull != sessionEpoch) return empty
                empty.copy(query = root["query"]?.jsonPrimitive?.contentOrNull.orEmpty().take(1024),
                    sort = root["sort"]?.jsonPrimitive?.contentOrNull?.takeIf { it in SORTS } ?: "RECENT_READ",
                    anchorKey = root["anchorKey"]?.jsonPrimitive?.contentOrNull?.take(512),
                    index = (root["index"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, 1000000),
                    offset = (root["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, 1000000))
            }.getOrDefault(empty)
        }
    }
}
