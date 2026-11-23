package com.example.myapplication.ui.components.learning

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.example.myapplication.data.appearance.AppearancePalette
import com.example.myapplication.ui.theme.LocalAppearance
import com.example.myapplication.ui.theme.UiHct
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.hctColor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Multi-layered iridescent style tokens derived from the active HCT seed,
 * [AppearancePalette], dark mode, and pure-black setting.
 *
 * Instead of tinting the whole card with one flat color, a single user seed
 * produces five coordinated optical layers: nacreous base, four-anchor
 * interference spectrum, dynamic specular rim, stacked plates, and five
 * distinct floating particle roles.
 */
@Immutable
internal data class StudyCardIridescentStyle(
    val seedArgb: Int,
    val palette: AppearancePalette,
    val dark: Boolean,
    val pureBlack: Boolean,
    val monochrome: Boolean,
    // L1: Base nacreous substrate (static, high readability)
    val baseTop: Color,
    val baseMid: Color,
    val baseBottom: Color,
    val baseAccentA: Color,
    val baseAccentB: Color,
    val grainTint: Color,
    val lipGlow: Color,
    // L2: Four-anchor thin-film interference spectrum
    val iridescentA: Color,
    val iridescentB: Color,
    val iridescentC: Color,
    val iridescentD: Color,
    val foilIntensity: Float,
    val tailWeight: Float,
    val glintStrength: Float,
    // L3: Dynamic perimeter rim & bevel
    val rimHighlight: Color,
    val rimPrismA: Color,
    val rimPrismB: Color,
    val innerBevel: Color,
    val haloPrimary: Color,
    val haloSecondary: Color,
    val rimStrength: Float,
    // L4: Stacked rear plates
    val nearPlate: Color,
    val farPlate: Color,
    val backBorderStart: Color,
    val backBorderEnd: Color,
    val shadow: Color,
    // L5: Five particle roles + page washes
    val particlePrimary: Color,
    val particleSecondary: Color,
    val particleTransition: Color,
    val particleWarmGold: Color,
    val particlePearl: Color,
    val washA: Color,
    val washB: Color,
    val washC: Color,
    // Expanded mini book-card accents
    val miniWashA: Color,
    val miniWashB: Color,
    val miniRimA: Color,
    val miniRimB: Color,
)

@Composable
internal fun rememberStudyCardIridescentStyle(): StudyCardIridescentStyle {
    val preferences = LocalAppearance.current.preferences
    val dark = UiRoles.dark
    val pureBlack = UiRoles.extended.pureBlack
    val accentArgb = UiRoles.accent.toArgb()
    val seedArgb = preferences.seed ?: accentArgb
    val palette = preferences.palette
    return remember(seedArgb, palette, dark, pureBlack) {
        resolveStudyCardIridescentStyle(
            seedArgb = seedArgb,
            palette = palette,
            dark = dark,
            pureBlack = pureBlack,
        )
    }
}

internal fun resolveStudyCardIridescentStyle(
    seedArgb: Int,
    palette: AppearancePalette,
    dark: Boolean,
    pureBlack: Boolean,
): StudyCardIridescentStyle {
    val seedHct = UiHct.fromInt(seedArgb or 0xFF000000.toInt())
    val h = seedHct.hue
    val mono = palette == AppearancePalette.Monochrome

    if (mono) {
        return resolveMonochromeMoonstoneStyle(seedArgb, h, dark, pureBlack)
    }

    val vivid = palette == AppearancePalette.Vivid
    val tinted = palette == AppearancePalette.Tinted
    val tonal = palette == AppearancePalette.Tonal

    // Harmonic hue offsets around the user's seed hue for genuine multi-color nacre refraction.
    val hueWarmBias = wrapDegrees(h - 18.0)
    val hueCoolBias = wrapDegrees(h + 26.0)
    val hueShort = wrapDegrees(h - 38.0)
    val hueJump = avoidMurkyYellowGreen(wrapDegrees(h + 68.0))
    val hueTail = avoidMurkyYellowGreen(wrapDegrees(h + 148.0))
    val hueRimA = wrapDegrees(h - 42.0)
    val hueRimB = avoidMurkyYellowGreen(wrapDegrees(h + 72.0))

    val baseChroma = when {
        tinted -> min(max(seedHct.chroma * 0.16, 4.0), 10.0)
        vivid -> min(max(seedHct.chroma * 0.11, 3.5), 7.5)
        else -> min(max(seedHct.chroma * 0.08, 2.5), 5.5)
    }
    val baseShift = if (dark && pureBlack) 7.0 else 0.0

    val baseTop = hctColor(hueWarmBias, baseChroma * 1.10, if (dark) (22.0 - baseShift).coerceAtLeast(8.0) else 96.5)
        .copy(alpha = if (dark) 0.96f else 0.95f)
    val baseMid = hctColor(h, baseChroma * 0.90, if (dark) (18.5 - baseShift).coerceAtLeast(6.0) else 94.0)
        .copy(alpha = if (dark) 0.95f else 0.92f)
    val baseBottom = hctColor(hueCoolBias, baseChroma * 1.35, if (dark) (20.5 - baseShift).coerceAtLeast(7.5) else 91.0)
        .copy(alpha = if (dark) 0.94f else 0.91f)
    val baseAccentChroma = if (tinted) 16.0 else if (vivid) 18.0 else 11.0
    val baseAccentA = hctColor(hueShort, baseAccentChroma, if (dark) (25.0 - baseShift * 0.7).coerceAtLeast(9.0) else 91.5)
        .copy(alpha = if (dark) 0.34f else 0.42f)
    val baseAccentB = hctColor(hueJump, baseAccentChroma * 0.95, if (dark) (24.0 - baseShift * 0.7).coerceAtLeast(8.5) else 92.0)
        .copy(alpha = if (dark) 0.32f else 0.40f)

    // Interference chroma & tones tuned for silky mother-of-pearl (high tone in light, luminous jewel in dark).
    val iriChromaScale = when {
        vivid -> 1.15
        tinted -> 0.92
        tonal -> 0.72
        else -> 0.90
    }
    val seedChroma = seedHct.chroma.coerceIn(28.0, 78.0)
    val iriToneMain = if (dark) 68.0 else 80.0
    val iriToneSub = if (dark) 72.0 else 83.0

    val iridescentA = hctColor(h, min(seedChroma * 0.70 * iriChromaScale, 54.0), iriToneMain)
    val iridescentB = hctColor(hueShort, min(seedChroma * 0.66 * iriChromaScale, 50.0), iriToneSub)
    val iridescentC = hctColor(hueJump, min(seedChroma * 0.62 * iriChromaScale, 48.0), if (dark) 74.0 else 84.0)
    val iridescentD = hctColor(hueTail, min(seedChroma * 0.54 * iriChromaScale, 42.0), if (dark) 70.0 else 82.0)

    val foilIntensity = when {
        vivid -> 1.0f
        tinted -> 0.90f
        tonal -> 0.72f
        else -> 0.85f
    }
    val tailWeight = when {
        vivid -> 1.0f
        tinted -> 0.85f
        tonal -> 0.45f
        else -> 0.70f
    }
    val glintStrength = when {
        vivid -> 1.0f
        tinted -> 0.85f
        tonal -> 0.65f
        else -> 0.80f
    }
    val rimStrength = when {
        vivid -> 1.0f
        tinted -> 0.90f
        tonal -> 0.76f
        else -> 0.85f
    }

    val rimHighlight = hctColor(hueWarmBias, 8.0, if (dark) 94.0 else 99.0)
    val rimPrismA = hctColor(hueRimA, min(seedChroma * 0.68 * iriChromaScale, 48.0), if (dark) 76.0 else 80.0)
    val rimPrismB = hctColor(hueRimB, min(seedChroma * 0.64 * iriChromaScale, 44.0), if (dark) 78.0 else 82.0)
    val innerBevel = hctColor(h, 12.0, if (dark) 88.0 else 98.0).copy(alpha = if (dark) 0.22f else 0.48f)

    val haloPrimary = hctColor(h, min(seedChroma * 0.60, 42.0), if (dark) 68.0 else 76.0)
    val haloSecondary = hctColor(hueJump, min(seedChroma * 0.52, 38.0), if (dark) 72.0 else 80.0)

    val nearPlate = hctColor(
        wrapDegrees(h + 12.0),
        baseChroma * 1.15,
        if (dark) (16.5 - baseShift * 0.7).coerceAtLeast(5.0) else 90.0,
    ).copy(alpha = 0.95f)
    val farPlate = hctColor(
        wrapDegrees(h + 34.0),
        baseChroma * 1.25,
        if (dark) (13.0 - baseShift * 0.6).coerceAtLeast(3.5) else 85.5,
    ).copy(alpha = 0.94f)
    val backBorderStart = rimHighlight.copy(alpha = if (dark) 0.34f else 0.74f)
    val backBorderEnd = rimPrismB.copy(alpha = if (dark) 0.28f else 0.45f)
    val shadow = hctColor(h, min(baseChroma * 2.0, 20.0), if (dark) 4.0 else 44.0)

    val particleChroma = min(seedChroma * 0.74 * iriChromaScale, 52.0)
    val particleTone = if (dark) 79.0 else 60.0
    val particlePrimary = hctColor(h, particleChroma, particleTone)
    val particleSecondary = hctColor(hueJump, particleChroma * 0.90, if (dark) 81.0 else 62.0)
    val particleTransition = hctColor(hueShort, particleChroma * 0.84, if (dark) 77.0 else 64.0)
    val particleWarmGold = hctColor(
        avoidMurkyYellowGreen(wrapDegrees(h + 150.0)),
        min(particleChroma * 0.78, 42.0),
        if (dark) 84.0 else 66.0,
    )
    val particlePearl = hctColor(hueWarmBias, 10.0, if (dark) 95.0 else 99.0)

    val washTone = if (dark) 34.0 else 93.0
    val washChroma = if (dark) 22.0 else 18.0
    val washA = hctColor(h, washChroma, washTone)
    val washB = hctColor(hueJump, washChroma * 0.9, washTone + if (dark) 2.0 else 1.0)
    val washC = hctColor(hueShort, washChroma * 0.85, washTone)

    val miniWashAlpha = if (dark) 0.12f else 0.10f
    val miniRimAlpha = if (dark) 0.42f else 0.56f
    return StudyCardIridescentStyle(
        seedArgb = seedArgb,
        palette = palette,
        dark = dark,
        pureBlack = pureBlack,
        monochrome = false,
        baseTop = baseTop,
        baseMid = baseMid,
        baseBottom = baseBottom,
        baseAccentA = baseAccentA,
        baseAccentB = baseAccentB,
        grainTint = rimHighlight.copy(alpha = if (dark) 0.055f else 0.075f),
        lipGlow = rimPrismB.copy(alpha = if (dark) 0.24f else 0.26f),
        iridescentA = iridescentA,
        iridescentB = iridescentB,
        iridescentC = iridescentC,
        iridescentD = iridescentD,
        foilIntensity = foilIntensity,
        tailWeight = tailWeight,
        glintStrength = glintStrength,
        rimHighlight = rimHighlight,
        rimPrismA = rimPrismA,
        rimPrismB = rimPrismB,
        innerBevel = innerBevel,
        haloPrimary = haloPrimary,
        haloSecondary = haloSecondary,
        rimStrength = rimStrength,
        nearPlate = nearPlate,
        farPlate = farPlate,
        backBorderStart = backBorderStart,
        backBorderEnd = backBorderEnd,
        shadow = shadow,
        particlePrimary = particlePrimary,
        particleSecondary = particleSecondary,
        particleTransition = particleTransition,
        particleWarmGold = particleWarmGold,
        particlePearl = particlePearl,
        washA = washA,
        washB = washB,
        washC = washC,
        miniWashA = iridescentA.copy(alpha = miniWashAlpha),
        miniWashB = iridescentC.copy(alpha = miniWashAlpha * 0.85f),
        miniRimA = rimPrismA.copy(alpha = miniRimAlpha),
        miniRimB = rimPrismB.copy(alpha = miniRimAlpha),
    )
}

/**
 * Monochrome mode renders as Obsidian / Moonstone Silver Foil rather than flat gray,
 * preserving metallic sheen, crisp specular rim highlights, and platinum-pearl particles.
 */
private fun resolveMonochromeMoonstoneStyle(
    seedArgb: Int,
    hue: Double,
    dark: Boolean,
    pureBlack: Boolean,
): StudyCardIridescentStyle {
    val shift = if (dark && pureBlack) 6.0 else 0.0
    val baseTop = hctColor(hue, 2.0, if (dark) (20.0 - shift).coerceAtLeast(6.0) else 97.5).copy(alpha = 0.95f)
    val baseMid = hctColor(hue, 1.0, if (dark) (15.5 - shift).coerceAtLeast(4.0) else 94.0).copy(alpha = 0.93f)
    val baseBottom = hctColor(wrapDegrees(hue + 20.0), 3.0, if (dark) (18.0 - shift).coerceAtLeast(5.0) else 90.0).copy(alpha = 0.91f)

    val iriA = hctColor(220.0, 5.5, if (dark) 78.0 else 86.0)
    val iriB = hctColor(195.0, 4.5, if (dark) 84.0 else 90.0)
    val iriC = hctColor(265.0, 5.0, if (dark) 74.0 else 84.0)
    val iriD = hctColor(42.0, 4.0, if (dark) 82.0 else 92.0)

    val rimHigh = if (dark) Color(0xFFF3F6FC) else Color(0xFFFFFFFF)
    val rimA = hctColor(215.0, 6.0, if (dark) 76.0 else 80.0)
    val rimB = hctColor(255.0, 5.0, if (dark) 72.0 else 76.0)

    return StudyCardIridescentStyle(
        seedArgb = seedArgb,
        palette = AppearancePalette.Monochrome,
        dark = dark,
        pureBlack = pureBlack,
        monochrome = true,
        baseTop = baseTop,
        baseMid = baseMid,
        baseBottom = baseBottom,
        baseAccentA = iriA.copy(alpha = if (dark) 0.24f else 0.28f),
        baseAccentB = iriC.copy(alpha = if (dark) 0.22f else 0.26f),
        grainTint = rimHigh.copy(alpha = 0.055f),
        lipGlow = rimA.copy(alpha = if (dark) 0.22f else 0.20f),
        iridescentA = iriA,
        iridescentB = iriB,
        iridescentC = iriC,
        iridescentD = iriD,
        foilIntensity = 0.68f,
        tailWeight = 0.15f,
        glintStrength = 0.85f,
        rimHighlight = rimHigh,
        rimPrismA = rimA,
        rimPrismB = rimB,
        innerBevel = rimHigh.copy(alpha = if (dark) 0.20f else 0.45f),
        haloPrimary = iriA,
        haloSecondary = iriC,
        rimStrength = 0.92f,
        nearPlate = hctColor(hue, 2.0, if (dark) (14.5 - shift * 0.7).coerceAtLeast(4.0) else 89.0).copy(alpha = 0.95f),
        farPlate = hctColor(hue, 2.5, if (dark) (11.5 - shift * 0.6).coerceAtLeast(3.0) else 84.5).copy(alpha = 0.94f),
        backBorderStart = rimHigh.copy(alpha = if (dark) 0.32f else 0.68f),
        backBorderEnd = rimB.copy(alpha = if (dark) 0.26f else 0.42f),
        shadow = hctColor(hue, 2.0, if (dark) 3.0 else 40.0),
        particlePrimary = hctColor(215.0, 5.0, if (dark) 84.0 else 58.0),
        particleSecondary = hctColor(195.0, 4.0, if (dark) 88.0 else 64.0),
        particleTransition = hctColor(250.0, 4.5, if (dark) 78.0 else 66.0),
        particleWarmGold = hctColor(44.0, 6.0, if (dark) 88.0 else 68.0),
        particlePearl = rimHigh,
        washA = hctColor(215.0, 4.0, if (dark) 32.0 else 92.0),
        washB = hctColor(195.0, 3.0, if (dark) 34.0 else 93.0),
        washC = hctColor(250.0, 4.0, if (dark) 31.0 else 91.0),
        miniWashA = iriA.copy(alpha = if (dark) 0.08f else 0.07f),
        miniWashB = iriB.copy(alpha = if (dark) 0.06f else 0.05f),
        miniRimA = rimA.copy(alpha = if (dark) 0.36f else 0.46f),
        miniRimB = rimB.copy(alpha = if (dark) 0.30f else 0.38f),
    )
}

private fun wrapDegrees(degrees: Double): Double {
    var result = degrees % 360.0
    if (result < 0.0) result += 360.0
    return result
}

private fun avoidMurkyYellowGreen(hue: Double): Double {
    val rounded = hue.roundToInt()
    return if (rounded in 88..114) wrapDegrees(hue + 32.0) else hue
}
