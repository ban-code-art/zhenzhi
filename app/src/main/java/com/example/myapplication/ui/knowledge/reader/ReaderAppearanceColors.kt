package com.example.myapplication.ui.knowledge.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import java.util.Locale

/** The same resolved colors feed the Compose viewport, WebView and settings sample. */
internal data class ReaderAppearanceColors(val backgroundHex: String, val foregroundHex: String) {
    init {
        require(ReaderFontColor.parse(backgroundHex)?.encode() == backgroundHex)
        require(ReaderFontColor.parse(foregroundHex)?.encode() == foregroundHex)
        require(backgroundHex != "AUTO" && foregroundHex != "AUTO")
    }

    val background: Color get() = Color(0xFF000000L or backgroundHex.drop(1).toLong(16))
    val foreground: Color get() = Color(0xFF000000L or foregroundHex.drop(1).toLong(16))
}

@Suppress("UNUSED_PARAMETER") // Historical preference flag remains readable; paper now follows app appearance.
internal fun resolveReaderAppearanceColors(
    settings: ReaderSettings,
    followsGlobalTheme: Boolean,
    globalDark: Boolean,
    globalBackgroundHex: String,
    globalForegroundHex: String,
): ReaderAppearanceColors {
    val page = ReaderAppearanceColors(globalBackgroundHex, globalForegroundHex)
    val foreground = when {
        settings.fontColor != ReaderFontColor.Automatic -> settings.fontColor.resolve(settings.theme)
        globalDark && page.background.luminance() <= .18f -> "#FFFFFF"
        else -> page.foregroundHex
    }
    return page.copy(foregroundHex = foreground)
}

@Composable
@ReadOnlyComposable
internal fun readerAppearanceColors(settings: ReaderSettings, followsGlobalTheme: Boolean): ReaderAppearanceColors =
    resolveReaderAppearanceColors(
        settings = settings,
        followsGlobalTheme = followsGlobalTheme,
        globalDark = UiRoles.dark,
        globalBackgroundHex = UiRoles.surface(UiSurfaceRole.Page).readerHex(),
        globalForegroundHex = UiRoles.pageForeground.readerHex(),
    )

private fun Color.readerHex(): String = String.format(Locale.ROOT, "#%06X", toArgb() and 0xFFFFFF)
