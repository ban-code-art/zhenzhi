package com.example.myapplication.ui.mine

import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun formatDateTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))

internal fun formatDuration(seconds: Int?): String {
    if (seconds == null) return "-"
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}

internal fun typeLabel(type: String): String = when (type) {
    "CHOICE" -> "选择题"
    "JUDGE" -> "判断题"
    "FILL" -> "填空题"
    "ESSAY" -> "应用题"
    else -> type
}

internal fun formatAnswer(type: String, raw: String): String {
    val text = raw.trim()
    return when (type) {
        "CHOICE" -> text.trim('"')
        "JUDGE" -> when (text.trim('"').lowercase()) {
            "true", "对", "1" -> "对"
            "false", "错", "0" -> "错"
            else -> text.trim('"')
        }
        "FILL", "ESSAY" -> formatAnswerArray(text)
        else -> text
    }
}

private fun formatAnswerArray(text: String): String {
    if (!text.startsWith("[")) return text.trim('"')
    return try {
        val array = JSONArray(text)
        (0 until array.length())
            .map { array.optString(it) }
            .filter { it.isNotBlank() }
            .joinToString("；")
    } catch (e: Exception) {
        text
    }
}
