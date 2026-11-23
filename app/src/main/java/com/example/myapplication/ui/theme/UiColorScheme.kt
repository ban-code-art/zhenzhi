package com.example.myapplication.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.myapplication.data.appearance.AppearancePalette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/** Extended semantic roles derived alongside the 46-role HCT scheme (spec 03 §3). */
@Immutable
internal data class UiExtendedColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val destructive: Color,
    val info: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val iconContainer: Color,
    val iconTint: Color,
    val destructiveIconContainer: Color,
    val modalScrimAlpha: Float,
    val pureBlack: Boolean,
)

@Immutable
internal data class UiGeneratedTheme(
    val colorScheme: ColorScheme,
    val extended: UiExtendedColors,
    val dark: Boolean,
)

/** Builds the 46-role Material3 [ColorScheme] and S1 extended roles from HCT seed, style, brightness, and pureBlack. */
internal fun generateUiTheme(
    seedArgb: Int,
    palette: AppearancePalette,
    dark: Boolean,
    pureBlack: Boolean,
): UiGeneratedTheme {
    val seedHct = UiHct.fromInt(seedArgb or 0xFF000000.toInt())
    val scheme = when (palette) {
        AppearancePalette.Vivid -> SchemeSpec.content(seedHct)
        AppearancePalette.Tinted -> SchemeSpec.content(seedHct)
        AppearancePalette.Tonal -> SchemeSpec.tonalSpot(seedHct)
        AppearancePalette.Monochrome -> SchemeSpec.monochrome(seedHct)
    }

    fun surfaceChroma(cap: Double): Double = when (palette) {
        AppearancePalette.Tinted -> min(seedHct.chroma / 6.0, 10.0)
        AppearancePalette.Monochrome -> 0.0
        AppearancePalette.Vivid, AppearancePalette.Tonal -> min(scheme.neutral.chroma, cap)
    }

    fun outlineVariantColor(): Color {
        val chroma = when (palette) {
            AppearancePalette.Tinted -> min(seedHct.chroma / 6.0, 10.0)
            AppearancePalette.Monochrome -> 0.0
            AppearancePalette.Vivid, AppearancePalette.Tonal -> min(scheme.neutralVariant.chroma, 4.0)
        }
        return hctColor(scheme.neutralVariant.hue, chroma, if (dark) 30.0 else 80.0)
    }

    fun pColor(tone: Double): Color {
        val chroma = if (palette == AppearancePalette.Monochrome) 0.0 else min(scheme.primary.chroma, 48.0)
        return hctColor(scheme.primary.hue, chroma, tone)
    }

    val paperCap = if (dark) 3.0 else 1.5
    val shift = if (dark && pureBlack) 6.0 else 0.0
    fun nColor(tone: Double, cap: Double = 3.0, blackOut: Boolean = false): Color {
        if (dark && pureBlack && blackOut) return Color.Black
        val adjustedTone = (tone - shift).coerceAtLeast(0.0)
        return hctColor(scheme.neutral.hue, surfaceChroma(cap), adjustedTone)
    }

    val useAccentAdjust = palette == AppearancePalette.Vivid || palette == AppearancePalette.Tinted
    val primaryColor: Color
    val onPrimaryColor: Color
    if (useAccentAdjust) {
        val t = seedHct.tone
        // Keep surface-legible primary tone in light mode even for pastel seeds so TextButton / links
        // keep >= 4.5:1 contrast, while primaryContainer / secondaryContainer preserve soft pastel fills.
        val initialTone = when {
            dark -> (t + 16.0).coerceIn(66.0, 82.0)
            t < 66.0 -> t.coerceIn(30.0, 47.0)
            else -> 44.0
        }
        val preferLightInk = !dark
        val darkInk = hctColor(seedHct.hue, min(seedHct.chroma, 12.0), 10.0)
        onPrimaryColor = if (preferLightInk) Color.White else darkInk
        primaryColor = tuneToneForContrast(
            hue = seedHct.hue,
            chroma = seedHct.chroma,
            initialTone = initialTone,
            onColor = onPrimaryColor,
            darker = preferLightInk,
            fallbackArgb = seedArgb,
        )
    } else if (palette == AppearancePalette.Monochrome) {
        primaryColor = if (dark) Color.White else Color.Black
        onPrimaryColor = if (dark) hctColor(0.0, 0.0, 10.0) else Color.White
    } else {
        onPrimaryColor = scheme.primary.color(if (dark) 20.0 else 100.0)
        primaryColor = tuneToneForContrast(
            hue = scheme.primary.hue,
            chroma = scheme.primary.chroma,
            initialTone = if (dark) 80.0 else 40.0,
            onColor = onPrimaryColor,
            darker = !dark,
            fallbackArgb = null,
        )
    }

    val primaryContainer = if (palette == AppearancePalette.Monochrome) {
        hctColor(0.0, 0.0, if (dark) 30.0 else 85.0)
    } else {
        pColor(if (dark) 30.0 else 90.0)
    }
    val onPrimaryContainer = if (palette == AppearancePalette.Monochrome) {
        hctColor(0.0, 0.0, if (dark) 90.0 else 10.0)
    } else {
        pColor(if (dark) 90.0 else 30.0)
    }

    val pageSurface = nColor(if (dark) 6.0 else 98.0, paperCap, blackOut = true)
    val surfaceDim = nColor(if (dark) 6.0 else 87.0, 3.0, blackOut = true)
    val surfaceBright = nColor(if (dark) 24.0 else 98.0, paperCap)
    val surfaceContainerLowest = nColor(if (dark) 4.0 else 100.0, 3.0, blackOut = true)
    val surfaceContainerLow = nColor(if (dark) 10.0 else 96.0, 3.0)
    val surfaceContainer = nColor(if (dark) 15.0 else 94.0, 3.0)
    val surfaceContainerHigh = nColor(if (dark) 21.0 else 92.0, 3.0)
    val surfaceContainerHighest = nColor(if (dark) 27.0 else 90.0, 3.0)

    val onSurfaceColor = if (dark) scheme.neutral.color(97.0) else scheme.neutral.color(10.0)
    val onSurfaceVariantColor = scheme.neutralVariant.color(if (dark) 80.0 else 30.0)

    val secondary = scheme.secondary.color(if (dark) 80.0 else 40.0)
    val onSecondary = scheme.secondary.color(if (dark) 20.0 else 100.0)
    val secondaryContainer = scheme.secondary.color(if (dark) 30.0 else 90.0)
    val onSecondaryContainer = scheme.secondary.color(if (dark) 90.0 else 10.0)

    val tertiary = scheme.tertiary.color(if (dark) 80.0 else 40.0)
    val onTertiary = scheme.tertiary.color(if (dark) 20.0 else 100.0)
    val tertiaryContainer = scheme.tertiary.color(if (dark) 30.0 else 90.0)
    val onTertiaryContainer = scheme.tertiary.color(if (dark) 90.0 else 10.0)

    val error = scheme.error.color(if (dark) 80.0 else 40.0)
    val onError = scheme.error.color(if (dark) 20.0 else 100.0)
    val errorContainer = scheme.error.color(if (dark) 30.0 else 90.0)
    val onErrorContainer = scheme.error.color(if (dark) 90.0 else 10.0)

    val pageBackground = if (dark) pageSurface else surfaceContainer

    val colorScheme = ColorScheme(
        primary = primaryColor,
        onPrimary = onPrimaryColor,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = scheme.primary.color(if (dark) 40.0 else 80.0),
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = pageBackground,
        onBackground = onSurfaceColor,
        surface = pageSurface,
        onSurface = onSurfaceColor,
        surfaceVariant = if (dark) surfaceContainerHigh else surfaceContainer,
        onSurfaceVariant = onSurfaceVariantColor,
        surfaceTint = primaryColor,
        inverseSurface = scheme.neutral.color(if (dark) 90.0 else 20.0),
        inverseOnSurface = scheme.neutral.color(if (dark) 20.0 else 95.0),
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        outline = scheme.neutralVariant.color(if (dark) 60.0 else 50.0),
        outlineVariant = outlineVariantColor(),
        scrim = Color.Black,
        surfaceBright = surfaceBright,
        surfaceDim = surfaceDim,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainerLowest = surfaceContainerLowest,
        primaryFixed = scheme.primary.color(90.0),
        primaryFixedDim = scheme.primary.color(80.0),
        onPrimaryFixed = scheme.primary.color(10.0),
        onPrimaryFixedVariant = scheme.primary.color(30.0),
        secondaryFixed = scheme.secondary.color(90.0),
        secondaryFixedDim = scheme.secondary.color(80.0),
        onSecondaryFixed = scheme.secondary.color(10.0),
        onSecondaryFixedVariant = scheme.secondary.color(30.0),
        tertiaryFixed = scheme.tertiary.color(90.0),
        tertiaryFixedDim = scheme.tertiary.color(80.0),
        onTertiaryFixed = scheme.tertiary.color(10.0),
        onTertiaryFixedVariant = scheme.tertiary.color(30.0),
    )

    val successSeed = UiHct.fromInt(0xFF4CAF50.toInt())
    val warningSeed = UiHct.fromInt(0xFFFF9800.toInt())
    val iconBaseChroma = when (palette) {
        AppearancePalette.Monochrome -> 0.0
        AppearancePalette.Tonal -> min(scheme.primary.chroma, 36.0)
        AppearancePalette.Vivid, AppearancePalette.Tinted -> seedHct.chroma
    }
    val iconContainerChroma = if (iconBaseChroma <= 0.0) 0.0 else min(iconBaseChroma * if (dark) 0.28 else 0.24, if (dark) 16.0 else 14.0)
    val iconInkChroma = if (iconBaseChroma <= 0.0) 0.0 else min(iconBaseChroma * if (dark) 0.45 else 0.55, if (dark) 28.0 else 34.0)
    val iconContainer = hctColor(
        scheme.primary.hue,
        iconContainerChroma,
        if (dark) if (pureBlack) 20.0 else 25.0 else 91.0,
    )
    val iconTint = hctColor(
        scheme.primary.hue,
        iconInkChroma,
        if (dark) 76.0 else 36.0,
    )
    val destructiveIconContainer = hctColor(
        25.0,
        if (palette == AppearancePalette.Monochrome) 0.0 else if (dark) 16.0 else 14.0,
        if (dark) if (pureBlack) 20.0 else 25.0 else 92.0,
    )
    val extended = UiExtendedColors(
        success = hctColor(successSeed.hue, min(successSeed.chroma, 48.0), if (dark) 80.0 else 40.0),
        successContainer = hctColor(successSeed.hue, min(successSeed.chroma, 24.0), if (dark) 30.0 else 90.0),
        onSuccessContainer = hctColor(successSeed.hue, min(successSeed.chroma, 48.0), if (dark) 90.0 else 30.0),
        warning = hctColor(warningSeed.hue, min(warningSeed.chroma, 48.0), if (dark) 80.0 else 40.0),
        warningContainer = hctColor(warningSeed.hue, min(warningSeed.chroma, 24.0), if (dark) 30.0 else 90.0),
        onWarningContainer = hctColor(warningSeed.hue, min(warningSeed.chroma, 48.0), if (dark) 90.0 else 30.0),
        destructive = if (dark) Color(0xFFFF4245) else Color(0xFFFF383C),
        info = secondary,
        infoContainer = secondaryContainer,
        onInfoContainer = onSecondaryContainer,
        iconContainer = iconContainer,
        iconTint = iconTint,
        destructiveIconContainer = destructiveIconContainer,
        modalScrimAlpha = if (dark) .48f else .20f,
        pureBlack = dark && pureBlack,
    )
    return UiGeneratedTheme(colorScheme, extended, dark)
}

/** Shifts dark surfaces by the container lift so elevated bottom sheets remain visually separated (spec 03 §3). */
internal fun ColorScheme.elevatedForSheet(dark: Boolean): ColorScheme {
    if (!dark) return this
    return copy(
        background = surfaceContainer,
        onBackground = onSurface,
        surface = surfaceContainer,
        surfaceContainer = surfaceContainerHigh,
        surfaceContainerHigh = surfaceContainerHighest,
        surfaceContainerLow = surfaceContainer,
        surfaceVariant = surfaceContainerHigh,
    )
}

internal fun hctColor(hue: Double, chroma: Double, tone: Double): Color =
    Color(UiHct.from(hue, chroma, tone).toInt())

private class TonePalette(val hue: Double, val chroma: Double) {
    fun color(tone: Double): Color = hctColor(hue, chroma, tone)
}

private class SchemeSpec(
    val primary: TonePalette,
    val secondary: TonePalette,
    val tertiary: TonePalette,
    val neutral: TonePalette,
    val neutralVariant: TonePalette,
    val error: TonePalette = TonePalette(25.0, 84.0),
) {
    companion object {
        fun content(seed: UiHct): SchemeSpec {
            val tertiaryHue = sanitizeDegrees(seed.hue + 60.0)
            val tertiaryFixed = fixIfDisliked(UiHct.from(tertiaryHue, seed.chroma, seed.tone))
            return SchemeSpec(
                primary = TonePalette(seed.hue, seed.chroma),
                secondary = TonePalette(seed.hue, max(seed.chroma - 32.0, seed.chroma * 0.5)),
                tertiary = TonePalette(tertiaryFixed.hue, tertiaryFixed.chroma),
                neutral = TonePalette(seed.hue, seed.chroma / 8.0),
                neutralVariant = TonePalette(seed.hue, seed.chroma / 8.0 + 4.0),
            )
        }

        fun tonalSpot(seed: UiHct): SchemeSpec = SchemeSpec(
            primary = TonePalette(seed.hue, 36.0),
            secondary = TonePalette(seed.hue, 16.0),
            tertiary = TonePalette(sanitizeDegrees(seed.hue + 60.0), 24.0),
            neutral = TonePalette(seed.hue, 6.0),
            neutralVariant = TonePalette(seed.hue, 8.0),
        )

        fun monochrome(seed: UiHct): SchemeSpec = SchemeSpec(
            primary = TonePalette(seed.hue, 0.0),
            secondary = TonePalette(seed.hue, 0.0),
            tertiary = TonePalette(seed.hue, 0.0),
            neutral = TonePalette(seed.hue, 0.0),
            neutralVariant = TonePalette(seed.hue, 0.0),
        )

        private fun fixIfDisliked(hct: UiHct): UiHct {
            val roundedHue = hct.hue.roundToInt()
            val roundedChroma = hct.chroma.roundToInt()
            val roundedTone = hct.tone.roundToInt()
            val disliked = roundedHue in 90..111 && roundedChroma > 16 && roundedTone < 65
            return if (disliked) UiHct.from(hct.hue, hct.chroma, 70.0) else hct
        }
    }
}

private fun sanitizeDegrees(degrees: Double): Double {
    var deg = degrees % 360.0
    if (deg < 0.0) deg += 360.0
    return deg
}

/**
 * Pure-Kotlin implementation of Material Color Utilities' HCT (Hue, Chroma, Tone) color space.
 * Combines CAM16 hue/chroma with CIE L* luminance for perceptual uniformity across Light/Dark themes.
 */
internal data class UiHct(
    val hue: Double,
    val chroma: Double,
    val tone: Double,
    val argb: Int,
) {
    fun toInt(): Int = argb

    companion object {
        fun fromInt(argb: Int): UiHct {
            val cam = Cam16.fromInt(argb)
            val lstar = ColorUtils.lstarFromArgb(argb)
            return UiHct(cam.hue, cam.chroma, lstar, argb or 0xFF000000.toInt())
        }

        fun from(hue: Double, chroma: Double, tone: Double): UiHct {
            val argb = HctSolver.solveToInt(hue, chroma, tone)
            return fromInt(argb)
        }
    }
}

private object ColorUtils {
    private val SRGB_TO_XYZ = arrayOf(
        doubleArrayOf(0.41233895, 0.35762064, 0.18051042),
        doubleArrayOf(0.2126, 0.7152, 0.0722),
        doubleArrayOf(0.01932141, 0.11916382, 0.95034478),
    )
    private val XYZ_TO_SRGB = arrayOf(
        doubleArrayOf(3.2413774792388685, -1.5376652402851851, -0.49885366846268053),
        doubleArrayOf(-0.9691452513005321, 1.8758853451067872, 0.04156585616912061),
        doubleArrayOf(0.05562093689691305, -0.20395524564742123, 1.0571799111220335),
    )

    fun linearized(rgbComponent: Int): Double {
        val normalized = rgbComponent / 255.0
        return if (normalized <= 0.040449936) normalized / 12.92 * 100.0
        else ((normalized + 0.055) / 1.055).pow(2.4) * 100.0
    }

    fun delinearized(rgbComponent: Double): Int {
        val normalized = rgbComponent / 100.0
        val delinearized = if (normalized <= 0.0031308) normalized * 12.92
        else 1.055 * normalized.pow(1.0 / 2.4) - 0.055
        return (delinearized * 255.0).roundToInt().coerceIn(0, 255)
    }

    fun xyzFromArgb(argb: Int): DoubleArray {
        val r = linearized((argb shr 16) and 0xFF)
        val g = linearized((argb shr 8) and 0xFF)
        val b = linearized(argb and 0xFF)
        return doubleArrayOf(
            r * SRGB_TO_XYZ[0][0] + g * SRGB_TO_XYZ[0][1] + b * SRGB_TO_XYZ[0][2],
            r * SRGB_TO_XYZ[1][0] + g * SRGB_TO_XYZ[1][1] + b * SRGB_TO_XYZ[1][2],
            r * SRGB_TO_XYZ[2][0] + g * SRGB_TO_XYZ[2][1] + b * SRGB_TO_XYZ[2][2],
        )
    }

    fun argbFromLinrgb(linrgb: DoubleArray): Int {
        val r = delinearized(linrgb[0])
        val g = delinearized(linrgb[1])
        val b = delinearized(linrgb[2])
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun argbFromXyz(x: Double, y: Double, z: Double): Int {
        val r = XYZ_TO_SRGB[0][0] * x + XYZ_TO_SRGB[0][1] * y + XYZ_TO_SRGB[0][2] * z
        val g = XYZ_TO_SRGB[1][0] * x + XYZ_TO_SRGB[1][1] * y + XYZ_TO_SRGB[1][2] * z
        val b = XYZ_TO_SRGB[2][0] * x + XYZ_TO_SRGB[2][1] * y + XYZ_TO_SRGB[2][2] * z
        return (0xFF shl 24) or (delinearized(r) shl 16) or (delinearized(g) shl 8) or delinearized(b)
    }

    fun lstarFromArgb(argb: Int): Double {
        val y = xyzFromArgb(argb)[1]
        return 116.0 * labF(y / 100.0) - 16.0
    }

    fun yFromLstar(lstar: Double): Double = 100.0 * labInvf((lstar + 16.0) / 116.0)

    private fun labF(t: Double): Double {
        val e = 216.0 / 24389.0
        val kappa = 24389.0 / 27.0
        return if (t > e) cbrt(t) else (kappa * t + 16.0) / 116.0
    }

    private fun labInvf(ft: Double): Double {
        val e = 216.0 / 24389.0
        val kappa = 24389.0 / 27.0
        val ft3 = ft * ft * ft
        return if (ft3 > e) ft3 else (116.0 * ft - 16.0) / kappa
    }
}

private object ViewingConditions {
    val whitePoint = doubleArrayOf(95.047, 100.0, 108.883)
    val aw = 29.980997194447333
    val nbb = 1.0003040045593807
    val ncb = 1.0003040045593807
    val c = 0.69
    val nc = 1.0
    val rgbD = doubleArrayOf(1.02117770275752, 0.9863077294280124, 0.9337762943125782)
    val fl = 0.3884814537800353
    val flRoot = fl.pow(0.25)
    val z = 1.909169568483652
    val n = 0.18418651851244416
}

private class Cam16(val hue: Double, val chroma: Double, val j: Double) {
    companion object {
        private val XYZ_TO_CAM16RGB = arrayOf(
            doubleArrayOf(0.401288, 0.650173, -0.051461),
            doubleArrayOf(-0.250268, 1.204414, 0.045854),
            doubleArrayOf(-0.002079, 0.048952, 0.953127),
        )

        fun fromInt(argb: Int): Cam16 {
            val xyz = ColorUtils.xyzFromArgb(argb)
            val rT = xyz[0] * XYZ_TO_CAM16RGB[0][0] + xyz[1] * XYZ_TO_CAM16RGB[0][1] + xyz[2] * XYZ_TO_CAM16RGB[0][2]
            val gT = xyz[0] * XYZ_TO_CAM16RGB[1][0] + xyz[1] * XYZ_TO_CAM16RGB[1][1] + xyz[2] * XYZ_TO_CAM16RGB[1][2]
            val bT = xyz[0] * XYZ_TO_CAM16RGB[2][0] + xyz[1] * XYZ_TO_CAM16RGB[2][1] + xyz[2] * XYZ_TO_CAM16RGB[2][2]
            val rD = ViewingConditions.rgbD[0] * rT
            val gD = ViewingConditions.rgbD[1] * gT
            val bD = ViewingConditions.rgbD[2] * bT
            val rAF = (ViewingConditions.fl * abs(rD) / 100.0).pow(0.42)
            val gAF = (ViewingConditions.fl * abs(gD) / 100.0).pow(0.42)
            val bAF = (ViewingConditions.fl * abs(bD) / 100.0).pow(0.42)
            val rA = sign(rD) * 400.0 * rAF / (rAF + 27.13)
            val gA = sign(gD) * 400.0 * gAF / (gAF + 27.13)
            val bA = sign(bD) * 400.0 * bAF / (bAF + 27.13)
            val a = (11.0 * rA + -12.0 * gA + bA) / 11.0
            val b = (rA + gA - 2.0 * bA) / 9.0
            val u = (20.0 * rA + 20.0 * gA + 21.0 * bA) / 20.0
            val p2 = (40.0 * rA + 20.0 * gA + bA) / 20.0
            val atanDegrees = Math.toDegrees(atan2(b, a))
            val hue = sanitizeDegrees(atanDegrees)
            val ac = p2 * ViewingConditions.nbb
            val j = 100.0 * (ac / ViewingConditions.aw).pow(ViewingConditions.c * ViewingConditions.z)
            val huePrime = if (hue < 20.14) hue + 360.0 else hue
            val eHue = 0.25 * (cos(Math.toRadians(huePrime) + 2.0) + 3.8)
            val p1 = 50000.0 / 13.0 * eHue * ViewingConditions.nc * ViewingConditions.ncb
            val t = p1 * hypot(a, b) / (u + 0.305)
            val alpha = (1.64 - 0.29.pow(ViewingConditions.n)).pow(0.73) * t.pow(0.9)
            val chroma = alpha * kotlin.math.sqrt(j / 100.0)
            return Cam16(hue, chroma, j)
        }
    }
}

private object HctSolver {
    private val SCALED_DISCOUNT_FROM_LINRGB = arrayOf(
        doubleArrayOf(0.001200833568784504, 0.002389694492170889, 0.0002795742885861124),
        doubleArrayOf(0.0005891086651375999, 0.0029785502573438758, 0.0003270666104008398),
        doubleArrayOf(0.00010146692491640572, 0.0005364214359186694, 0.0032979401770712076),
    )
    private val LINRGB_FROM_SCALED_DISCOUNT = arrayOf(
        doubleArrayOf(1373.2198709594231, -1100.4251190754821, -7.278681089101213),
        doubleArrayOf(-271.815969077903, 559.6580465940733, -32.46047482791194),
        doubleArrayOf(1.9622899599665666, -57.173814538844006, 308.7233197812385),
    )
    private val Y_FROM_LINRGB = doubleArrayOf(0.2126, 0.7152, 0.0722)
    private val CRITICAL_PLANES = doubleArrayOf(
        0.015176349177441876, 0.045529047532325624, 0.07588174588720938, 0.10623444424209313,
        0.13658714259697685, 0.16693984095186062, 0.19729253930674434, 0.2276452376616281,
        0.2579979360165119, 0.28835063437139563, 0.3188300904430532, 0.350925934958123,
        0.3848314933096426, 0.42057480301049466, 0.458183274052838, 0.4976837250274023,
        0.5391024159806381, 0.5824650784040898, 0.6277969426914107, 0.6751227633498623,
        0.7244668422128921, 0.775853049866786, 0.829304845476233, 0.8848452951698498,
        0.942497089126609, 1.0022825574869039, 1.0642236851973577, 1.1283421258858297,
        1.1946592148522128, 1.2631959812511864, 1.3339731595349034, 1.407011200216447,
        1.4823302800086415, 1.5599503113873272, 1.6398909516233677, 1.7221716113234105,
        1.8068114625156377, 1.8938294463134073, 1.9832442801866852, 2.075074464868551,
        2.1693382909216234, 2.2660538449872063, 2.36523901573795, 2.4669114995532007,
        2.5710888059345764, 2.6777882626779785, 2.7870270208169257, 2.898822059350997,
        3.0131901897720907, 3.1301480604002863, 3.2497121605402226, 3.3718988244681087,
        3.4967242352587946, 3.624204428461639, 3.754355295633311, 3.887192587735158,
        4.022731918402185, 4.160988767090289, 4.301978482107941, 4.445716283538092,
        4.592217266055746, 4.741496401646282, 4.893568542229298, 5.048448422192488,
        5.20615066083972, 5.3666897647573375, 5.5300801301023865, 5.696336044816294,
        5.865471690767354, 6.037501145825082, 6.212438385869475, 6.390297286737924,
        6.571091626112461, 6.7548350853498045, 6.941541251256611, 7.131223617812143,
        7.323895587840543, 7.5195704746346665, 7.7182615035334345, 7.919981813454504,
        8.124744458384042, 8.332562408825165, 8.543448553206703, 8.757415699253682,
        8.974476575321063, 9.194643831691977, 9.417930041841839, 9.644347703669503,
        9.873909240696694, 10.106627003236781, 10.342513269534024, 10.58158024687427,
        10.8238400726681, 11.069304815507364, 11.317986476196008, 11.569896988756009,
        11.825048221409341, 12.083451977536606, 12.345119996613247, 12.610063955123938,
        12.878295467455942, 13.149826086772048, 13.42466730586372, 13.702830557985108,
        13.984327217668513, 14.269168601521828, 14.55736596900856, 14.848930523210871,
        15.143873411576273, 15.44220572664832, 15.743938506781891, 16.04908273684337,
        16.35764934889634, 16.66964922287304, 16.985093187232053, 17.30399201960269,
        17.62635644741625, 17.95219714852476, 18.281524751807332, 18.614349837764564,
        18.95068293910138, 19.290534541298456, 19.633915083172692, 19.98083495742689,
        20.331304511189067, 20.685334046541502, 21.042933821039977, 21.404114048223256,
        21.76888489811322, 22.137256497705877, 22.50923893145328, 22.884842241736916,
        23.264076429332462, 23.6469514538663, 24.033477234264016, 24.42366364919083,
        24.817520537484558, 25.21505769858089, 25.61628489293138, 26.021211842414342,
        26.429848230738664, 26.842203703840827, 27.258287870275353, 27.678110301598522,
        28.10168053274597, 28.529008062403893, 28.96010235337422, 29.39497283293396,
        29.83362889318845, 30.276079891419332, 30.722335150426627, 31.172403958865512,
        31.62629557157785, 32.08401920991837, 32.54558406207592, 33.010999283389665,
        33.4802739966603, 33.953417292456834, 34.430438229418264, 34.911345834551085,
        35.39614910352207, 35.88485700094671, 36.37747846067349, 36.87402238606382,
        37.37449765026789, 37.87891309649659, 38.38727753828926, 38.89959975977785,
        39.41588851594697, 39.93615253289054, 40.460400508064545, 40.98864111053629,
        41.520882981230194, 42.05713473317016, 42.597404951718396, 43.141702194811224,
        43.6900349931913, 44.24241185063697, 44.798841244188324, 45.35933162437017,
        45.92389141541209, 46.49252901546552, 47.065252796817916, 47.64207110610409,
        48.22299226451468, 48.808024568002054, 49.3971762874833, 49.9904556690408,
        50.587870934119984, 51.189430279724725, 51.79514187861014, 52.40501387947288,
        53.0190544071392, 53.637271562750364, 54.259673423945976, 54.88626804504493,
        55.517063457223934, 56.15206766869424, 56.79128866487574, 57.43473440856916,
        58.08241284012621, 58.734331877617365, 59.39049941699807, 60.05092333227251,
        60.715611475655585, 61.38457167773311, 62.057811747619894, 62.7353394731159,
        63.417162620860914, 64.10328893648692, 64.79372614476921, 65.48848194977529,
        66.18756403501224, 66.89098006357258, 67.59873767827808, 68.31084450182222,
        69.02730813691093, 69.74813616640164, 70.47333615344107, 71.20291564160104,
        71.93688215501312, 72.67524319850172, 73.41800625771542, 74.16517879925733,
        74.9167682708136, 75.67278210128072, 76.43322770089146, 77.1981124613393,
        77.96744375590167, 78.74122893956174, 79.51947534912904, 80.30219030335869,
        81.08938110306934, 81.88105503125999, 82.67721935322541, 83.4778813166706,
        84.28304815182372, 85.09272707154808, 85.90692527145302, 86.72564993000343,
        87.54890820862819, 88.3767072518277, 89.2090541872801, 90.04595612594655,
        90.88742016217518, 91.73345337380438, 92.58406282226491, 93.43925555268066,
        94.29903859396902, 95.16341895893969, 96.03240364439274, 96.9059996312159,
        97.78421388448044, 98.6670533535366, 99.55452497210776,
    )

    fun solveToInt(hueDegrees: Double, chroma: Double, lstar: Double): Int {
        if (chroma < 0.0001 || lstar < 0.0001 || lstar > 99.9999) {
            return ColorUtils.argbFromXyz(
                ViewingConditions.whitePoint[0] * ColorUtils.yFromLstar(lstar) / 100.0,
                ViewingConditions.whitePoint[1] * ColorUtils.yFromLstar(lstar) / 100.0,
                ViewingConditions.whitePoint[2] * ColorUtils.yFromLstar(lstar) / 100.0,
            )
        }
        val hueRadians = Math.toRadians(sanitizeDegrees(hueDegrees))
        val y = ColorUtils.yFromLstar(lstar)
        val exactAnswer = findResultByJ(hueRadians, chroma, y)
        if (exactAnswer != 0) return exactAnswer
        val linrgb = bisectToLimit(y, hueRadians)
        return ColorUtils.argbFromLinrgb(linrgb)
    }

    private fun findResultByJ(hueRadians: Double, chroma: Double, y: Double): Int {
        var j = kotlin.math.sqrt(y) * 11.0
        val viewingConditions = ViewingConditions
        val tInnerCoeff = 1.0 / (1.64 - 0.29.pow(viewingConditions.n)).pow(0.73)
        val eHue = 0.25 * (cos(hueRadians + 2.0) + 3.8)
        val p1 = 50000.0 / 13.0 * eHue * viewingConditions.nc * viewingConditions.ncb
        val hSin = sin(hueRadians)
        val hCos = cos(hueRadians)
        for (iterationRound in 0 until 5) {
            val jNormalized = j / 100.0
            val alpha = if (chroma == 0.0 || j == 0.0) 0.0 else chroma / kotlin.math.sqrt(jNormalized)
            val t = (alpha * tInnerCoeff).pow(1.0 / 0.9)
            val ac = viewingConditions.aw * jNormalized.pow(1.0 / viewingConditions.c / viewingConditions.z)
            val p2 = ac / viewingConditions.nbb
            val gamma = 23.0 * (p2 + 0.305) * t / (23.0 * p1 + 11.0 * t * hCos + 108.0 * t * hSin)
            val a = gamma * hCos
            val b = gamma * hSin
            val rA = (460.0 * p2 + 451.0 * a + 288.0 * b) / 1403.0
            val gA = (460.0 * p2 - 891.0 * a - 261.0 * b) / 1403.0
            val bA = (460.0 * p2 - 220.0 * a - 6300.0 * b) / 1403.0
            val rCScaled = inverseChromaticAdaptation(rA)
            val gCScaled = inverseChromaticAdaptation(gA)
            val bCScaled = inverseChromaticAdaptation(bA)
            val linrgb = doubleArrayOf(
                rCScaled * LINRGB_FROM_SCALED_DISCOUNT[0][0] + gCScaled * LINRGB_FROM_SCALED_DISCOUNT[0][1] + bCScaled * LINRGB_FROM_SCALED_DISCOUNT[0][2],
                rCScaled * LINRGB_FROM_SCALED_DISCOUNT[1][0] + gCScaled * LINRGB_FROM_SCALED_DISCOUNT[1][1] + bCScaled * LINRGB_FROM_SCALED_DISCOUNT[1][2],
                rCScaled * LINRGB_FROM_SCALED_DISCOUNT[2][0] + gCScaled * LINRGB_FROM_SCALED_DISCOUNT[2][1] + bCScaled * LINRGB_FROM_SCALED_DISCOUNT[2][2],
            )
            if (linrgb[0] < 0.0 || linrgb[1] < 0.0 || linrgb[2] < 0.0) return 0
            val kR = Y_FROM_LINRGB[0]; val kG = Y_FROM_LINRGB[1]; val kB = Y_FROM_LINRGB[2]
            val fnj = kR * linrgb[0] + kG * linrgb[1] + kB * linrgb[2]
            if (fnj <= 0.0) return 0
            if (iterationRound == 4 || abs(fnj - y) < 0.002) {
                return if (linrgb[0] > 100.01 || linrgb[1] > 100.01 || linrgb[2] > 100.01) 0
                else ColorUtils.argbFromLinrgb(linrgb)
            }
            j -= (fnj - y) * j / (2.0 * fnj)
        }
        return 0
    }

    private fun inverseChromaticAdaptation(adapted: Double): Double {
        val adaptedAbs = abs(adapted)
        val base = max(0.0, 27.13 * adaptedAbs / (400.0 - adaptedAbs))
        return sign(adapted) * base.pow(1.0 / 0.42)
    }

    private fun bisectToLimit(y: Double, targetHue: Double): DoubleArray {
        val target = sanitizeRadians(targetHue)
        val vertices = mutableListOf<Pair<Double, DoubleArray>>()
        for (i in 0..11) {
            val candidate = nthVertex(y, i)
            if (candidate[0] < 0.0) continue
            val duplicate = vertices.any { (_, existing) ->
                abs(candidate[0] - existing[0]) + abs(candidate[1] - existing[1]) + abs(candidate[2] - existing[2]) < 1e-6
            }
            if (!duplicate) vertices.add(sanitizeRadians(hueOf(candidate)) to candidate)
        }
        if (vertices.size < 2) return doubleArrayOf(y, y, y)
        vertices.sortBy { it.first }
        var left = vertices.last().second
        var right = vertices.first().second
        for (i in 0 until vertices.lastIndex) {
            if (target >= vertices[i].first && target <= vertices[i + 1].first) {
                left = vertices[i].second
                right = vertices[i + 1].second
                break
            }
        }
        var leftHue = sanitizeRadians(hueOf(left))
        for (i in 0 until 24) {
            val mid = DoubleArray(3) { (left[it] + right[it]) / 2.0 }
            val midHue = sanitizeRadians(hueOf(mid))
            if (areInCyclicOrder(leftHue, target, midHue)) {
                right = mid
            } else {
                left = mid
                leftHue = midHue
            }
            if (abs(left[0] - right[0]) + abs(left[1] - right[1]) + abs(left[2] - right[2]) < 1e-6) break
        }
        return DoubleArray(3) { (left[it] + right[it]) / 2.0 }
    }

    private fun nthVertex(y: Double, n: Int): DoubleArray {
        val kR = Y_FROM_LINRGB[0]; val kG = Y_FROM_LINRGB[1]; val kB = Y_FROM_LINRGB[2]
        val coordA = if (n % 4 <= 1) 0.0 else 100.0
        val coordB = if (n % 2 == 0) 0.0 else 100.0
        return when {
            n < 4 -> {
                val g = (y - kR * coordA - kB * coordB) / kG
                if (g in 0.0..100.0) doubleArrayOf(coordA, g, coordB) else doubleArrayOf(-1.0, -1.0, -1.0)
            }
            n < 8 -> {
                val b = (y - kR * coordA - kG * coordB) / kB
                if (b in 0.0..100.0) doubleArrayOf(coordA, coordB, b) else doubleArrayOf(-1.0, -1.0, -1.0)
            }
            else -> {
                val r = (y - kG * coordA - kB * coordB) / kR
                if (r in 0.0..100.0) doubleArrayOf(r, coordA, coordB) else doubleArrayOf(-1.0, -1.0, -1.0)
            }
        }
    }

    private fun areInCyclicOrder(a: Double, b: Double, c: Double): Boolean {
        val deltaAB = sanitizeRadians(b - a)
        val deltaAC = sanitizeRadians(c - a)
        return deltaAB < deltaAC
    }

    private fun sanitizeRadians(angle: Double): Double =
        (angle + Math.PI * 8) % (Math.PI * 2)

    private fun hueOf(linrgb: DoubleArray): Double {
        val rC = linrgb[0] * SCALED_DISCOUNT_FROM_LINRGB[0][0] + linrgb[1] * SCALED_DISCOUNT_FROM_LINRGB[0][1] + linrgb[2] * SCALED_DISCOUNT_FROM_LINRGB[0][2]
        val gC = linrgb[0] * SCALED_DISCOUNT_FROM_LINRGB[1][0] + linrgb[1] * SCALED_DISCOUNT_FROM_LINRGB[1][1] + linrgb[2] * SCALED_DISCOUNT_FROM_LINRGB[1][2]
        val bC = linrgb[0] * SCALED_DISCOUNT_FROM_LINRGB[2][0] + linrgb[1] * SCALED_DISCOUNT_FROM_LINRGB[2][1] + linrgb[2] * SCALED_DISCOUNT_FROM_LINRGB[2][2]
        val rA = chromaticAdaptation(rC)
        val gA = chromaticAdaptation(gC)
        val bA = chromaticAdaptation(bC)
        val a = (11.0 * rA + -12.0 * gA + bA) / 11.0
        val b = (rA + gA - 2.0 * bA) / 9.0
        return atan2(b, a)
    }

    private fun chromaticAdaptation(component: Double): Double {
        val af = abs(component).pow(0.42)
        return sign(component) * 400.0 * af / (af + 27.13)
    }
}

internal fun contrastRatio(a: Color, b: Color): Float {
    val l1 = a.luminance()
    val l2 = b.luminance()
    return ((max(l1, l2) + 0.05f) / (min(l1, l2) + 0.05f))
}

private fun tuneToneForContrast(
    hue: Double,
    chroma: Double,
    initialTone: Double,
    onColor: Color,
    darker: Boolean,
    fallbackArgb: Int?,
): Color {
    var candidateTone = initialTone
    var color = if (fallbackArgb != null && abs(candidateTone - UiHct.fromInt(fallbackArgb or 0xFF000000.toInt()).tone) < 0.05) {
        Color(fallbackArgb or 0xFF000000.toInt())
    } else {
        hctColor(hue, chroma, candidateTone)
    }
    repeat(30) {
        if (contrastRatio(onColor, color) >= 4.55f) return color
        candidateTone = if (darker) (candidateTone - 1.5).coerceAtLeast(10.0) else (candidateTone + 1.5).coerceAtMost(95.0)
        color = hctColor(hue, chroma, candidateTone)
    }
    return color
}
