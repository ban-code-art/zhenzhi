package com.example.myapplication.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.graphics.fonts.SystemFonts
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.example.myapplication.data.appearance.AppearanceFont
import com.example.myapplication.data.appearance.AppearanceGlass
import com.example.myapplication.data.appearance.AppearanceMode
import com.example.myapplication.data.appearance.AppearancePreferences
import com.example.myapplication.data.appearance.AppearanceState
import com.example.myapplication.data.appearance.AppearanceStore
import com.example.myapplication.data.appearance.DefaultAppearanceSeed
import com.example.myapplication.ui.components.LocalUiGlassQuality
import com.example.myapplication.ui.components.UiGlassQuality
import com.example.myapplication.ui.components.UiGlassQualitySnapshot
import java.io.File

internal val LocalAppearance = staticCompositionLocalOf { AppearanceState() }

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean,
    dynamicColor: Boolean = false,
    paperTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MyApplicationThemeContent(
        explicitDarkTheme = darkTheme,
        dynamicColor = dynamicColor,
        paperTheme = paperTheme,
        appearanceOverride = null,
        content = content,
    )
}

@Composable
fun MyApplicationTheme(
    dynamicColor: Boolean = false,
    paperTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MyApplicationThemeContent(
        explicitDarkTheme = null,
        dynamicColor = dynamicColor,
        paperTheme = paperTheme,
        appearanceOverride = null,
        content = content,
    )
}

@Composable
internal fun MyApplicationPreviewTheme(
    preferences: AppearancePreferences,
    applyTextScale: Boolean = false,
    content: @Composable () -> Unit,
) {
    MyApplicationThemeContent(
        explicitDarkTheme = null,
        dynamicColor = false,
        paperTheme = false,
        appearanceOverride = preferences,
        applyTextScale = applyTextScale,
        syncSystemBars = false,
        content = content,
    )
}

@Composable
private fun MyApplicationThemeContent(
    explicitDarkTheme: Boolean?,
    dynamicColor: Boolean,
    paperTheme: Boolean,
    appearanceOverride: AppearancePreferences?,
    applyTextScale: Boolean = !paperTheme,
    syncSystemBars: Boolean = !paperTheme && appearanceOverride == null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val state = if (appearanceOverride != null) {
        AppearanceState(preferences = appearanceOverride, ready = true)
    } else {
        val store = remember(context.applicationContext) { AppearanceStore.forApplication(context) }
        val collected by store.state.collectAsState()
        collected
    }
    val preferences = state.preferences
    val resolvedDark = when {
        paperTheme -> false
        explicitDarkTheme != null -> explicitDarkTheme
        else -> when (preferences.mode) {
            AppearanceMode.System -> systemDark
            AppearanceMode.Light -> false
            AppearanceMode.Dark -> true
        }
    }
    val useSystemSeed = dynamicColor || preferences.seed == null
    val resolvedSeed = remember(useSystemSeed, preferences.seed, resolvedDark) {
        if (useSystemSeed) resolveSystemSeedArgb(context) else (preferences.seed ?: DefaultAppearanceSeed)
    }
    val generated = remember(resolvedSeed, preferences.palette, resolvedDark, preferences.pureBlack) {
        generateUiTheme(
            seedArgb = resolvedSeed,
            palette = preferences.palette,
            dark = resolvedDark,
            pureBlack = preferences.pureBlack && resolvedDark,
        )
    }
    val colorScheme = if (paperTheme) AppTheme.PaperColors else generated.colorScheme
    val fontFamily = remember(preferences.font, preferences.customFont) {
        resolveAppearanceFontFamily(preferences.font, preferences.customFont)
    }
    val typography = remember(fontFamily) {
        if (paperTheme) Typography else Typography.withFontFamily(fontFamily)
    }
    val glassDesired = when (preferences.glass) {
        AppearanceGlass.Ultra -> UiGlassQuality.Ultra
        AppearanceGlass.Standard -> UiGlassQuality.Standard
        AppearanceGlass.None -> UiGlassQuality.None
    }
    val currentDensity = LocalDensity.current
    val targetDensity = if (applyTextScale && preferences.textScale != null) {
        Density(currentDensity.density, fontScale = preferences.textScale)
    } else currentDensity

    if (syncSystemBars) {
        val view = LocalView.current
        LaunchedEffect(view, resolvedDark) {
            val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>()
                .firstOrNull()
            activity?.window?.let { window ->
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !resolvedDark
                controller.isAppearanceLightNavigationBars = !resolvedDark
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
    ) {
        val selectionBackground = if (paperTheme) AppTheme.BrandGreen.copy(alpha = 0.28f)
        else colorScheme.primary.copy(alpha = if (resolvedDark) 0.35f else 0.20f)
        CompositionLocalProvider(
            LocalAppearance provides state,
            LocalAppDarkTheme provides resolvedDark,
            LocalUiExtendedColors provides generated.extended,
            LocalUiGlassQuality provides UiGlassQualitySnapshot(desired = glassDesired),
            LocalDensity provides targetDensity,
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = colorScheme.primary,
                backgroundColor = selectionBackground,
            ),
            content = content,
        )
    }
}

internal fun resolveSystemSeedArgb(context: Context): Int {
    if (Build.VERSION.SDK_INT >= 31) {
        val color = runCatching {
            ContextCompat.getColor(context, android.R.color.system_accent1_500)
        }.getOrNull()
        if (color != null) return color or 0xFF000000.toInt()
    }
    return DefaultAppearanceSeed
}

internal fun resolveAppearanceFontFamily(font: AppearanceFont, customFontName: String?): FontFamily? {
    if (!customFontName.isNullOrBlank()) {
        val customTypeface = buildCustomFontTypeface(customFontName)
        if (customTypeface != null) return FontFamily(customTypeface)
    }
    return when (font) {
        AppearanceFont.System -> null
        AppearanceFont.Sans -> FontFamily.SansSerif
        AppearanceFont.Serif -> FontFamily.Serif
        AppearanceFont.Monospace -> FontFamily.Monospace
    }
}

private fun buildCustomFontTypeface(fontPathOrFamily: String): Typeface? = runCatching {
    val file = File(fontPathOrFamily)
    if (file.isFile && file.canRead() && Build.VERSION.SDK_INT >= 29) {
        val primaryFont = android.graphics.fonts.Font.Builder(file).build()
        val primaryFamily = android.graphics.fonts.FontFamily.Builder(primaryFont).build()
        return@runCatching Typeface.CustomFallbackBuilder(primaryFamily)
            .setSystemFallback("sans-serif")
            .build()
    }
    if (file.isFile && file.canRead()) {
        return@runCatching Typeface.createFromFile(file)
    }
    Typeface.create(fontPathOrFamily, Typeface.NORMAL)
}.getOrNull()

internal data class SystemFontEntry(
    val id: String,
    val displayName: String,
)

internal fun discoverAvailableSystemFonts(): List<SystemFontEntry> {
    val entries = linkedMapOf<String, SystemFontEntry>()
    if (Build.VERSION.SDK_INT >= 29) {
        runCatching {
            SystemFonts.getAvailableFonts()
                .asSequence()
                .mapNotNull { font -> font.file }
                .filter { file ->
                    file.isFile && file.canRead() &&
                        (file.name.endsWith(".ttf", ignoreCase = true) ||
                            file.name.endsWith(".otf", ignoreCase = true)) &&
                        !file.name.contains("ColorEmoji", ignoreCase = true)
                }
                .sortedBy { it.nameWithoutExtension }
                .forEach { file ->
                    val name = readableFontLabel(file.nameWithoutExtension)
                    if (name.isNotBlank() && !entries.containsKey(name.lowercase())) {
                        entries[name.lowercase()] = SystemFontEntry(
                            id = file.absolutePath,
                            displayName = name,
                        )
                    }
                }
        }
    } else {
        File("/system/fonts").listFiles()
            ?.asSequence()
            ?.filter { file ->
                file.isFile && file.canRead() &&
                    (file.name.endsWith(".ttf", ignoreCase = true) ||
                        file.name.endsWith(".otf", ignoreCase = true)) &&
                    !file.name.contains("ColorEmoji", ignoreCase = true)
            }
            ?.sortedBy { it.nameWithoutExtension }
            ?.forEach { file ->
                val name = readableFontLabel(file.nameWithoutExtension)
                if (name.isNotBlank() && !entries.containsKey(name.lowercase())) {
                    entries[name.lowercase()] = SystemFontEntry(
                        id = file.absolutePath,
                        displayName = name,
                    )
                }
            }
    }
    return entries.values.toList()
}

private fun readableFontLabel(raw: String): String =
    raw.removeSuffix("-Regular")
        .removeSuffix("_Regular")
        .replace('_', ' ')
        .trim()

private fun Typography.withFontFamily(family: FontFamily?): Typography {
    if (family == null) return this
    return Typography(
        displayLarge = displayLarge.copy(fontFamily = family),
        displayMedium = displayMedium.copy(fontFamily = family),
        displaySmall = displaySmall.copy(fontFamily = family),
        headlineLarge = headlineLarge.copy(fontFamily = family),
        headlineMedium = headlineMedium.copy(fontFamily = family),
        headlineSmall = headlineSmall.copy(fontFamily = family),
        titleLarge = titleLarge.copy(fontFamily = family),
        titleMedium = titleMedium.copy(fontFamily = family),
        titleSmall = titleSmall.copy(fontFamily = family),
        bodyLarge = bodyLarge.copy(fontFamily = family),
        bodyMedium = bodyMedium.copy(fontFamily = family),
        bodySmall = bodySmall.copy(fontFamily = family),
        labelLarge = labelLarge.copy(fontFamily = family),
        labelMedium = labelMedium.copy(fontFamily = family),
        labelSmall = labelSmall.copy(fontFamily = family),
    )
}
