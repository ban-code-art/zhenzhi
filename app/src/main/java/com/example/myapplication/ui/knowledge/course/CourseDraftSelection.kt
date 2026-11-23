package com.example.myapplication.ui.knowledge.course

import android.content.Context
import com.example.myapplication.data.reader.notes.AnnotationColor
import org.json.JSONArray
import org.json.JSONObject

internal data class CourseDraftRange(val start: Int, val end: Int)
internal data class CourseDraftSelection(val range: CourseDraftRange, val quote: String)
internal data class CourseDraftHighlight(val range: CourseDraftRange, val color: AnnotationColor)

/** Draft chapters have no published reader document ID. Their offsets belong to the bundled HTML revision. */
internal class CourseDraftHighlightStore(context: Context, userId: Long?, chapterKey: String, revision: String) {
    private val preferences = context.getSharedPreferences("bundled_course_highlights", Context.MODE_PRIVATE)
    private val key = "v1:${userId ?: "guest"}:$chapterKey:$revision"

    fun load(): List<CourseDraftHighlight> {
        val stored = runCatching { JSONArray(preferences.getString(key, "[]")) }.getOrNull() ?: return emptyList()
        return (0 until stored.length()).mapNotNull { index ->
            val item = stored.optJSONObject(index) ?: return@mapNotNull null
            val start = item.optInt("start", -1)
            val end = item.optInt("end", -1)
            val color = AnnotationColor.entries.firstOrNull { it.hex == item.optString("color") }
            if (start >= 0 && end > start && color != null) CourseDraftHighlight(CourseDraftRange(start, end), color)
            else null
        }
    }

    fun save(highlight: CourseDraftHighlight): List<CourseDraftHighlight> = synchronized(preferences) {
        val highlights = load().filterNot { it.range == highlight.range } + highlight
        preferences.edit().putString(key, encodeCourseDraftHighlights(highlights)).apply()
        highlights
    }
}

internal fun encodeCourseDraftHighlights(highlights: List<CourseDraftHighlight>): String =
    JSONArray().apply {
        highlights.forEach { highlight ->
            put(JSONObject().put("start", highlight.range.start).put("end", highlight.range.end)
                .put("color", highlight.color.hex))
        }
    }.toString()
