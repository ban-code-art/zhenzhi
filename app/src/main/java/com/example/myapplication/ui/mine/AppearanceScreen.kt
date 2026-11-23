package com.example.myapplication.ui.mine

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected as semanticsSelected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.appearance.AppearanceDockMotion
import com.example.myapplication.data.appearance.AppearanceFont
import com.example.myapplication.data.appearance.AppearanceGlass
import com.example.myapplication.data.appearance.AppearanceMode
import com.example.myapplication.data.appearance.AppearanceNavigation
import com.example.myapplication.data.appearance.AppearancePalette
import com.example.myapplication.data.appearance.AppearancePreferences
import com.example.myapplication.data.appearance.AppearanceState
import com.example.myapplication.data.appearance.AppearanceStore
import com.example.myapplication.data.appearance.AppearanceTabMotion
import com.example.myapplication.data.appearance.DefaultAppearanceSeed
import com.example.myapplication.ui.components.AnchoredActionMenu
import com.example.myapplication.ui.components.AnchoredMenuChoice
import com.example.myapplication.ui.components.PopupAnchor
import com.example.myapplication.ui.components.PopupDrag
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiConfirmDialog
import com.example.myapplication.ui.components.UiGlassBackdrop
import com.example.myapplication.ui.components.UiGlassQualitySnapshot
import com.example.myapplication.ui.components.UiPane
import com.example.myapplication.ui.components.UiPaneRole
import com.example.myapplication.ui.components.rememberUiBackdrop
import com.example.myapplication.ui.navigation.BackDecision
import com.example.myapplication.ui.navigation.BackLayer
import com.example.myapplication.ui.navigation.BackPage
import com.example.myapplication.ui.navigation.BackState
import com.example.myapplication.ui.navigation.isBackImeVisible
import com.example.myapplication.ui.navigation.unifiedBackRequest
import com.example.myapplication.ui.theme.LocalAppearance
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import com.example.myapplication.ui.theme.discoverAvailableSystemFonts
import com.example.myapplication.ui.theme.resolveSystemSeedArgb
import kotlin.math.abs
import kotlin.math.roundToInt

private sealed interface ActiveAppearanceMenu {
    data class Palette(val anchor: PopupAnchor, val drag: PopupDrag?) : ActiveAppearanceMenu
    data class Swatch(
        val index: Int,
        val argb: Int,
        val anchor: PopupAnchor,
    ) : ActiveAppearanceMenu
}

private data class PendingColorCommit(
    val token: Long,
    val editIndex: Int?,
    val argb: Int,
)

@Composable
internal fun AppearanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context.applicationContext) { AppearanceStore.forApplication(context) }
    AppearanceScreen(
        onBack = onBack,
        state = LocalAppearance.current,
        onUpdate = store::update,
        onRetry = store::retry,
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun AppearanceScreen(
    onBack: () -> Unit,
    state: AppearanceState,
    onUpdate: ((AppearancePreferences) -> AppearancePreferences) -> Unit,
    onRetry: () -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = state.preferences
    val latestOnUpdate by rememberUpdatedState(onUpdate)

    var showFontPicker by rememberSaveable { mutableStateOf(false) }
    var colorSheetOpen by rememberSaveable { mutableStateOf(false) }
    var colorSheetEditIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var colorSheetInitialArgb by rememberSaveable { mutableStateOf(DefaultAppearanceSeed) }
    var colorSheetToken by rememberSaveable { mutableStateOf(0L) }
    var pendingCommit by remember { mutableStateOf<PendingColorCommit?>(null) }
    var deleteCandidate by rememberSaveable { mutableStateOf<Pair<Int, Int>?>(null) }
    var resetConfirmOpen by rememberSaveable { mutableStateOf(false) }
    var activeMenu by remember { mutableStateOf<ActiveAppearanceMenu?>(null) }
    var glassSnapshot by remember { mutableStateOf(UiGlassQualitySnapshot()) }
    var hostBounds by remember { mutableStateOf(Rect.Zero) }
    var pendingFontFocusRestore by remember { mutableStateOf(false) }
    var pendingResetFocusRestore by remember { mutableStateOf(false) }

    val fontEntryFocus = remember { FocusRequester() }
    val addColorFocus = remember { FocusRequester() }
    val paletteAnchor = remember { PopupAnchor() }
    val swatchAnchors = remember { mutableStateMapOf<Int, PopupAnchor>() }
    val overlayBackdrop = rememberUiBackdrop()
    val scrollState = rememberScrollState()

    LaunchedEffect(pendingCommit, colorSheetOpen) {
        val commit = pendingCommit ?: return@LaunchedEffect
        if (!colorSheetOpen) {
            pendingCommit = null
            latestOnUpdate { current ->
                if (commit.editIndex == null) applyAddCustomColor(current, commit.argb)
                else applyEditCustomColor(current, commit.editIndex, commit.argb)
            }
        }
    }

    LaunchedEffect(showFontPicker, pendingFontFocusRestore) {
        if (!showFontPicker && pendingFontFocusRestore) {
            pendingFontFocusRestore = false
            runCatching { fontEntryFocus.requestFocus() }
        }
    }

    LaunchedEffect(deleteCandidate, pendingResetFocusRestore) {
        if (deleteCandidate == null && pendingResetFocusRestore) {
            pendingResetFocusRestore = false
            val targetFocus = swatchAnchors[0]?.focus ?: addColorFocus
            runCatching { targetFocus.requestFocus() }
        }
    }

    if (showFontPicker) {
        val closeFontPicker: () -> Unit = {
            pendingFontFocusRestore = true
            showFontPicker = false
        }
        val fontBack = unifiedBackRequest(
            BackState(page = BackPage.CHILD, imeVisible = isBackImeVisible()),
        ) { decision ->
            if (decision == BackDecision.PopPage) closeFontPicker()
        }
        FontSelectionScreen(
            currentFont = prefs.font,
            currentCustomFont = prefs.customFont,
            onSelect = { font, customFont ->
                onUpdate { it.copy(font = font, customFont = customFont) }
            },
            onBack = fontBack,
        )
        return
    }

    val requestBack = unifiedBackRequest(
        BackState(
            page = BackPage.CHILD,
            imeVisible = isBackImeVisible(),
            topLayer = when {
                colorSheetOpen -> BackLayer.Overlay("appearance-color-sheet")
                deleteCandidate != null || resetConfirmOpen -> BackLayer.Overlay("appearance-confirm-dialog")
                activeMenu != null -> BackLayer.Overlay("appearance-menu")
                else -> null
            },
        ),
    ) { decision ->
        when (decision) {
            BackDecision.PopPage -> onBack()
            is BackDecision.CloseLayer -> when {
                deleteCandidate != null -> deleteCandidate = null
                resetConfirmOpen -> resetConfirmOpen = false
                activeMenu != null -> activeMenu = null
                colorSheetOpen -> colorSheetOpen = false
            }
            else -> Unit
        }
    }

    val systemSeed = remember(context.applicationContext) {
        resolveSystemSeedArgb(context)
    }
    val systemFontLabel = remember(prefs.font, prefs.customFont) {
        fontDisplayLabel(prefs.font, prefs.customFont)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { hostBounds = it.boundsInRoot() },
    ) {
        UiGlassBackdrop(overlayBackdrop, Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                SubPageScaffold(
                    title = "外观与界面",
                    onBack = requestBack,
                    glassBack = true,
                    actionId = "appearance.back",
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .imePadding()
                            .verticalScroll(scrollState)
                            .padding(start = 16.dp, top = GlassPageBarHeight + 8.dp, end = 16.dp, bottom = 32.dp)
                            .testTag("appearance-scroll"),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        state.error?.let { error ->
                            AppearanceStatusBanner(
                                error = error,
                                onRetry = onRetry,
                            )
                        }

                        AppearanceSection("预览") {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                ThemeLivePreview(preferences = prefs)
                            }
                        }

                        AppearanceSection("主题模式") {
                            Column(Modifier.fillMaxWidth().selectableGroup()) {
                                val options = listOf(
                                    Triple(AppearanceMode.System, "自动", "mode-System"),
                                    Triple(AppearanceMode.Light, "浅色", "mode-Light"),
                                    Triple(AppearanceMode.Dark, "深色", "mode-Dark"),
                                )
                                options.forEachIndexed { idx, (mode, label, thumbKind) ->
                                    if (idx > 0) AppearanceDivider()
                                    AppearanceChoiceRow(
                                        label = label,
                                        selected = prefs.mode == mode,
                                        onSelect = {
                                            if (prefs.mode != mode) onUpdate { it.copy(mode = mode) }
                                        },
                                        thumbnailKind = thumbKind,
                                        modifier = Modifier.testTag("appearance-mode-${mode.name}"),
                                    )
                                }
                            }
                        }

                        AppearanceSection("纯黑模式") {
                            Column(Modifier.fillMaxWidth().selectableGroup()) {
                                AppearanceChoiceRow(
                                    label = "标准",
                                    selected = !prefs.pureBlack,
                                    onSelect = {
                                        if (prefs.pureBlack) onUpdate { it.copy(pureBlack = false) }
                                    },
                                    thumbnailKind = "black-false",
                                    modifier = Modifier.testTag("appearance-pure-black-false"),
                                )
                                AppearanceDivider()
                                AppearanceChoiceRow(
                                    label = "纯黑",
                                    selected = prefs.pureBlack,
                                    onSelect = {
                                        if (!prefs.pureBlack) onUpdate { it.copy(pureBlack = true) }
                                    },
                                    thumbnailKind = "black-true",
                                    modifier = Modifier.testTag("appearance-pure-black-true"),
                                )
                            }
                        }

                        AppearanceSection("主题色彩") {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth().testTag("appearance-color-grid-palette"),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    ColorSwatchCell(
                                        color = Color(systemSeed),
                                        selected = prefs.seed == null,
                                        description = if (prefs.seed == null) "系统色，已选" else "系统色",
                                        badgeIcon = UiIcons.Eyedropper,
                                        onClick = {
                                            if (prefs.seed != null) onUpdate { it.copy(seed = null) }
                                        },
                                        modifier = Modifier.testTag("appearance-color-system"),
                                    )

                                    prefs.customColors.forEachIndexed { index, argb ->
                                        val swatchAnchor = remember(index) {
                                            swatchAnchors.getOrPut(index) { PopupAnchor() }
                                        }
                                        val selected = prefs.seed == argb
                                        val hex = formatHexRgb(argb)
                                        val desc = if (selected) "$hex，已选" else hex
                                        val openSwatchMenu: () -> Unit = {
                                            activeMenu = ActiveAppearanceMenu.Swatch(index, argb, swatchAnchor)
                                        }
                                        ColorSwatchCell(
                                            color = Color(argb),
                                            selected = selected,
                                            description = desc,
                                            badgeIcon = null,
                                            onClick = {
                                                if (prefs.seed != argb) onUpdate { it.copy(seed = argb) }
                                            },
                                            onLongClick = openSwatchMenu,
                                            customA11yActions = listOf(
                                                CustomAccessibilityAction("编辑") {
                                                    colorSheetEditIndex = index
                                                    colorSheetInitialArgb = argb
                                                    colorSheetToken++
                                                    colorSheetOpen = true
                                                    true
                                                },
                                                CustomAccessibilityAction("删除") {
                                                    deleteCandidate = index to argb
                                                    true
                                                },
                                            ),
                                            modifier = Modifier
                                                .focusRequester(swatchAnchor.focus)
                                                .then(swatchAnchor.visualModifier)
                                                .then(swatchAnchor.foregroundModifier())
                                                .testTag("appearance-color-swatch-$index"),
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .focusRequester(addColorFocus)
                                            .clickable(role = Role.Button) {
                                                colorSheetEditIndex = null
                                                colorSheetInitialArgb = prefs.seed ?: systemSeed
                                                colorSheetToken++
                                                colorSheetOpen = true
                                            }
                                            .semantics { contentDescription = "添加自定义颜色" }
                                            .testTag("appearance-color-add"),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Box(
                                            Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(UiRoles.surface(UiSurfaceRole.Block)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                UiIcons.Add,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                                tint = TextPrimary,
                                            )
                                        }
                                    }
                                }

                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    UiAction(
                                        label = "重置颜色",
                                        onClick = { resetConfirmOpen = true },
                                        enabled = hasResettableColorChanges(prefs),
                                        foreground = UiRoles.extended.destructive,
                                        inheritedPane = true,
                                        animatePress = false,
                                        actionId = "appearance.color.reset",
                                        modifier = Modifier.testTag("appearance-color-reset"),
                                    )
                                }

                                HorizontalDivider(thickness = 0.5.dp, color = HairlineDivider)

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .focusRequester(paletteAnchor.focus)
                                        .then(paletteAnchor.visualModifier)
                                        .then(paletteAnchor.foregroundModifier())
                                        .then(
                                            paletteAnchor.inputModifier { drag ->
                                                activeMenu = ActiveAppearanceMenu.Palette(paletteAnchor, drag)
                                                true
                                            },
                                        )
                                        .clickable(role = Role.DropdownList) {
                                            activeMenu = ActiveAppearanceMenu.Palette(paletteAnchor, null)
                                        }
                                        .padding(horizontal = 4.dp, vertical = 8.dp)
                                        .testTag("appearance-palette-trigger"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        "配色风格",
                                        style = UiRoles.text(UiRoles.rowText),
                                        color = TextPrimary,
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            paletteDisplayLabel(prefs.palette),
                                            style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                                            color = TextSecondary,
                                        )
                                        Icon(
                                            UiIcons.ChevronDown,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = TextSecondary,
                                        )
                                    }
                                }
                            }
                        }

                        AppearanceSection("底栏") {
                            Column(Modifier.fillMaxWidth().selectableGroup()) {
                                AppearanceChoiceRow(
                                    label = "悬浮",
                                    selected = prefs.navigation == AppearanceNavigation.Floating,
                                    onSelect = {
                                        if (prefs.navigation != AppearanceNavigation.Floating) {
                                            onUpdate { it.copy(navigation = AppearanceNavigation.Floating) }
                                        }
                                    },
                                    thumbnailKind = "nav-Floating",
                                    modifier = Modifier.testTag("appearance-nav-Floating"),
                                )
                                AppearanceDivider()
                                AppearanceChoiceRow(
                                    label = "固定",
                                    selected = prefs.navigation == AppearanceNavigation.Fixed,
                                    onSelect = {
                                        if (prefs.navigation != AppearanceNavigation.Fixed) {
                                            onUpdate { it.copy(navigation = AppearanceNavigation.Fixed) }
                                        }
                                    },
                                    thumbnailKind = "nav-Fixed",
                                    modifier = Modifier.testTag("appearance-nav-Fixed"),
                                )
                            }
                        }

                        AppearanceSection("底栏动效") {
                            Column(Modifier.fillMaxWidth().selectableGroup()) {
                                AppearanceChoiceRow(
                                    label = "平滑滑动",
                                    selected = prefs.dockMotion == AppearanceDockMotion.Slide,
                                    onSelect = {
                                        if (prefs.dockMotion != AppearanceDockMotion.Slide) {
                                            onUpdate { it.copy(dockMotion = AppearanceDockMotion.Slide) }
                                        }
                                    },
                                    modifier = Modifier.testTag("appearance-dock-motion-Slide"),
                                )
                                AppearanceDivider()
                                AppearanceChoiceRow(
                                    label = "淡入淡出",
                                    selected = prefs.dockMotion == AppearanceDockMotion.Fade,
                                    onSelect = {
                                        if (prefs.dockMotion != AppearanceDockMotion.Fade) {
                                            onUpdate { it.copy(dockMotion = AppearanceDockMotion.Fade) }
                                        }
                                    },
                                    modifier = Modifier.testTag("appearance-dock-motion-Fade"),
                                )
                            }
                        }

                        AppearanceSection("选项卡动画") {
                            Column(Modifier.fillMaxWidth().selectableGroup()) {
                                AppearanceChoiceRow(
                                    label = "滑动",
                                    selected = prefs.tabMotion == AppearanceTabMotion.Slide,
                                    onSelect = {
                                        if (prefs.tabMotion != AppearanceTabMotion.Slide) {
                                            onUpdate { it.copy(tabMotion = AppearanceTabMotion.Slide) }
                                        }
                                    },
                                    modifier = Modifier.testTag("appearance-tab-motion-Slide"),
                                )
                                AppearanceDivider()
                                AppearanceChoiceRow(
                                    label = "淡入",
                                    selected = prefs.tabMotion == AppearanceTabMotion.Fade,
                                    onSelect = {
                                        if (prefs.tabMotion != AppearanceTabMotion.Fade) {
                                            onUpdate { it.copy(tabMotion = AppearanceTabMotion.Fade) }
                                        }
                                    },
                                    modifier = Modifier.testTag("appearance-tab-motion-Fade"),
                                )
                            }
                        }

                        AppearanceSection("液态玻璃") {
                            Column(Modifier.fillMaxWidth().selectableGroup()) {
                                val glassOptions = listOf(
                                    AppearanceGlass.None to "无",
                                    AppearanceGlass.Standard to "标准",
                                    AppearanceGlass.Ultra to "极致",
                                )
                                glassOptions.forEachIndexed { idx, (glass, label) ->
                                    if (idx > 0) AppearanceDivider()
                                    AppearanceChoiceRow(
                                        label = label,
                                        selected = prefs.glass == glass,
                                        onSelect = {
                                            if (prefs.glass != glass) onUpdate { it.copy(glass = glass) }
                                        },
                                        modifier = Modifier.testTag("appearance-glass-${glass.name}"),
                                    )
                                }
                                HorizontalDivider(thickness = 0.5.dp, color = HairlineDivider)
                                val sampleBackdrop = rememberUiBackdrop()
                                Box(Modifier.fillMaxWidth().padding(12.dp)) {
                                    UiGlassBackdrop(
                                        backdrop = sampleBackdrop,
                                        modifier = Modifier.matchParentSize(),
                                    ) {
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)),
                                        )
                                    }
                                    UiPane(
                                        role = UiPaneRole.Regular,
                                        shape = RoundedCornerShape(16.dp),
                                        backdrop = sampleBackdrop,
                                        onQualityChanged = { glassSnapshot = it },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            text = describeWindowGlassSnapshot(glassSnapshot),
                                            style = UiRoles.text(MaterialTheme.typography.bodySmall),
                                            color = TextSecondary,
                                            modifier = Modifier
                                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                                .testTag("appearance-glass-status"),
                                        )
                                    }
                                }
                            }
                        }

                        AppearanceSection("字体") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .focusRequester(fontEntryFocus)
                                    .clickable(role = Role.Button) { showFontPicker = true }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                                    .testTag("appearance-font-entry"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = "Aa",
                                    style = UiRoles.text(MaterialTheme.typography.titleMedium),
                                    fontWeight = FontWeight.Bold,
                                    color = UiRoles.accent,
                                )
                                Text(
                                    text = systemFontLabel,
                                    style = UiRoles.text(UiRoles.rowText),
                                    color = TextPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    UiIcons.Chevron,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = TextSecondary,
                                )
                            }
                        }

                        AppearanceSection("文本缩放") {
                            val currentScale = prefs.textScale ?: LocalDensity.current.fontScale
                            val isCustom = prefs.textScale != null
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(UiRoles.surface(UiSurfaceRole.Block))
                                        .padding(4.dp)
                                        .selectableGroup(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    ScaleSegmentButton(
                                        label = "跟随系统",
                                        selected = !isCustom,
                                        onClick = {
                                            if (prefs.textScale != null) {
                                                onUpdate { it.copy(textScale = null) }
                                            }
                                        },
                                        modifier = Modifier.weight(1f).testTag("appearance-scale-system"),
                                    )
                                    ScaleSegmentButton(
                                        label = "自定义",
                                        selected = isCustom,
                                        onClick = {
                                            if (prefs.textScale == null) {
                                                onUpdate { it.copy(textScale = it.lastCustomTextScale) }
                                            }
                                        },
                                        modifier = Modifier.weight(1f).testTag("appearance-scale-custom"),
                                    )
                                }

                                Surface(
                                    color = UiRoles.surface(UiSurfaceRole.Block),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            text = "Aa 示例字体大小",
                                            style = UiRoles.text(MaterialTheme.typography.bodyLarge).copy(
                                                fontSize = (16f * currentScale).sp,
                                                lineHeight = (22f * currentScale).sp,
                                            ),
                                            color = TextPrimary,
                                        )
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = CircleShape,
                                            modifier = Modifier
                                                .semantics(mergeDescendants = true) {}
                                                .testTag("appearance-scale-percent"),
                                        ) {
                                            Text(
                                                text = "${(currentScale * 100f).roundToInt()}%",
                                                style = UiRoles.text(MaterialTheme.typography.labelMedium),
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            )
                                        }
                                    }
                                }

                                AppearanceScaleSlider(
                                    value = prefs.textScale ?: prefs.lastCustomTextScale,
                                    enabled = isCustom,
                                    onCommitScale = { committed ->
                                        onUpdate {
                                            if (it.textScale == null) it
                                            else it.copy(textScale = committed, lastCustomTextScale = committed)
                                        }
                                    },
                                    modifier = Modifier.testTag("appearance-scale-slider"),
                                )

                                if (isCustom && abs((prefs.textScale ?: 1f) - 1f) > 0.001f) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        UiAction(
                                            label = "重置",
                                            onClick = {
                                                onUpdate {
                                                    it.copy(textScale = 1f, lastCustomTextScale = 1f)
                                                }
                                            },
                                            inheritedPane = true,
                                            actionId = "appearance.scale.reset",
                                            modifier = Modifier.testTag("appearance-scale-reset"),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        when (val menu = activeMenu) {
            is ActiveAppearanceMenu.Palette -> {
                val anchorRect = menu.anchor.bounds?.translate(-hostBounds.topLeft)
                val openFrom = anchorRect ?: Rect(0f, 0f, 100f, 48f)
                val choices = remember {
                    AppearancePalette.entries.map {
                        AnchoredMenuChoice(
                            value = it.name,
                            label = paletteDisplayLabel(it),
                            testTag = "appearance-palette-option-${it.name}",
                        )
                    }
                }
                AnchoredActionMenu(
                    anchor = anchorRect,
                    openFrom = openFrom,
                    choices = choices,
                    selected = prefs.palette.name,
                    paneTitleText = "配色风格",
                    onChoose = { value ->
                        val picked = AppearancePalette.entries.firstOrNull { it.name == value } ?: return@AnchoredActionMenu
                        if (prefs.palette != picked) onUpdate { it.copy(palette = picked) }
                    },
                    onClosed = {
                        activeMenu = null
                        runCatching { menu.anchor.focus.requestFocus() }
                    },
                    backdrop = overlayBackdrop,
                    tagPrefix = "appearance-palette-menu",
                    sourceAnchor = menu.anchor,
                    drag = menu.drag,
                )
            }
            is ActiveAppearanceMenu.Swatch -> {
                val anchorRect = menu.anchor.bounds?.translate(-hostBounds.topLeft)
                val openFrom = anchorRect ?: Rect(0f, 0f, 48f, 48f)
                val choices = remember {
                    listOf(
                        AnchoredMenuChoice("edit", "编辑", icon = UiIcons.Edit, testTag = "appearance-swatch-edit"),
                        AnchoredMenuChoice("delete", "删除", icon = UiIcons.Trash, destructive = true, testTag = "appearance-swatch-delete"),
                    )
                }
                AnchoredActionMenu(
                    anchor = anchorRect,
                    openFrom = openFrom,
                    choices = choices,
                    paneTitleText = formatHexRgb(menu.argb),
                    onChoose = { action ->
                        when (action) {
                            "edit" -> {
                                colorSheetEditIndex = menu.index
                                colorSheetInitialArgb = menu.argb
                                colorSheetToken++
                                colorSheetOpen = true
                            }
                            "delete" -> deleteCandidate = menu.index to menu.argb
                        }
                    },
                    onClosed = {
                        activeMenu = null
                        runCatching { menu.anchor.focus.requestFocus() }
                    },
                    backdrop = overlayBackdrop,
                    tagPrefix = "appearance-swatch-menu",
                    sourceAnchor = menu.anchor,
                )
            }
            null -> Unit
        }
    }

    if (colorSheetOpen) {
        CustomColorSheet(
            initialArgb = colorSheetInitialArgb,
            palette = prefs.palette,
            pureBlack = prefs.pureBlack,
            ownerKey = "color-sheet:$colorSheetToken",
            onDismiss = { colorSheetOpen = false },
            onConfirm = { pickedArgb ->
                pendingCommit = PendingColorCommit(
                    token = colorSheetToken,
                    editIndex = colorSheetEditIndex,
                    argb = pickedArgb,
                )
                colorSheetOpen = false
            },
        )
    }

    deleteCandidate?.let { (index, argb) ->
        UiConfirmDialog(
            title = "删除颜色",
            message = "确定删除 ${formatHexRgb(argb)} 吗？",
            confirmLabel = "删除",
            dismissLabel = "取消",
            destructive = true,
            actionId = "appearance.color.delete",
            ownerKey = "delete:$index:$argb",
            onConfirm = {
                pendingResetFocusRestore = true
                deleteCandidate = null
                onUpdate { applyDeleteCustomColor(it, index) }
            },
            onDismiss = {
                val restoreTarget = swatchAnchors[index]
                deleteCandidate = null
                if (restoreTarget != null) runCatching { restoreTarget.focus.requestFocus() }
            },
            modifier = Modifier.testTag("appearance-delete-color-dialog"),
        )
    }

    if (resetConfirmOpen) {
        UiConfirmDialog(
            title = "重置颜色",
            message = "将颜色列表、主题色与配色风格恢复为默认设置，保留字体、液态玻璃与导航设置。",
            confirmLabel = "重置",
            dismissLabel = "取消",
            destructive = true,
            actionId = "appearance.color.resetConfirm",
            ownerKey = "reset-colors",
            onConfirm = {
                resetConfirmOpen = false
                onUpdate(::applyResetAppearanceColors)
            },
            onDismiss = { resetConfirmOpen = false },
            modifier = Modifier.testTag("appearance-reset-color-dialog"),
        )
    }
}

@Composable
private fun AppearanceStatusBanner(
    error: String,
    onRetry: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.errorContainer,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("appearance-save-banner")
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = error,
                style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                color = cs.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            UiAction(
                label = "重试",
                onClick = onRetry,
                actionId = "appearance.retry",
                inheritedPane = true,
                foreground = cs.onErrorContainer,
                modifier = Modifier.testTag("appearance-save-retry"),
            )
        }
    }
}

@Composable
private fun AppearanceSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = UiRoles.text(MaterialTheme.typography.titleSmall),
            fontWeight = FontWeight.Bold,
            color = UiRoles.pageForeground,
            modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
        )
        Surface(
            color = UiRoles.surface(UiSurfaceRole.Card),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun AppearanceDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp, end = 14.dp),
        thickness = 0.5.dp,
        color = HairlineDivider,
    )
}

@Composable
private fun AppearanceChoiceRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailKind: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (thumbnailKind != null) {
            AppearanceOptionThumbnail(thumbnailKind, selected)
        }
        Text(
            text = label,
            style = UiRoles.text(UiRoles.rowText),
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                UiIcons.Check,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = UiRoles.accent,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColorSwatchCell(
    color: Color,
    selected: Boolean,
    description: String,
    badgeIcon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    customA11yActions: List<CustomAccessibilityAction> = emptyList(),
) {
    val cs = MaterialTheme.colorScheme
    val checkColor = if (color.luminance() > 0.55f) Color.Black else Color.White
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .combinedClickable(
                role = Role.RadioButton,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics {
                this.semanticsSelected = selected
                contentDescription = description
                if (customA11yActions.isNotEmpty()) {
                    customActions = customA11yActions
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .then(
                    if (selected) Modifier.border(2.5.dp, cs.primary, CircleShape)
                    else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(0.75.dp, cs.outlineVariant.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    selected -> Icon(
                        UiIcons.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = checkColor,
                    )
                    badgeIcon != null -> Icon(
                        badgeIcon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = checkColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScaleSegmentButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = if (selected) UiRoles.surface(UiSurfaceRole.Card) else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.selectable(selected = selected, role = Role.Tab, onClick = onClick),
    ) {
        Box(
            Modifier.heightIn(min = 36.dp).padding(horizontal = 8.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = UiRoles.text(MaterialTheme.typography.labelLarge),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) UiRoles.accent else TextSecondary,
            )
        }
    }
}

internal fun paletteDisplayLabel(palette: AppearancePalette): String = when (palette) {
    AppearancePalette.Vivid -> "鲜明"
    AppearancePalette.Tinted -> "氛围"
    AppearancePalette.Tonal -> "柔和"
    AppearancePalette.Monochrome -> "单色"
}

private fun fontDisplayLabel(font: AppearanceFont, customFont: String?): String {
    if (!customFont.isNullOrBlank()) {
        val discovered = discoverAvailableSystemFonts().firstOrNull { it.id == customFont }
        return discovered?.displayName ?: customFont.substringAfterLast('/')
    }
    return when (font) {
        AppearanceFont.System -> "默认"
        AppearanceFont.Sans -> "无衬线"
        AppearanceFont.Serif -> "衬线"
        AppearanceFont.Monospace -> "等宽"
    }
}
