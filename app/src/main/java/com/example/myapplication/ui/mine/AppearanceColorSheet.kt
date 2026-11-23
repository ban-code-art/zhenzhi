package com.example.myapplication.ui.mine

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.appearance.AppearancePalette
import com.example.myapplication.data.appearance.AppearancePreferences
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiSheet
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import com.example.myapplication.ui.theme.generateUiTheme
import com.example.myapplication.ui.theme.hctColor
import kotlin.math.roundToInt

internal enum class ColorPickerTab(val label: String) {
    Grid("网格"),
    Spectrum("光谱"),
    Sliders("滑杆"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CustomColorSheet(
    initialArgb: Int,
    palette: AppearancePalette,
    pureBlack: Boolean,
    ownerKey: String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var draftArgb by rememberSaveable(ownerKey) { mutableStateOf(initialArgb) }
    var hexInput by rememberSaveable(ownerKey) { mutableStateOf(formatHexRgb(initialArgb)) }
    var pickerTab by rememberSaveable(ownerKey) { mutableStateOf(ColorPickerTab.Grid) }
    var pickerInteracting by remember { mutableStateOf(false) }

    val parsedHex = parseStrictHexRgb(hexInput)
    val hexError = if (parsedHex == null) "请输入 6 位十六进制颜色，例如 #007AFF" else null
    val validConfirm = parsedHex != null

    fun applyDraftFromPicker(argb: Int) {
        draftArgb = argb
        hexInput = formatHexRgb(argb)
    }

    UiSheet(
        title = "自定义颜色",
        onDismiss = onDismiss,
        actionsAtTop = true,
        maxContentFraction = .84f,
        dragContentAtTop = { !pickerInteracting },
        actions = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UiAction(
                    label = "取消",
                    onClick = onDismiss,
                    inheritedPane = true,
                    actionId = "appearance.color.cancel",
                    modifier = Modifier.testTag("appearance-color-sheet-cancel"),
                )
                Text(
                    "自定义颜色",
                    style = UiRoles.text(UiRoles.pageTitle),
                    color = UiRoles.foreground,
                    modifier = Modifier.semantics { heading() },
                )
                UiAction(
                    label = "确认",
                    onClick = { parsedHex?.let(onConfirm) },
                    enabled = validConfirm,
                    filled = true,
                    inheritedPane = false,
                    actionId = "appearance.color.confirm",
                    animatePress = false,
                    modifier = Modifier.testTag("appearance-color-sheet-confirm"),
                )
            }
        },
        modifier = Modifier.testTag("appearance-color-sheet"),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fontScale = LocalDensity.current.fontScale
            val stackFields = maxWidth < 312.dp || fontScale >= 1.35f
            if (stackFields) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DualThemeColorMiniPreviews(draftArgb, palette, pureBlack)
                    HexColorField(
                        value = hexInput,
                        error = hexError,
                        onValueChange = { raw ->
                            hexInput = raw
                            parseStrictHexRgb(raw)?.let { draftArgb = it }
                        },
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DualThemeColorMiniPreviews(draftArgb, palette, pureBlack)
                    Box(Modifier.weight(1f)) {
                        HexColorField(
                            value = hexInput,
                            error = hexError,
                            onValueChange = { raw ->
                                hexInput = raw
                                parseStrictHexRgb(raw)?.let { draftArgb = it }
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(UiRoles.surface(UiSurfaceRole.Block))
                .padding(4.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ColorPickerTab.entries.forEach { tab ->
                val selected = pickerTab == tab
                Surface(
                    color = if (selected) UiRoles.surface(UiSurfaceRole.Card) else Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("appearance-color-tab-${tab.name}")
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { pickerTab = tab },
                        ),
                ) {
                    Box(
                        Modifier.padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tab.label,
                            style = UiRoles.text(MaterialTheme.typography.labelLarge),
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) UiRoles.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        when (pickerTab) {
            ColorPickerTab.Grid -> HctColorGrid(
                selectedArgb = draftArgb,
                onSelect = ::applyDraftFromPicker,
            )
            ColorPickerTab.Spectrum -> HslSpectrumPicker(
                selectedArgb = draftArgb,
                onInteracting = { pickerInteracting = it },
                onPick = ::applyDraftFromPicker,
            )
            ColorPickerTab.Sliders -> HsvChannelSliders(
                argb = draftArgb,
                onInteracting = { pickerInteracting = it },
                onChange = ::applyDraftFromPicker,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DualThemeColorMiniPreviews(
    seedArgb: Int,
    palette: AppearancePalette,
    pureBlack: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MiniThemeCard(seedArgb, palette, dark = false, pureBlack = false, label = "浅")
        MiniThemeCard(seedArgb, palette, dark = true, pureBlack = pureBlack, label = "深")
    }
}

@Composable
private fun MiniThemeCard(
    seedArgb: Int,
    palette: AppearancePalette,
    dark: Boolean,
    pureBlack: Boolean,
    label: String,
) {
    val generated = remember(seedArgb, palette, dark, pureBlack) {
        generateUiTheme(seedArgb, palette, dark, pureBlack)
    }
    val cs = generated.colorScheme
    Surface(
        modifier = Modifier
            .size(width = 72.dp, height = 96.dp)
            .border(1.dp, cs.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = cs.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(16.dp).clip(CircleShape).background(cs.primary))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (dark) cs.surfaceContainer else cs.surfaceBright,
                modifier = Modifier.fillMaxWidth().height(26.dp),
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(cs.primary))
                    Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(cs.primaryContainer))
                }
            }
            Box(
                Modifier.fillMaxWidth().height(16.dp).clip(CircleShape).background(cs.primary),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(20.dp).height(4.dp).clip(CircleShape).background(cs.onPrimary))
            }
        }
    }
}

@Composable
private fun HexColorField(
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            isError = error != null,
            label = { Text("Hex 色值") },
            placeholder = { Text("#007AFF") },
            modifier = Modifier.fillMaxWidth().testTag("appearance-color-hex-input"),
        )
        if (error != null) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .testTag("appearance-color-hex-error")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun HctColorGrid(
    selectedArgb: Int,
    onSelect: (Int) -> Unit,
) {
    val swatches = remember {
        val tones = listOf(92.0, 84.0, 76.0, 68.0, 60.0, 52.0, 44.0, 36.0, 28.0, 20.0)
        tones.map { tone ->
            (0 until 12).map { col ->
                val hue = col * 30.0
                hctColor(hue, 56.0, tone)
            }
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().testTag("appearance-color-grid"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        swatches.forEach { rowColors ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                rowColors.forEach { color ->
                    val argb = ((color.alpha * 255).roundToInt() shl 24) or
                        ((color.red * 255).roundToInt() shl 16) or
                        ((color.green * 255).roundToInt() shl 8) or
                        (color.blue * 255).roundToInt()
                    val selected = (argb and 0x00FFFFFF) == (selectedArgb and 0x00FFFFFF)
                    val hex = formatHexRgb(argb)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(color)
                            .then(
                                if (selected) Modifier.border(
                                    2.dp,
                                    if (color.luminance() > 0.55f) Color.Black else Color.White,
                                    RoundedCornerShape(6.dp),
                                ) else Modifier,
                            )
                            .clickable(role = Role.Button) { onSelect(argb) }
                            .semantics { contentDescription = hex },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Icon(
                                UiIcons.Check,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = if (color.luminance() > 0.55f) Color.Black else Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HslSpectrumPicker(
    selectedArgb: Int,
    onInteracting: (Boolean) -> Unit,
    onPick: (Int) -> Unit,
) {
    val (initHue, _, initVal) = remember(selectedArgb) { argbToHsv(selectedArgb) }
    val spectrumColors = remember {
        listOf(
            Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
            Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(6f / 5f)
            .clip(RoundedCornerShape(16.dp))
            .testTag("appearance-color-spectrum")
            .pointerInput(Unit) {
                fun emitAt(pos: Offset) {
                    val x = (pos.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val y = (pos.y / size.height.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    onPick(hslToArgb(hue = x * 360f, sat = 1f, lightness = 1f - y))
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onInteracting(true)
                    try {
                        emitAt(down.position)
                        down.consume()
                        drag(down.id) { change ->
                            emitAt(change.position)
                            change.consume()
                        }
                    } finally {
                        onInteracting(false)
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.horizontalGradient(spectrumColors))
            drawRect(
                Brush.verticalGradient(
                    0f to Color.White,
                    0.5f to Color.Transparent,
                    1f to Color.Black,
                ),
            )
            val markerX = (initHue / 360f).coerceIn(0f, 1f) * size.width
            val markerY = (1f - initVal.coerceIn(0f, 1f)) * size.height
            drawCircle(Color.White, radius = 10.dp.toPx(), center = Offset(markerX, markerY), style = Stroke(3.dp.toPx()))
            drawCircle(Color.Black, radius = 11.dp.toPx(), center = Offset(markerX, markerY), style = Stroke(1.dp.toPx()))
        }
    }
}

@Composable
private fun HsvChannelSliders(
    argb: Int,
    onInteracting: (Boolean) -> Unit,
    onChange: (Int) -> Unit,
) {
    val (h, s, v) = remember(argb) { argbToHsv(argb) }
    Column(
        modifier = Modifier.fillMaxWidth().testTag("appearance-color-sliders"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HsvTrackSlider(
            label = "色相",
            valueText = "${h.roundToInt()}°",
            fraction = (h / 360f).coerceIn(0f, 1f),
            colors = listOf(
                Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
                Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
            ),
            onInteracting = onInteracting,
            onFractionChange = { onChange(hsvToArgb(it * 360f, s, v)) },
            modifier = Modifier.testTag("appearance-hsv-hue"),
        )
        HsvTrackSlider(
            label = "饱和度",
            valueText = "${(s * 100f).roundToInt()}%",
            fraction = s.coerceIn(0f, 1f),
            colors = listOf(
                Color(hsvToArgb(h, 0f, v)),
                Color(hsvToArgb(h, 1f, v)),
            ),
            onInteracting = onInteracting,
            onFractionChange = { onChange(hsvToArgb(h, it, v)) },
            modifier = Modifier.testTag("appearance-hsv-saturation"),
        )
        HsvTrackSlider(
            label = "明度",
            valueText = "${(v * 100f).roundToInt()}%",
            fraction = v.coerceIn(0f, 1f),
            colors = listOf(
                Color(hsvToArgb(h, s, 0f)),
                Color(hsvToArgb(h, s, 1f)),
            ),
            onInteracting = onInteracting,
            onFractionChange = { onChange(hsvToArgb(h, s, it)) },
            modifier = Modifier.testTag("appearance-hsv-value"),
        )
    }
}

@Composable
private fun HsvTrackSlider(
    label: String,
    valueText: String,
    fraction: Float,
    colors: List<Color>,
    onInteracting: (Boolean) -> Unit,
    onFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = UiRoles.text(MaterialTheme.typography.bodySmall), color = TextSecondary)
            Text(valueText, style = UiRoles.text(MaterialTheme.typography.bodySmall), color = TextPrimary)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .pointerInput(Unit) {
                    fun updateAt(xPx: Float) {
                        val thumbPad = 14.dp.toPx()
                        val usable = (size.width - thumbPad * 2f).coerceAtLeast(1f)
                        val f = ((xPx - thumbPad) / usable).coerceIn(0f, 1f)
                        onFractionChange(f)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pressed = true
                        onInteracting(true)
                        try {
                            updateAt(down.position.x)
                            down.consume()
                            drag(down.id) { change ->
                                updateAt(change.position.x)
                                change.consume()
                            }
                        } finally {
                            pressed = false
                            onInteracting(false)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val trackHeight = 20.dp.toPx()
                val thumbRadius = (if (pressed) 16.dp else 14.dp).toPx()
                val trackTop = (size.height - trackHeight) / 2f
                drawRoundRect(
                    brush = Brush.horizontalGradient(colors),
                    topLeft = Offset(0f, trackTop),
                    size = Size(size.width, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f),
                )
                val usable = (size.width - thumbRadius * 2f).coerceAtLeast(1f)
                val cx = thumbRadius + usable * fraction.coerceIn(0f, 1f)
                val cy = size.height / 2f
                drawCircle(Color.White, radius = thumbRadius, center = Offset(cx, cy))
                drawCircle(Color.Black.copy(alpha = 0.25f), radius = thumbRadius, center = Offset(cx, cy), style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}
