package com.example.myapplication.data.reader.content

import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/** The existing ISO_INSTANT UTC contract without java.time linkage on the minimum Android API. */
internal object Api24UtcTimestamp {
    private val utc = TimeZone.getTimeZone("UTC")
    private val instant = Regex("([0-9]{4}|\\+[0-9]{5,10}|-[0-9]{4,10})-([0-9]{2})-([0-9]{2})[Tt]([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]{0,9}))?Z")

    fun isValid(value: String): Boolean {
        val match = instant.matchEntire(value) ?: return false
        val yearText = match.groupValues[1]
        val year = yearText.toLongOrNull() ?: return false
        if (yearText.startsWith('-') && year == 0L) return false
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        val hour = match.groupValues[4].toInt()
        val minute = match.groupValues[5].toInt()
        val second = match.groupValues[6].toInt()
        if (month !in 1..12 || minute !in 0..59 || hour !in 0..24) return false
        val leap = year % 4L == 0L && (year % 100L != 0L || year % 400L == 0L)
        val days = when (month) { 2 -> if (leap) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }
        if (day !in 1..days) return false
        if (hour == 24) {
            if (minute != 0 || second != 0 || match.groupValues[7].any { it != '0' }) return false
            // ISO_INSTANT checks the range after midnight rolls into the next date.
            // The final day before the minimum year can therefore normalize exactly into Instant.MIN.
            val normalizedYear = if (month == 12 && day == 31) year + 1 else year
            return normalizedYear in -1_000_000_000L..1_000_000_000L
        }
        // ISO_INSTANT's leap-second handling accepts 23:59:60 and maps it to second 59.
        return year in -1_000_000_000L..1_000_000_000L &&
            (second in 0..59 || hour == 23 && minute == 59 && second == 60)
    }

    fun fromEpochMillis(epochMillis: Long): String {
        val calendar = GregorianCalendar(utc, Locale.ROOT).apply {
            gregorianChange = Date(Long.MIN_VALUE) // ISO uses the proleptic Gregorian calendar, including before 1582.
            timeInMillis = epochMillis
        }
        val year = if (calendar.get(Calendar.ERA) == GregorianCalendar.BC) 1 - calendar.get(Calendar.YEAR) else calendar.get(Calendar.YEAR)
        val yearText = when {
            year in 0..9999 -> year.toString().padStart(4, '0')
            year < 0 -> "-" + (-year).toString().padStart(4, '0')
            else -> "+$year"
        }
        val base = String.format(Locale.ROOT, "%s-%02d-%02dT%02d:%02d:%02d", yearText,
            calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH), calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE), calendar.get(Calendar.SECOND))
        val millis = calendar.get(Calendar.MILLISECOND)
        return base + if (millis == 0) "Z" else String.format(Locale.ROOT, ".%03dZ", millis)
    }

    /** Used by the host Instant compatibility bridge; source text is already canonical ISO_INSTANT. */
    fun truncateMillis(value: String): String {
        require(isValid(value)) { "invalid UTC instant" }
        val match = requireNotNull(instant.matchEntire(value))
        val base = value.substringBefore('.').removeSuffix("Z")
        val millis = match.groupValues[7].take(3).padEnd(3, '0')
        return base + if (millis == "000") "Z" else ".$millis" + "Z"
    }
}
