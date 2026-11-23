package com.example.myapplication.ui.knowledge.reader

/**
 * A validated font-color choice. This persistence token is not a CSS expression.
 * Missing/invalid settings fallback is a caller decision; [parse] never hides invalid input.
 */
class ReaderFontColor private constructor(private val custom: String?) {
    fun encode(): String = custom ?: "AUTO"

    /** Explicit colors stay unchanged; the UI can project AUTO from the global palette. */
    fun resolve(theme: ReaderTheme): String = custom ?: when (theme) {
        ReaderTheme.LIGHT, ReaderTheme.SEPIA -> "#2F3B36"
        ReaderTheme.NIGHT -> "#FFFFFF"
    }

    override fun equals(other: Any?): Boolean = other is ReaderFontColor && custom == other.custom
    override fun hashCode(): Int = custom?.hashCode() ?: 0
    override fun toString(): String = encode()

    companion object {
        val Automatic = ReaderFontColor(null)

        /** Accept exactly AUTO or #RRGGBB, with ASCII hexadecimal digits only. */
        fun parse(value: String?): ReaderFontColor? {
            if (value == "AUTO") return Automatic
            if (value == null || value.length != 7 || value[0] != '#') return null
            if (value.drop(1).any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
            // ASCII conversion is deliberately independent of the device locale.
            return ReaderFontColor(value.map { if (it in 'a'..'f') it.uppercaseChar() else it }.joinToString(""))
        }
    }
}
