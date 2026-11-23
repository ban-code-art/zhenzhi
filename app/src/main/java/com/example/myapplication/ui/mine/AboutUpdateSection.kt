package com.example.myapplication.ui.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.update.UpdateCheckCoordinator
import com.example.myapplication.data.update.UpdateCheckFailure
import com.example.myapplication.data.update.UpdateCheckResult
import com.example.myapplication.data.update.UpdateCheckState
import com.example.myapplication.data.update.UpdateInstallState
import com.example.myapplication.data.update.UpdateInstaller
import com.example.myapplication.ui.theme.UiRoles
import java.text.NumberFormat

@Composable
internal fun AboutUpdateSection(
    checker: UpdateCheckCoordinator,
    identity: AppAboutIdentity,
    installer: UpdateInstaller?,
) {
    val state by checker.state.collectAsState()
    val finished = state as? UpdateCheckState.Finished
    val result = finished?.result
    val available = result as? UpdateCheckResult.Available
    val running = state is UpdateCheckState.Running
    val installState = installer?.state?.collectAsState()?.value
    var openWhenFinished by rememberSaveable { mutableStateOf<Long?>(null) }
    var detailsFor by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(finished) {
        if (finished != null && finished.requestId == openWhenFinished) {
            openWhenFinished = null
            if (available != null) detailsFor = finished.requestId
        }
    }

    Column(
        Modifier.widthIn(max = 380.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = {
                detailsFor = null
                openWhenFinished = checker.request()
            },
            enabled = !running && identity.versionCode != null,
            modifier = Modifier.heightIn(min = 48.dp).testTag("about-check-updates"),
        ) {
            Text(if (running) "正在检查…" else "检查更新")
        }
        val status = when {
            result != null -> result.statusText()
            running -> "正在查询发行目录"
            else -> null
        }
        if (status != null) {
            Text(
                text = status,
                modifier = Modifier.testTag("about-update-result").semantics { liveRegion = LiveRegionMode.Polite },
                color = if (result is UpdateCheckResult.Failed) MaterialTheme.colorScheme.error else TextSecondary,
                style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                textAlign = TextAlign.Center,
            )
        }
        if (installer != null && installState != null) {
            UpdateInstallControls(installer, installState)
        }
        if (available != null) {
            TextButton(
                onClick = { detailsFor = finished.requestId },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("查看更新内容")
            }
        }
    }

    if (detailsFor != null && detailsFor == finished?.requestId && available != null) {
        val canDownload = installer != null && available.release.apk != null &&
            installState !is UpdateInstallState.Downloading && installState !is UpdateInstallState.Opening &&
            installState != UpdateInstallState.Loading &&
            (installState as? UpdateInstallState.Ready)?.apk?.sha256 != available.release.apk.sha256
        UpdateDetailsDialog(
            identity, available,
            onDismiss = { detailsFor = null },
            onDownload = if (canDownload) {
                {
                    installer?.download(available.release)
                    detailsFor = null
                }
            } else null,
        )
    }
}

@Composable
private fun UpdateDetailsDialog(
    current: AppAboutIdentity,
    available: UpdateCheckResult.Available,
    onDismiss: () -> Unit,
    onDownload: (() -> Unit)?,
) {
    val release = available.release
    val uriHandler = LocalUriHandler.current
    var linkFailed by rememberSaveable { mutableStateOf(false) }
    val megabytes = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
        .format(release.sizeBytes / 1_000_000.0)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 380.dp).testTag("about-update-details"),
        title = { Text("发现新版本") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text("当前 ${current.versionPillText} → v${release.versionName}")
                    Text(
                        "安装包 $megabytes MB",
                        style = UiRoles.text(MaterialTheme.typography.bodySmall),
                    )
                }
                available.blockedHigher?.let { higher ->
                    item { Text("v${higher.versionName} 与此设备或安装渠道不兼容。") }
                }
                item { Text("更新内容", style = UiRoles.text(MaterialTheme.typography.titleSmall)) }
                items(displayBlocks(release.notes)) { Text(it) }
                item {
                    TextButton(onClick = {
                        try {
                            uriHandler.openUri(release.releasePage)
                            linkFailed = false
                        } catch (_: IllegalArgumentException) {
                            linkFailed = true
                        } catch (_: SecurityException) {
                            linkFailed = true
                        }
                    }) { Text("查看发行页") }
                }
                if (linkFailed) item { Text("无法打开浏览器。", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (onDownload != null) {
                Button(
                    onClick = onDownload,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("about-download-update"),
                ) { Text("下载更新") }
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = onDismiss,
            ) { Text("关闭") }
        },
    )
}

@Composable
private fun UpdateInstallControls(installer: UpdateInstaller, state: UpdateInstallState) {
    when (state) {
        is UpdateInstallState.Downloading -> {
            LinearProgressIndicator(
                progress = { state.bytes.toFloat() / state.total },
                modifier = Modifier.fillMaxWidth().testTag("about-update-progress"),
            )
            val percent = state.bytes * 100 / state.total
            Text(if (percent == 100L) "正在校验安装包…" else "正在下载 v${state.version} · $percent%")
        }
        is UpdateInstallState.Ready -> {
            Text("v${state.apk.versionName} 已校验，可以安装。", modifier = Modifier.testTag("about-update-ready"))
            ContinueInstallButton(installer::install)
        }
        is UpdateInstallState.Opening -> Text("正在打开系统安装界面…")
        is UpdateInstallState.Installed -> Text("已更新至 v${state.version}", modifier = Modifier.testTag("about-update-installed"))
        is UpdateInstallState.Verifying -> Text("正在校验 v${state.version} 安装包…", style = MaterialTheme.typography.bodySmall)
        is UpdateInstallState.Installing -> Text("正在调起系统安装 v${state.version}…", style = MaterialTheme.typography.bodySmall)
        is UpdateInstallState.PendingConfirmation -> ContinueInstallButton(installer::install)
        is UpdateInstallState.Failed -> {
            Text(state.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("about-download-error"))
            state.retry?.let { release ->
                TextButton(onClick = { installer.download(release) }) { Text("重试下载") }
            }
        }
        UpdateInstallState.Loading, UpdateInstallState.Idle -> Unit
    }
}

@Composable
private fun ContinueInstallButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp).testTag("about-install-update"),
    ) { Text("继续安装") }
}

private fun displayBlocks(notes: String): List<String> {
    if (notes.isBlank()) return listOf("此发行版未提供更新说明。")
    return buildList {
        var start = 0
        while (start < notes.length) {
            var end = (start + 2048).coerceAtMost(notes.length)
            if (end < notes.length && notes[end - 1].isHighSurrogate()) end--
            add(notes.substring(start, end))
            start = end
        }
    }
}

internal fun UpdateCheckResult.statusText(): String = when (this) {
    is UpdateCheckResult.Available -> "发现新版本 v${release.versionName}"
    UpdateCheckResult.NoRelease -> "当前渠道暂无稳定发行版。"
    UpdateCheckResult.UpToDate -> "当前已是最新版本。"
    UpdateCheckResult.DeviceUnsupported -> "新版本需要更高的 Android 版本。"
    UpdateCheckResult.ChannelUnsupported -> "暂无与当前安装渠道兼容的新版本。"
    is UpdateCheckResult.Failed -> when (reason) {
        UpdateCheckFailure.SourceNotConfigured -> "更新源未配置。"
        UpdateCheckFailure.SourceUnavailable -> "更新源暂不可访问，请稍后再试。"
        UpdateCheckFailure.InstalledIdentityUnavailable -> "无法读取当前应用版本或签名。"
        UpdateCheckFailure.CatalogInvalid -> "发行信息校验失败，请稍后再试。"
        UpdateCheckFailure.CatalogIncomplete -> "未能读取完整发行目录，请重新检查。"
        UpdateCheckFailure.Conflict -> "发行目录中存在重复版号，请稍后再试。"
        UpdateCheckFailure.Network -> "暂时无法连接更新服务器，请检查网络后重试。"
        UpdateCheckFailure.RateLimited -> "更新服务器请求过于频繁，请稍后再试。"
        UpdateCheckFailure.Timeout -> "检查更新超时，请重试。"
        UpdateCheckFailure.Interrupted -> "检查更新已中断，请重试。"
        UpdateCheckFailure.Unexpected -> "检查更新失败，请稍后再试。"
    }
}
