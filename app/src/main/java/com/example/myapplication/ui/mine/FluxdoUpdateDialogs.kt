package com.example.myapplication.ui.mine

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.myapplication.data.update.UpdateCheckCoordinator
import com.example.myapplication.data.update.UpdateCheckFailure
import com.example.myapplication.data.update.UpdateCheckResult
import com.example.myapplication.data.update.UpdateCheckState
import com.example.myapplication.data.update.UpdateInstallState
import com.example.myapplication.data.update.UpdateInstaller
import com.example.myapplication.data.update.UpdateRelease
import com.example.myapplication.data.update.isUpdateInProgress
import com.example.myapplication.ui.components.UiPane
import com.example.myapplication.ui.components.UiPaneRole
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import java.util.Locale

private enum class UpdateModalMode {
    CHECKING,
    PREVIEW,
    PROGRESS,
    UP_TO_DATE,
    CHECK_FAILED,
}

/** Keeps the current update step visible in a shared glass dialog. */
@Composable
internal fun SettingsUpdateModalHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    updateCheck: UpdateCheckCoordinator,
    installer: UpdateInstaller?,
    currentVersionText: String,
) {
    val checkState by updateCheck.state.collectAsState()
    val installState by (installer?.state?.collectAsState()
        ?: remember { mutableStateOf<UpdateInstallState>(UpdateInstallState.Idle) })

    LifecycleResumeEffect(installer) {
        installer?.refreshInstalled()
        onPauseOrDispose {}
    }

    var forceProgress by remember(visible) { mutableStateOf(false) }

    val progressActive = installState.isUpdateInProgress
    LaunchedEffect(progressActive, checkState) {
        when {
            progressActive -> forceProgress = true
            checkState is UpdateCheckState.Running -> forceProgress = false
        }
    }

    if (!visible) return

    val result = (checkState as? UpdateCheckState.Finished)?.result
    val mode = when {
        forceProgress || progressActive -> UpdateModalMode.PROGRESS
        checkState is UpdateCheckState.Running -> UpdateModalMode.CHECKING
        result != null -> when (result) {
            is UpdateCheckResult.Available -> UpdateModalMode.PREVIEW
            UpdateCheckResult.UpToDate,
            UpdateCheckResult.NoRelease,
            UpdateCheckResult.DeviceUnsupported,
            UpdateCheckResult.ChannelUnsupported, -> UpdateModalMode.UP_TO_DATE
            is UpdateCheckResult.Failed -> UpdateModalMode.CHECK_FAILED
        }
        else -> UpdateModalMode.CHECKING
    }

    Dialog(
        onDismissRequest = {
            // Allow dismissing preview, results, or backgrounding download; block accidental tap during SHA verify.
            if (installState !is UpdateInstallState.Verifying) {
                onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val scrim = UiRoles.extended.modalScrimAlpha
        SideEffect { window?.setDimAmount(scrim) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (mode) {
                UpdateModalMode.CHECKING -> CheckingProgressCard(
                    onDismiss = onDismiss,
                )
                UpdateModalMode.PREVIEW -> {
                    val available = result as UpdateCheckResult.Available
                    FluxdoUpdatePreviewCard(
                        currentVersionText = currentVersionText,
                        release = available.release,
                        installState = installState,
                        onStartUpdate = {
                            val target = available.release
                            if (installer != null) {
                                forceProgress = true
                                val cachedSha = (installState as? UpdateInstallState.Ready)?.apk?.sha256
                                if (cachedSha != null && cachedSha == target.apk?.sha256) {
                                    installer.install()
                                } else {
                                    installer.download(target, autoInstall = true)
                                }
                            }
                        },
                        onDismiss = onDismiss,
                    )
                }
                UpdateModalMode.PROGRESS -> FluxdoDownloadProgressCard(
                    installState = installState,
                    onCancelDownload = {
                        installer?.cancelDownload()
                        forceProgress = false
                        onDismiss()
                    },
                    onBackground = {
                        onDismiss()
                    },
                    onRetry = { release ->
                        installer?.download(release, autoInstall = true)
                    },
                    onInstallNow = {
                        installer?.install()
                    },
                    onClose = {
                        forceProgress = false
                        onDismiss()
                    },
                )
                UpdateModalMode.UP_TO_DATE -> {
                    val title = if (result == UpdateCheckResult.UpToDate) "已是最新版本" else "暂无可用更新"
                    val msg = if (result == UpdateCheckResult.UpToDate) {
                        "当前使用的针知 ($currentVersionText) 已经是最新的正式发布版本。"
                    } else {
                        result?.statusText() ?: "当前暂无可用更新。"
                    }
                    FluxdoStatusDialogCard(
                        title = title,
                        message = msg,
                        primaryLabel = "确定",
                        onPrimary = onDismiss,
                    )
                }
                UpdateModalMode.CHECK_FAILED -> {
                    val failure = result as UpdateCheckResult.Failed
                    FluxdoStatusDialogCard(
                        title = "检查更新失败",
                        message = failure.statusText(),
                        primaryLabel = "重试",
                        onPrimary = { updateCheck.request() },
                        secondaryLabel = "关闭",
                        onSecondary = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckingProgressCard(
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    UpdateGlassPane(
        modifier = Modifier.width(300.dp).testTag("update-checking-dialog"),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(38.dp),
                color = colors.primary,
                strokeWidth = 3.5.dp,
            )
            Text(
                text = "正在检查更新…",
                style = UiRoles.text(MaterialTheme.typography.titleMedium),
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Text(
                text = "正在从国内加速镜像源获取最新发行信息",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onDismiss) {
                Text("后台等待", color = colors.primary, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun FluxdoUpdatePreviewCard(
    currentVersionText: String,
    release: UpdateRelease,
    installState: UpdateInstallState,
    onStartUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val apk = release.apk
    val isCachedReady = apk != null && (installState as? UpdateInstallState.Ready)?.apk?.sha256 == apk.sha256

    UpdateGlassPane(
        modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth().testTag("about-update-details"),
    ) {
        Box(Modifier.fillMaxWidth()) {
            // FluxDO tilted rocket watermark
            Icon(
                imageVector = RocketGlyph,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 18.dp, y = (-12).dp)
                    .size(132.dp)
                    .rotate(-14f)
                    .alpha(0.06f),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = SparkleGlyph,
                            contentDescription = null,
                            tint = colors.onPrimaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "发现新版本",
                            style = UiRoles.text(MaterialTheme.typography.headlineSmall),
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface,
                        )
                        Text(
                            text = if (isCachedReady) "新版本安装包已就绪，可直接安装" else "新版本已发布，推荐立即更新",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }

                // Version diff pills & APK size
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = colors.surfaceContainerHighest,
                    ) {
                        Text(
                            text = currentVersionText,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    Icon(
                        imageVector = ArrowForwardGlyph,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = colors.primaryContainer,
                    ) {
                        Text(
                            text = "v${release.versionName}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    if (apk != null) {
                        Spacer(Modifier.weight(1f))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = colors.surfaceContainerHigh,
                        ) {
                            Text(
                                text = formatMegabytes(apk.sizeBytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.45f))

                Text(
                    text = "更新内容",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurfaceVariant,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp, max = 210.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = release.notes.ifBlank { "此发行版未提供更新说明。" },
                        modifier = Modifier.testTag("about-update-notes"),
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                        color = colors.onSurface,
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (apk != null) {
                        Button(
                            onClick = onStartUpdate,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("about-download-update"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.primary,
                                contentColor = colors.onPrimary,
                            ),
                        ) {
                            Text(
                                text = if (isCachedReady) "立即安装 v${release.versionName}" else "立即更新",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (release.releasePage.isNotBlank()) {
                            TextButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(release.releasePage))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }
                                },
                            ) {
                                Text("查看发行页", color = colors.primary, fontWeight = FontWeight.Medium)
                            }
                        } else {
                            Spacer(Modifier.width(1.dp))
                        }
                        TextButton(onClick = onDismiss) {
                            Text("以后再说", color = colors.onSurfaceVariant, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FluxdoDownloadProgressCard(
    installState: UpdateInstallState,
    onCancelDownload: () -> Unit,
    onBackground: () -> Unit,
    onRetry: (UpdateRelease) -> Unit,
    onInstallNow: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val meshColors = remember(colors) {
        listOf(
            colors.surfaceContainerLowest,
            colors.primaryContainer,
            colors.secondaryContainer,
            colors.tertiaryContainer,
        )
    }

    UpdateGlassPane(
        modifier = Modifier.size(width = 300.dp, height = 340.dp).testTag("about-update-progress"),
    ) {
        Box(Modifier.fillMaxSize()) {
            val stateKey = progressVisualKey(installState)
            if (stateKey == "downloading" || stateKey == "verifying") {
                MeshGradientBackdrop(
                    colors = meshColors,
                    backgroundColor = colors.surface,
                    distortion = 0.8f,
                    swirl = 0.1f,
                    speed = 1.0f,
                    modifier = Modifier.matchParentSize().alpha(.24f),
                )
            }
            Box(Modifier.fillMaxSize().padding(24.dp)) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    when (stateKey) {
                        "downloading" -> {
                            val downloading = installState as? UpdateInstallState.Downloading
                            val bytes = downloading?.bytes ?: 0L
                            val total = downloading?.total ?: 0L
                            val fraction = if (total > 0L) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
                            val percent = (fraction * 100f).toInt().coerceIn(0, 100)

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = colors.primaryContainer.copy(alpha = 0.88f),
                                ) {
                                    Text(
                                        text = "正在下载 v${downloading?.version.orEmpty()}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                    )
                                }
                                Text(
                                    text = if (total > 0L) "${formatMegabytes(bytes)} / ${formatMegabytes(total)}" else "正在连接更新源…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = "$percent%",
                                    style = MaterialTheme.typography.displayLarge.copy(
                                        fontSize = 64.sp,
                                        lineHeight = 68.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFeatureSettings = "tnum",
                                    ),
                                    color = colors.onSurface,
                                )
                                if (total > 0L) {
                                    LinearProgressIndicator(
                                        progress = { fraction },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(CircleShape),
                                        color = colors.primary,
                                        trackColor = colors.onSurface.copy(alpha = 0.14f),
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(CircleShape),
                                        color = colors.primary,
                                        trackColor = colors.onSurface.copy(alpha = 0.14f),
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                TextButton(onClick = onCancelDownload) {
                                    Text("取消", color = colors.onSurfaceVariant, fontWeight = FontWeight.Medium)
                                }
                                TextButton(onClick = onBackground) {
                                    Text("后台下载", color = colors.primary, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        "verifying" -> {
                            val verifying = installState as? UpdateInstallState.Verifying
                            Spacer(Modifier.height(4.dp))
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(52.dp),
                                    color = colors.primary,
                                    strokeWidth = 4.dp,
                                )
                                Text(
                                    text = "正在校验安装包…",
                                    style = UiRoles.text(MaterialTheme.typography.titleMedium),
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onSurface,
                                )
                                Text(
                                    text = "v${verifying?.version.orEmpty()} SHA-256 完整性与签名校验中",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                        }

                        "installing", "ready", "pending_confirm" -> {
                            val versionName = when (installState) {
                                is UpdateInstallState.Installing -> installState.version
                                is UpdateInstallState.Opening -> installState.apk.versionName
                                is UpdateInstallState.Ready -> installState.apk.versionName
                                is UpdateInstallState.PendingConfirmation -> installState.version
                                else -> ""
                            }
                            Spacer(Modifier.height(4.dp))
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(colors.primaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = InstallPhoneGlyph,
                                        contentDescription = null,
                                        tint = colors.onPrimaryContainer,
                                        modifier = Modifier.size(34.dp),
                                    )
                                }
                                Text(
                                    text = when (stateKey) {
                                        "pending_confirm" -> "系统安装界面尚未打开"
                                        "ready" -> "v$versionName 已就绪"
                                        else -> "正在调起系统安装…"
                                    },
                                    style = UiRoles.text(MaterialTheme.typography.titleMedium),
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onSurface,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.testTag("about-update-ready"),
                                )
                                Text(
                                    text = when (stateKey) {
                                        "pending_confirm" -> "继续安装以重新打开系统界面。"
                                        "ready" -> "安装包校验完成，可以继续安装。"
                                        else -> "请在系统界面完成 v$versionName 覆盖更新。"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (stateKey == "ready" || stateKey == "pending_confirm") {
                                    Button(
                                        onClick = onInstallNow,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(44.dp)
                                            .testTag("about-install-update"),
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = colors.primary,
                                            contentColor = colors.onPrimary,
                                        ),
                                    ) {
                                        Text("继续安装", fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                TextButton(onClick = onClose) {
                                    Text("关闭", color = colors.onSurfaceVariant)
                                }
                            }
                        }

                        "installed" -> {
                            val installed = installState as? UpdateInstallState.Installed
                            Spacer(Modifier.height(4.dp))
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(UiRoles.extended.successContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = UiIcons.Check,
                                        contentDescription = null,
                                        tint = UiRoles.extended.success,
                                        modifier = Modifier.size(34.dp),
                                    )
                                }
                                Text(
                                    text = "已更新至 v${installed?.version.orEmpty()}",
                                    style = UiRoles.text(MaterialTheme.typography.titleMedium),
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onSurface,
                                    modifier = Modifier.testTag("about-update-installed"),
                                )
                                Text(
                                    text = "新版本已成功覆盖安装。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            Button(
                                onClick = onClose,
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.primary,
                                    contentColor = colors.onPrimary,
                                ),
                            ) {
                                Text("完成", fontWeight = FontWeight.SemiBold)
                            }
                        }

                        else -> {
                            val failed = installState as? UpdateInstallState.Failed
                            Spacer(Modifier.height(4.dp))
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(CircleShape)
                                        .background(colors.errorContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = UiIcons.Close,
                                        contentDescription = null,
                                        tint = colors.error,
                                        modifier = Modifier.size(30.dp),
                                    )
                                }
                                Text(
                                    text = "更新失败",
                                    style = UiRoles.text(MaterialTheme.typography.titleMedium),
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onSurface,
                                )
                                Text(
                                    text = failed?.message ?: "安装过程中出现错误，请重试。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .testTag("about-download-error")
                                        .semantics { liveRegion = LiveRegionMode.Polite },
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                TextButton(onClick = onClose) {
                                    Text("关闭", color = colors.onSurfaceVariant)
                                }
                                val retryRelease = failed?.retry
                                if (retryRelease != null) {
                                    Button(
                                        onClick = { onRetry(retryRelease) },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = colors.primary,
                                            contentColor = colors.onPrimary,
                                        ),
                                    ) {
                                        Text("重试")
                                    }
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
private fun FluxdoStatusDialogCard(
    title: String,
    message: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    UpdateGlassPane(
        modifier = Modifier.widthIn(max = 348.dp).fillMaxWidth().testTag("about-update-result"),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = UiRoles.text(MaterialTheme.typography.titleLarge),
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (secondaryLabel != null && onSecondary != null) {
                    TextButton(onClick = onSecondary) {
                        Text(secondaryLabel, color = colors.onSurfaceVariant, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    onClick = onPrimary,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary,
                    ),
                ) {
                    Text(primaryLabel, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun UpdateGlassPane(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    UiPane(
        role = UiPaneRole.Alert,
        shape = RoundedCornerShape(34.dp),
        modifier = modifier,
        content = content,
    )
}

private fun progressVisualKey(state: UpdateInstallState): String = when (state) {
    is UpdateInstallState.Downloading, UpdateInstallState.Idle, UpdateInstallState.Loading -> "downloading"
    is UpdateInstallState.Verifying -> "verifying"
    is UpdateInstallState.Installing, is UpdateInstallState.Opening -> "installing"
    is UpdateInstallState.Ready -> "ready"
    is UpdateInstallState.PendingConfirmation -> "pending_confirm"
    is UpdateInstallState.Installed -> "installed"
    is UpdateInstallState.Failed -> "failed"
}

private fun formatMegabytes(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes.coerceAtLeast(0L) / 1_048_576.0)

private val RocketGlyph by lazy {
    ImageVector.Builder("RocketWatermark", 24.dp, 24.dp, 24f, 24f).path(
        fill = SolidColor(Color.Black),
    ) {
        moveTo(13.5f, 3f)
        curveTo(16.8f, 3.3f, 19.8f, 5.2f, 21f, 8.2f)
        curveTo(19.6f, 11.9f, 17f, 15.1f, 13.7f, 17.1f)
        lineTo(14.5f, 20.5f)
        lineTo(11f, 18.8f)
        lineTo(8.5f, 20.8f)
        lineTo(9.2f, 17.5f)
        lineTo(6.5f, 14.8f)
        lineTo(3.2f, 15.5f)
        lineTo(5.2f, 13f)
        lineTo(3.5f, 9.5f)
        lineTo(6.9f, 10.3f)
        curveTo(8.9f, 7f, 11.2f, 4.4f, 13.5f, 3f)
        close()
    }.build()
}

private val SparkleGlyph by lazy {
    ImageVector.Builder("UpdateSparkle", 24.dp, 24.dp, 24f, 24f).path(
        fill = SolidColor(Color.Black),
    ) {
        moveTo(12f, 2.5f)
        lineTo(14.2f, 8.8f)
        lineTo(20.5f, 11f)
        lineTo(14.2f, 13.2f)
        lineTo(12f, 19.5f)
        lineTo(9.8f, 13.2f)
        lineTo(3.5f, 11f)
        lineTo(9.8f, 8.8f)
        close()
        moveTo(18.5f, 15.5f)
        lineTo(19.4f, 17.6f)
        lineTo(21.5f, 18.5f)
        lineTo(19.4f, 19.4f)
        lineTo(18.5f, 21.5f)
        lineTo(17.6f, 19.4f)
        lineTo(15.5f, 18.5f)
        lineTo(17.6f, 17.6f)
        close()
    }.build()
}

private val ArrowForwardGlyph by lazy {
    ImageVector.Builder("ArrowForward", 24.dp, 24.dp, 24f, 24f).path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(5f, 12f)
        lineTo(19f, 12f)
        moveTo(13f, 6f)
        lineTo(19f, 12f)
        lineTo(13f, 18f)
    }.build()
}

private val InstallPhoneGlyph by lazy {
    ImageVector.Builder("InstallMobile", 24.dp, 24.dp, 24f, 24f).path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.9f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(14f, 3.5f)
        lineTo(8f, 3.5f)
        curveTo(6.9f, 3.5f, 6f, 4.4f, 6f, 5.5f)
        lineTo(6f, 18.5f)
        curveTo(6f, 19.6f, 6.9f, 20.5f, 8f, 20.5f)
        lineTo(16f, 20.5f)
        curveTo(17.1f, 20.5f, 18f, 19.6f, 18f, 18.5f)
        lineTo(18f, 13f)
        moveTo(16f, 3.5f)
        lineTo(16f, 10.5f)
        moveTo(13.2f, 7.8f)
        lineTo(16f, 10.6f)
        lineTo(18.8f, 7.8f)
    }.build()
}
