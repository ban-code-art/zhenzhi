package com.example.myapplication.ui.mine

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.update.UpdateCheckCoordinator
import com.example.myapplication.data.update.UpdateCheckState
import com.example.myapplication.data.update.UpdateInstallState
import com.example.myapplication.data.update.UpdateInstaller
import com.example.myapplication.data.update.isUpdateInProgress
import com.example.myapplication.ui.components.SettingItem
import com.example.myapplication.ui.components.UiSettingsSwitch
import com.example.myapplication.ui.navigation.*
import com.example.myapplication.ui.mine.datamanagement.AppDataManagementRoute
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

/** Retains the existing public entry point and its real state owner. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigate: (MineDestination) -> Unit,
    onLogout: () -> Unit,
) {
    val enhancement = rememberEnhancementSettingsViewModel()
    val state by enhancement.state.collectAsState()
    var showDataManagement by rememberSaveable { mutableStateOf(false) }
    if (showDataManagement) {
        AppDataManagementRoute(onBack = { showDataManagement = false })
        return
    }
    SettingsScreen(onBack, onNavigate, onLogout, state,
        enhancement::setWebSearchEnabled, enhancement::setAiReviewEnabled,
        onOpenDataManagement = { showDataManagement = true })
}

/** MainScreen and both standalone entries use the same ViewModelStoreOwner. */
@Composable
internal fun rememberEnhancementSettingsViewModel(): EnhancementSettingsViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(context) { EnhancementSettingsViewModelFactory(context) }
    return viewModel(factory = factory)
}

/** The production renderer also accepts a controlled state for UI interaction tests. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigate: (MineDestination) -> Unit,
    onLogout: () -> Unit,
    state: EnhancementSettingsUiState,
    onWebSearchEnabledChange: (Boolean) -> Unit,
    onAiReviewEnabledChange: (Boolean) -> Unit,
    onOpenDataManagement: () -> Unit = {},
) {
    val context = LocalContext.current
    val container = remember(context.applicationContext) {
        (context.applicationContext as? LearningApplication)?.container
    }
    SettingsScreenWithUpdate(
        onBack = onBack,
        onNavigate = onNavigate,
        onLogout = onLogout,
        state = state,
        onWebSearchEnabledChange = onWebSearchEnabledChange,
        onAiReviewEnabledChange = onAiReviewEnabledChange,
        onOpenDataManagement = onOpenDataManagement,
        updateCheck = container?.updateCheck,
        installer = container?.updateInstaller,
    )
}

@Composable
internal fun SettingsScreenWithUpdate(
    onBack: () -> Unit,
    onNavigate: (MineDestination) -> Unit,
    onLogout: () -> Unit,
    state: EnhancementSettingsUiState,
    onWebSearchEnabledChange: (Boolean) -> Unit,
    onAiReviewEnabledChange: (Boolean) -> Unit,
    onOpenDataManagement: () -> Unit = {},
    updateCheck: UpdateCheckCoordinator? = null,
    installer: UpdateInstaller? = null,
) {
    val context = LocalContext.current
    val aboutIdentity = remember(context.applicationContext) { resolveAppAboutIdentity(context) }
    var showLauncherIcons by rememberSaveable { mutableStateOf(false) }
    var showUpdateModal by rememberSaveable { mutableStateOf(false) }
    val checkState by (updateCheck?.state?.collectAsState()
        ?: remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) })
    val installState by (installer?.state?.collectAsState()
        ?: remember { mutableStateOf<UpdateInstallState>(UpdateInstallState.Idle) })
    val updateRowBadge = when (val inst = installState) {
        is UpdateInstallState.Downloading -> {
            val pct = if (inst.total > 0L) ((inst.bytes * 100L) / inst.total).toInt().coerceIn(0, 100) else 0
            "$pct%"
        }
        is UpdateInstallState.Verifying -> "校验中"
        is UpdateInstallState.Installing, is UpdateInstallState.Opening -> "安装中"
        is UpdateInstallState.PendingConfirmation, is UpdateInstallState.Ready -> "待安装"
        else -> if (checkState is UpdateCheckState.Running) "检查中" else null
    }
    if (showUpdateModal && updateCheck != null) {
        SettingsUpdateModalHost(
            visible = true,
            onDismiss = { showUpdateModal = false },
            updateCheck = updateCheck,
            installer = installer,
            currentVersionText = aboutIdentity.versionPillText,
        )
    }
    UiPageStack(if (showLauncherIcons) listOf("launcher-icons") else emptyList()) { visualStack ->
        if (visualStack.isEmpty()) {
            SettingsContent(
                onBack = onBack,
                onNavigate = onNavigate,
                onLogout = onLogout,
                state = state,
                onWebSearchEnabledChange = onWebSearchEnabledChange,
                onAiReviewEnabledChange = onAiReviewEnabledChange,
                onOpenDataManagement = onOpenDataManagement,
                onOpenLauncherIcons = { showLauncherIcons = true },
                updateCheck = updateCheck,
                onCheckUpdates = {
                    if (!installState.isUpdateInProgress) {
                        updateCheck?.request()
                    }
                    showUpdateModal = true
                },
                updateRowBadge = updateRowBadge,
            )
        } else {
            LauncherIconScreen(onBack = { showLauncherIcons = false })
        }
    }
}

@Composable
private fun SettingsContent(
    onBack: () -> Unit,
    onNavigate: (MineDestination) -> Unit,
    onLogout: () -> Unit,
    state: EnhancementSettingsUiState,
    onWebSearchEnabledChange: (Boolean) -> Unit,
    onAiReviewEnabledChange: (Boolean) -> Unit,
    onOpenDataManagement: () -> Unit,
    onOpenLauncherIcons: () -> Unit,
    updateCheck: UpdateCheckCoordinator? = null,
    onCheckUpdates: () -> Unit = {},
    updateRowBadge: String? = null,
) {
    val requestBack = unifiedBackRequest(
        BackState(page = BackPage.CHILD, imeVisible = isBackImeVisible()),
    ) { decision ->
        if (decision == BackDecision.PopPage) onBack()
    }

    // Mine subpages render outside the home Scaffold and own their insets.
    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        SubPageScaffold(title = "设置", onBack = requestBack, glassBack = true) {
            Column(
                modifier = Modifier.fillMaxSize().imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, top = GlassPageBarHeight + 8.dp, end = 16.dp, bottom = 32.dp)
                    .testTag("settings-scroll"),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SettingsGroup("阅读与学习", "settings-reading-group") {
                    SettingItem("外观与界面", { onNavigate(MineDestination.Appearance) },
                        icon = UiIcons.Palette,
                        modifier = Modifier.testTag("settings-appearance-entry"))
                    SettingsDivider()
                    SettingItem("应用图标", onOpenLauncherIcons,
                        icon = UiIcons.Palette,
                        modifier = Modifier.testTag("settings-app-icon-entry"))
                    SettingsDivider()
                    SettingItem("学习进度", { onNavigate(MineDestination.StudyProgress) },
                        icon = UiIcons.Progress,
                        modifier = Modifier.testTag("settings-study-progress"))
                }
                SettingsGroup("AI 与知识库", "settings-ai-group") {
                    SettingItem("AI 配置", { onNavigate(MineDestination.AiConfig) },
                        icon = UiIcons.Ai,
                        modifier = Modifier.testTag("settings-ai-config"))
                    SettingsDivider()
                    SettingItem("知识库设置", { onNavigate(MineDestination.KnowledgeSettings) },
                        icon = UiIcons.Database,
                        modifier = Modifier.testTag("settings-knowledge-entry"))
                    SettingsDivider()
                    SettingItem("知识库统计", { onNavigate(MineDestination.KnowledgeStats) },
                        icon = UiIcons.Records,
                        modifier = Modifier.testTag("settings-knowledge-stats"))
                    SettingsDivider()
                    UiSettingsSwitch(
                        title = "联网检索",
                        checked = state.webSearchEnabled,
                        status = state.webSearchToggleStatus,
                        onCheckedChange = onWebSearchEnabledChange,
                        icon = UiIcons.Search,
                        modifier = Modifier.testTag("settings-web-search-switch"),
                    )
                    SettingsDivider()
                    UiSettingsSwitch(
                        title = "AI 简答复核",
                        checked = state.aiReviewEnabled,
                        status = state.aiReviewToggleStatus,
                        onCheckedChange = onAiReviewEnabledChange,
                        icon = UiIcons.Check,
                        modifier = Modifier.testTag("settings-ai-review-switch"),
                    )
                }
                SettingsGroup("数据与账号", "settings-account-group") {
                    SettingItem("导出与恢复", onOpenDataManagement,
                        icon = UiIcons.Export,
                        modifier = Modifier.testTag("settings-data-management"))
                    SettingsDivider()
                    SettingItem("个人信息", { onNavigate(MineDestination.EditProfile) },
                        icon = UiIcons.Person,
                        modifier = Modifier.testTag("settings-edit-profile"))
                    SettingsDivider()
                    SettingItem("修改密码", { onNavigate(MineDestination.ChangePassword) },
                        icon = UiIcons.Lock,
                        modifier = Modifier.testTag("settings-change-password"))
                    SettingsDivider()
                    SettingItem("退出登录", onLogout, icon = UiIcons.Logout,
                        iconTint = Red, iconContainerColor = UiRoles.destructiveIconContainer,
                        titleColor = Red, showArrow = false, modifier = Modifier.testTag("settings-logout"))
                }
                SettingsGroup("关于", "settings-about-group") {
                    if (updateCheck != null) {
                        SettingItem(
                            title = "检查更新",
                            onClick = onCheckUpdates,
                            icon = UiIcons.Refresh,
                            value = updateRowBadge,
                            showArrow = false,
                            modifier = Modifier.testTag("settings-check-updates-entry"),
                        )
                        SettingsDivider()
                    }
                    SettingItem("关于", { onNavigate(MineDestination.About) }, icon = UiIcons.Info,
                        modifier = Modifier.testTag("settings-about-entry"))
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, tag: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = UiRoles.text(MaterialTheme.typography.titleSmall),
            fontWeight = FontWeight.Bold,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp).testTag(tag).semantics { heading() },
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
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 72.dp, end = 14.dp),
        thickness = 0.5.dp,
        color = HairlineDivider,
    )
}

internal fun vectorEncodingSummary(state: EnhancementSettingsUiState): String =
    "当前检索：${state.activeVectorEncoding}；目标编码：${state.vectorEncoding}" +
        if (state.vectorEncodingRebuildPending) "（重建中，旧索引仍在使用）" else ""
