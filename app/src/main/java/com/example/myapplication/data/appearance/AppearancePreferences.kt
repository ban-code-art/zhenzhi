package com.example.myapplication.data.appearance

import kotlin.math.roundToInt

internal enum class AppearanceMode { System, Light, Dark }
internal enum class AppearancePalette { Vivid, Tinted, Tonal, Monochrome }
internal enum class AppearanceNavigation { Floating, Fixed }
internal enum class AppearanceDockMotion { Slide, Fade }
internal enum class AppearanceTabMotion { Slide, Fade }
internal enum class AppearanceGlass { Ultra, Standard, None }
internal enum class AppearanceFont { System, Sans, Serif, Monospace }

internal const val DefaultAppearanceSeed: Int = -16_745_729

internal val DefaultAppearanceColors = listOf(
    0xFF007AFF.toInt(), 0xFFCB30E0.toInt(), 0xFFFF2D55.toInt(), 0xFFFF383C.toInt(),
    0xFFFF8D28.toInt(), 0xFFFFCC00.toInt(), 0xFF34C759.toInt(), 0xFF8E8E93.toInt(),
)

internal data class AppearancePreferences(
    val mode: AppearanceMode = AppearanceMode.System,
    val seed: Int? = DefaultAppearanceSeed,
    val palette: AppearancePalette = AppearancePalette.Vivid,
    val customColors: List<Int> = DefaultAppearanceColors,
    val pureBlack: Boolean = false,
    val navigation: AppearanceNavigation = AppearanceNavigation.Floating,
    val dockMotion: AppearanceDockMotion = AppearanceDockMotion.Slide,
    val tabMotion: AppearanceTabMotion = AppearanceTabMotion.Fade,
    val glass: AppearanceGlass = AppearanceGlass.Ultra,
    val font: AppearanceFont = AppearanceFont.System,
    val textScale: Float? = null,
    val lastCustomTextScale: Float = 1f,
    val customFont: String? = null,
) {
    fun normalized(): AppearancePreferences {
        val scale = textScale?.let(::normalizeTextScale)
        return copy(
            seed = seed?.let(::opaqueColor),
            customColors = customColors.map(::opaqueColor).distinct(),
            textScale = scale,
            lastCustomTextScale = scale ?: normalizeTextScale(lastCustomTextScale) ?: 1f,
            customFont = customFont?.trim()?.ifEmpty { null },
        )
    }
}

internal fun opaqueColor(argb: Int): Int = argb or 0xFF000000.toInt()

private fun normalizeTextScale(value: Float): Float? =
    value.takeIf { it.isFinite() && it in 0.8f..1.4f }?.let { (it * 20f).roundToInt() / 20f }
