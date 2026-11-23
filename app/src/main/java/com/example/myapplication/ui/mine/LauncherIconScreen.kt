package com.example.myapplication.ui.mine

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.theme.UiIcons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.myapplication.ui.navigation.unifiedPageBack
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LauncherIconScreen(onBack: () -> Unit) {
    val hostContext = LocalContext.current
    val context = hostContext.applicationContext
    val activity = remember(hostContext) { hostContext.findActivity() }
    val controller = remember(context) { LauncherIconController(context) }
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<LauncherIconSelection?>(null) }
    var applying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun updateIcon(target: LauncherIconSelection? = null) {
        error = null
        try {
            val (resolved, taskBitmap) = withContext(Dispatchers.IO) {
                val selected = if (target != null) controller.switchTo(target) else controller.current()
                selected to prepareLauncherTaskBitmap(context, selected)
            }
            current = resolved
            activity?.let { applyLauncherTaskAppearance(it, taskBitmap) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = if (target == null) "无法读取当前应用图标，请重试。" else "切换应用图标失败，请重试。"
        } finally {
            applying = false
        }
    }

    fun selectIcon(selection: LauncherIconSelection) {
        if (!applying && current != null && current != selection) {
            applying = true
            scope.launch { updateIcon(selection) }
        }
    }

    LaunchedEffect(controller) {
        applying = true
        updateIcon()
    }

    val requestBack = unifiedPageBack(onBack)
    val usingDefault = current == LauncherIconSelection.DEFAULT
    Box(Modifier.fillMaxSize().systemBarsPadding().testTag("launcher-icon-screen")) {
        SubPageScaffold(
            title = "应用图标",
            onBack = requestBack,
            glassBack = true,
            actionId = "launcher-icon.back",
            bottomOverlay = { backdrop ->
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    UiAction(
                        label = if (usingDefault) "恢复默认图标（使用中）" else "恢复默认图标",
                        onClick = { selectIcon(LauncherIconSelection.DEFAULT) },
                        enabled = !applying && current != null && !usingDefault,
                        icon = null,
                        backdrop = backdrop,
                        actionId = "launcherIcon.restoreDefault",
                        modifier = Modifier.testTag("launcher-icon-restore-default"),
                    )
                }
            },
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, top = GlassPageBarHeight + 8.dp, end = 16.dp, bottom = 92.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "点击图标即可切换，桌面更新可能需要片刻。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("launcher-icon-error")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    if (current == null) {
                        UiAction(
                            label = "重试",
                            onClick = {
                                applying = true
                                scope.launch { updateIcon() }
                            },
                            enabled = !applying,
                            actionId = "launcherIcon.retry",
                        )
                    }
                }
                Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LauncherIcon.entries.forEach { icon ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                icon.label,
                                style = UiRoles.text(MaterialTheme.typography.titleSmall),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(start = 4.dp).semantics { heading() },
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                for (shape in listOf(LauncherIconShape.NORMAL, LauncherIconShape.ROUND)) {
                                    val choice = LauncherIconSelection(icon, shape)
                                    LauncherIconChoice(
                                        choice = choice,
                                        selected = current == choice,
                                        enabled = !applying && current != null,
                                        onClick = { selectIcon(choice) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LauncherIconChoice(
    choice: LauncherIconSelection,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        color = if (selected) colors.secondaryContainer else UiRoles.surface(UiSurfaceRole.Card),
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .testTag("launcher-icon-option-" + choice.icon.name.lowercase() + "-" + choice.shape.name.lowercase())
                .semantics { contentDescription = choice.icon.label + "，" + choice.shape.label }
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LauncherIconPreview(
                resourceId = if (choice.shape == LauncherIconShape.ROUND) choice.icon.roundIconRes else choice.icon.iconRes,
                round = choice.shape == LauncherIconShape.ROUND,
            )
            Text(choice.shape.label, style = UiRoles.text(MaterialTheme.typography.titleSmall))
            Box(Modifier.heightIn(min = 18.dp), contentAlignment = Alignment.Center) {
                if (selected) {
                    Text("使用中", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun LauncherIconPreview(@DrawableRes resourceId: Int, round: Boolean) {
    val context = LocalContext.current
    val image = key(resourceId) {
        val preview by produceState<ImageBitmap?>(null, context, resourceId) {
            value = withContext(Dispatchers.IO) {
                ContextCompat.getDrawable(context, resourceId)
                    ?.let { rasterizeLauncherIconBitmap(it).asImageBitmap() }
            }
        }
        preview
    }
    val shape = if (round) CircleShape else RoundedCornerShape(13.dp)
    Box(Modifier.size(56.dp).clip(shape), contentAlignment = Alignment.Center) {
        image?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}
