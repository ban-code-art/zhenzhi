package com.example.myapplication.ui.mine

import com.example.myapplication.data.appearance.AppearancePalette
import com.example.myapplication.data.appearance.AppearancePreferences
import com.example.myapplication.data.appearance.DefaultAppearanceColors
import com.example.myapplication.data.appearance.DefaultAppearanceSeed
import com.example.myapplication.data.appearance.opaqueColor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal fun formatHexRgb(argb: Int): String {
    val rgb = argb and 0x00FFFFFF
    return "#%06X".format(rgb)
}

internal fun parseStrictHexRgb(input: String): Int? {
    val trimmed = input.trim()
    val hex = if (trimmed.startsWith("#")) trimmed.substring(1) else trimmed
    if (hex.length != 6) return null
    val rgb = hex.toIntOrNull(16) ?: return null
    return opaqueColor(rgb)
}

internal fun applyAddCustomColor(
    current: AppearancePreferences,
    newColorArgb: Int,
): AppearancePreferences {
    val opaque = opaqueColor(newColorArgb)
    val nextColors = if (opaque in current.customColors) current.customColors
    else current.customColors + opaque
    return current.copy(customColors = nextColors, seed = opaque).normalized()
}

internal fun applyEditCustomColor(
    current: AppearancePreferences,
    editIndex: Int,
    newColorArgb: Int,
): AppearancePreferences {
    if (editIndex !in current.customColors.indices) return current
    val oldColor = current.customColors[editIndex]
    val opaque = opaqueColor(newColorArgb)
    if (opaque == oldColor) return current
    val existingIndex = current.customColors.indexOf(opaque)
    val nextColors = if (existingIndex >= 0 && existingIndex != editIndex) {
        current.customColors.filterIndexed { idx, _ -> idx != editIndex }
    } else {
        current.customColors.toMutableList().also { it[editIndex] = opaque }
    }
    val nextSeed = if (current.seed == oldColor) opaque else current.seed
    return current.copy(customColors = nextColors, seed = nextSeed).normalized()
}

internal fun applyDeleteCustomColor(
    current: AppearancePreferences,
    deleteIndex: Int,
): AppearancePreferences {
    if (deleteIndex !in current.customColors.indices) return current
    val removed = current.customColors[deleteIndex]
    val nextColors = current.customColors.filterIndexed { idx, _ -> idx != deleteIndex }
    val nextSeed = if (current.seed == removed) {
        if (DefaultAppearanceSeed in nextColors) DefaultAppearanceSeed else null
    } else {
        current.seed
    }
    return current.copy(customColors = nextColors, seed = nextSeed).normalized()
}

internal fun hasResettableColorChanges(current: AppearancePreferences): Boolean =
    current.customColors != DefaultAppearanceColors ||
        current.seed != DefaultAppearanceSeed ||
        current.palette != AppearancePalette.Vivid

internal fun applyResetAppearanceColors(current: AppearancePreferences): AppearancePreferences =
    current.copy(
        customColors = DefaultAppearanceColors,
        seed = DefaultAppearanceSeed,
        palette = AppearancePalette.Vivid,
    ).normalized()

internal fun snapTextScaleStep(raw: Float): Float {
    val clamped = raw.coerceIn(0.8f, 1.4f)
    return ((clamped * 20f).roundToInt() / 20f).coerceIn(0.8f, 1.4f)
}

internal fun argbToHsv(argb: Int): Triple<Float, Float, Float> {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val cMax = max(r, max(g, b))
    val cMin = min(r, min(g, b))
    val delta = cMax - cMin
    val h = when {
        delta <= 1e-5f -> 0f
        cMax == r -> 60f * (((g - b) / delta) % 6f)
        cMax == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val s = if (cMax <= 1e-5f) 0f else delta / cMax
    return Triple(h, s, cMax)
}

internal fun hsvToArgb(hue: Float, sat: Float, value: Float): Int {
    val h = ((hue % 360f) + 360f) % 360f
    val s = sat.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)
    val c = v * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = v - c
    val (rp, gp, bp) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val r = ((rp + m) * 255f).roundToInt().coerceIn(0, 255)
    val g = ((gp + m) * 255f).roundToInt().coerceIn(0, 255)
    val b = ((bp + m) * 255f).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

internal fun hslToArgb(hue: Float, sat: Float, lightness: Float): Int {
    val h = ((hue % 360f) + 360f) % 360f
    val s = sat.coerceIn(0f, 1f)
    val l = lightness.coerceIn(0f, 1f)
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (rp, gp, bp) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val r = ((rp + m) * 255f).roundToInt().coerceIn(0, 255)
    val g = ((gp + m) * 255f).roundToInt().coerceIn(0, 255)
    val b = ((bp + m) * 255f).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
