package com.example.myapplication.data.update

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ZhenzhiUpdateFileProvider : FileProvider()

internal sealed interface UpdateInstallState {
    data object Loading : UpdateInstallState
    data object Idle : UpdateInstallState
    data class Downloading(val version: String, val bytes: Long, val total: Long) : UpdateInstallState
    data class Verifying(val version: String) : UpdateInstallState
    data class Ready(val apk: PreparedUpdate) : UpdateInstallState
    data class Opening(val apk: PreparedUpdate) : UpdateInstallState
    data class Installing(val version: String, val progress: Float = 0f) : UpdateInstallState
    data class PendingConfirmation(val version: String, val apk: PreparedUpdate) : UpdateInstallState
    data class Installed(val version: String) : UpdateInstallState
    data class Failed(val message: String, val retry: UpdateRelease? = null) : UpdateInstallState
}

internal val UpdateInstallState.isUpdateInProgress: Boolean
    get() = this is UpdateInstallState.Downloading || this is UpdateInstallState.Verifying ||
        this is UpdateInstallState.Opening || this is UpdateInstallState.Installing ||
        this is UpdateInstallState.PendingConfirmation

internal data class PreparedUpdate(
    val versionCode: Long,
    val versionName: String,
    val certificateSha256: String,
    val sizeBytes: Long,
    val sha256: String,
)

internal class UpdateInstaller(
    context: Context,
    private val scope: CoroutineScope,
    private val transport: UpdateApkTransport = GiteeApkTransport(),
) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "zhenzhi-update")
    private val verified = File(root, "verified")
    private val partial = File(root, "pending.apk")
    private val preferences = app.getSharedPreferences("zhenzhi-verified-update", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow<UpdateInstallState>(UpdateInstallState.Loading)
    val state = mutableState.asStateFlow()

    @Volatile
    private var downloadJob: Job? = null

    @Volatile
    private var installJob: Job? = null

    @Volatile
    private var lastRelease: UpdateRelease? = null

    @Volatile
    private var activeSessionId: Int = -1

    @Volatile
    private var pendingConfirmationIntent: Intent? = null

    @Volatile
    private var pendingConfirmationApk: PreparedUpdate? = null

    @Volatile
    private var pendingPermissionApk: PreparedUpdate? = null

    @Volatile
    private var pendingFallbackApk: PreparedUpdate? = null

    @Volatile
    private var activeActivityRef: WeakReference<Activity>? = null

    private val sessionCallback = object : PackageInstaller.SessionCallback() {
        override fun onCreated(sessionId: Int) = Unit
        override fun onBadgingChanged(sessionId: Int) = Unit
        override fun onActiveChanged(sessionId: Int, active: Boolean) = Unit
        override fun onProgressChanged(sessionId: Int, progress: Float) {
            if (sessionId != activeSessionId) return
            val current = mutableState.value
            if (current is UpdateInstallState.Installing) {
                mutableState.value = current.copy(progress = progress.coerceIn(0f, 1f))
            }
        }
        override fun onFinished(sessionId: Int, success: Boolean) {
            // Final install disposition is delivered via UpdateInstallResultReceiver.
        }
    }

    init {
        UpdateInstallSessionRegistry.activeInstaller = this
        registerLifecycleObserver()
        scope.launch(Dispatchers.IO) {
            mutableState.compareAndSet(UpdateInstallState.Loading, restore())
        }
    }

    private fun registerLifecycleObserver() {
        val application = app as? Application ?: return
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                activeActivityRef = WeakReference(activity)
                pendingPermissionApk?.let { apk ->
                    pendingPermissionApk = null
                    if (Build.VERSION.SDK_INT < 26 || app.packageManager.canRequestPackageInstalls()) {
                        installPrepared(apk)
                    } else {
                        mutableState.compareAndSet(UpdateInstallState.Opening(apk), UpdateInstallState.Ready(apk))
                    }
                    return
                }
                pendingFallbackApk?.let { apk ->
                    pendingFallbackApk = null
                    mutableState.compareAndSet(UpdateInstallState.Opening(apk), UpdateInstallState.Ready(apk))
                    refreshInstalled()
                }
                pendingConfirmationIntent?.let { openConfirmation(pendingConfirmationApk, it) }
            }
            override fun onActivityPaused(activity: Activity) {
                if (activeActivityRef?.get() === activity) activeActivityRef = null
            }
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (activeActivityRef?.get() === activity) activeActivityRef = null
            }
        })
    }

    fun download(release: UpdateRelease, autoInstall: Boolean = false) {
        val payload = release.apk ?: return
        lastRelease = release
        val prior = state.value
        if (prior.isUpdateInProgress || prior == UpdateInstallState.Loading) return
        if (prior is UpdateInstallState.Ready && prior.apk.sha256 == payload.sha256) {
            if (autoInstall) installPrepared(prior.apk)
            return
        }
        val downloading = UpdateInstallState.Downloading(release.versionName, 0, payload.sizeBytes)
        if (!mutableState.compareAndSet(prior, downloading)) return

        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                val installed = eligibleInstalled(release.packageName, release.certificateSha256, release.versionCode)
                if (release.minSdk > installed.sdk) throw UpdatePackageInvalid()
                checkDirectory(verified)
                if (root.usableSpace < payload.sizeBytes) throw IOException("Not enough space")
                var lastEmission = -100L
                val saved = withTimeoutOrNull(900_000) {
                    receiveUpdateApk(transport, payload, partial) { bytes ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastEmission >= 100 || bytes == payload.sizeBytes) {
                            mutableState.value = downloading.copy(bytes = bytes)
                            lastEmission = now
                        }
                    }
                    true
                }
                if (saved != true) throw IOException("Update download timed out")

                mutableState.value = UpdateInstallState.Verifying(release.versionName)
                val candidate = PreparedUpdate(
                    release.versionCode, release.versionName, release.certificateSha256, payload.sizeBytes, payload.sha256,
                )
                validateArchive(partial, candidate, installed.sdk)
                val destination = fileFor(candidate)
                if (!partial.renameTo(destination)) throw IOException("Could not store the APK")
                if (!save(candidate)) {
                    destination.delete()
                    throw IOException("Could not save the update")
                }
                prune(destination)
                if (autoInstall) {
                    withContext(Dispatchers.Main.immediate) { installPrepared(candidate) }
                } else {
                    mutableState.value = UpdateInstallState.Ready(candidate)
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = restore()
                throw cancelled
            } catch (_: UpdatePackageInvalid) {
                mutableState.value = UpdateInstallState.Failed("安装包校验失败，版本或签名与当前应用不匹配。", release)
            } catch (_: Exception) {
                mutableState.value = UpdateInstallState.Failed("下载更新失败，请检查网络和可用空间。", release)
            } finally {
                partial.delete()
                downloadJob = null
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        partial.delete()
        mutableState.value = UpdateInstallState.Idle
    }

    fun install() {
        when (val current = state.value) {
            is UpdateInstallState.Ready -> installPrepared(current.apk)
            is UpdateInstallState.PendingConfirmation -> {
                val confirm = pendingConfirmationIntent
                if (confirm != null) {
                    openConfirmation(current.apk, confirm)
                } else {
                    installPrepared(current.apk)
                }
            }
            else -> Unit
        }
    }

    private fun installPrepared(apk: PreparedUpdate) {
        if (installJob?.isActive == true) return
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            val opening = UpdateInstallState.Opening(apk)
            mutableState.value = opening
            pendingPermissionApk = apk
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.fromParts("package", app.packageName, null),
            )
            if (!openInSystem(settingsIntent)) {
                pendingPermissionApk = null
                mutableState.compareAndSet(opening, UpdateInstallState.Ready(apk))
            }
            return
        }
        mutableState.value = UpdateInstallState.Installing(apk.versionName, 0f)
        installJob = scope.launch(Dispatchers.IO) {
            var sessionId = -1
            var committed = false
            val packageInstaller = app.packageManager.packageInstaller
            try {
                val installed = readInstalledIdentity(app) ?: throw InstallRejectedException("无法读取当前安装应用信息")
                if (apk.versionCode <= installed.versionCode) {
                    throw InstallRejectedException("当前已安装版本码 (${installed.versionCode}) 已等于或高于目标版本 (${apk.versionCode})")
                }
                if (apk.certificateSha256 != installed.certificateSha256) {
                    throw InstallRejectedException("安装包签名与当前版本不一致，无法直接覆盖更新")
                }
                val file = fileFor(apk)
                if (!verifyContents(file, apk)) throw InstallRejectedException("已缓存安装包校验失败，请重新下载")
                validateArchive(file, apk, installed.sdk)

                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    setSize(file.length())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        setRequestUpdateOwnership(true)
                    }
                }
                sessionId = packageInstaller.createSession(params)
                activeSessionId = sessionId
                pendingConfirmationApk = apk
                pendingConfirmationIntent = null
                runCatching { packageInstaller.unregisterSessionCallback(sessionCallback) }
                packageInstaller.registerSessionCallback(sessionCallback, android.os.Handler(app.mainLooper))

                packageInstaller.openSession(sessionId).use { session ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        file.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                out.write(buffer, 0, read)
                            }
                        }
                        session.fsync(out)
                    }
                    val callbackIntent = Intent(app, UpdateInstallResultReceiver::class.java).apply {
                        action = "${app.packageName}.${UpdateInstallResultReceiver.ACTION_INSTALL_RESULT}"
                        putExtra(UpdateInstallResultReceiver.EXTRA_SESSION_ID, sessionId)
                        putExtra(UpdateInstallResultReceiver.EXTRA_VERSION_NAME, apk.versionName)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    val pending = PendingIntent.getBroadcast(app, sessionId, callbackIntent, flags)
                    session.commit(pending.intentSender)
                    committed = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rejected: InstallRejectedException) {
                mutableState.value = UpdateInstallState.Failed(rejected.reason, lastRelease)
            } catch (_: UpdatePackageInvalid) {
                mutableState.value = UpdateInstallState.Failed("安装包与当前应用不兼容，请重新检查更新。", lastRelease)
            } catch (sec: SecurityException) {
                // OEM fallback via ACTION_VIEW FileProvider only when Session API is restricted pre-commit.
                withContext(Dispatchers.Main.immediate) {
                    val opening = UpdateInstallState.Opening(apk)
                    mutableState.value = opening
                    pendingFallbackApk = apk
                    if (!launchActionViewFallback(fileFor(apk))) {
                        pendingFallbackApk = null
                        mutableState.compareAndSet(
                            opening,
                            UpdateInstallState.Failed(sec.message ?: "系统拒绝调起安装程序。", lastRelease),
                        )
                    }
                }
            } catch (failure: Exception) {
                mutableState.value = UpdateInstallState.Failed(
                    failure.message?.takeIf { it.isNotBlank() } ?: "安装更新失败，请重试。",
                    lastRelease,
                )
            } finally {
                if (!committed && sessionId != -1) {
                    runCatching { packageInstaller.abandonSession(sessionId) }
                    if (activeSessionId == sessionId) activeSessionId = -1
                }
            }
        }
    }

    private class InstallRejectedException(val reason: String) : Exception(reason)

    internal fun handleSessionResult(
        context: Context,
        sessionId: Int,
        versionName: String,
        status: Int,
        statusMessage: String?,
        confirmationIntent: Intent?,
    ) {
        if (activeSessionId != -1 && sessionId != activeSessionId) return
        val apk = pendingConfirmationApk
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION && confirmationIntent != null) {
            val current = state.value
            if (current is UpdateInstallState.Opening && current.apk == apk) return
            openConfirmation(apk, confirmationIntent)
            return
        }

        cleanupSessionResources(context)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                prune(null)
                val targetVersion = versionName.ifBlank { apk?.versionName.orEmpty() }
                mutableState.value = UpdateInstallState.Installed(targetVersion)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                if (apk != null) {
                    mutableState.value = UpdateInstallState.Ready(apk)
                } else {
                    mutableState.value = UpdateInstallState.Failed("已取消安装。", lastRelease)
                }
            }
            else -> {
                val friendly = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT ->
                        "安装冲突或签名不一致，无法直接覆盖当前版本（${statusMessage ?: "CONFLICT"}）。"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "安装包与当前系统或架构不兼容（${statusMessage ?: "INCOMPATIBLE"}）。"
                    PackageInstaller.STATUS_FAILURE_INVALID ->
                        "安装包无效或版本号未高于当前版本（${statusMessage ?: "INVALID"}）。"
                    PackageInstaller.STATUS_FAILURE_STORAGE ->
                        "设备存储空间不足，请清理后重试。"
                    PackageInstaller.STATUS_FAILURE_BLOCKED ->
                        "系统拦截了本次安装，请检查系统“应用安装权限”设置。"
                    else -> statusMessage?.takeIf { it.isNotBlank() } ?: "系统未能完成安装（状态码 $status）。"
                }
                mutableState.value = UpdateInstallState.Failed(friendly, lastRelease)
            }
        }
    }

    private fun cleanupSessionResources(context: Context) {
        pendingConfirmationIntent = null
        pendingConfirmationApk = null
        activeSessionId = -1
        runCatching { context.packageManager.packageInstaller.unregisterSessionCallback(sessionCallback) }
    }

    private fun openConfirmation(apk: PreparedUpdate?, intent: Intent) {
        pendingConfirmationIntent = null
        if (apk != null) mutableState.value = UpdateInstallState.Opening(apk)
        if (!openInSystem(intent)) {
            // Keep retry available only when Android could not open its confirmation screen.
            pendingConfirmationIntent = intent
            if (apk != null) {
                mutableState.value = UpdateInstallState.PendingConfirmation(apk.versionName, apk)
            }
        }
    }

    private fun openInSystem(intent: Intent): Boolean {
        val activity = activeActivityRef?.get()
        return (activity != null && !activity.isFinishing && launchActivitySafely(activity, intent)) ||
            launchFromAppContext(intent)
    }

    private fun launchActivitySafely(activity: Activity, intent: Intent): Boolean =
        runCatching {
            activity.startActivity(intent)
            true
        }.getOrDefault(false)

    private fun launchFromAppContext(intent: Intent): Boolean =
        runCatching {
            app.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)

    private fun launchActionViewFallback(file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri("安装包", uri)
        }
        app.startActivity(intent)
        true
    }.getOrDefault(false)

    suspend fun installationIntent(): Intent? = withContext(Dispatchers.IO) {
        val ready = state.value as? UpdateInstallState.Ready ?: return@withContext null
        val apk = ready.apk
        val opening = UpdateInstallState.Opening(apk)
        if (!mutableState.compareAndSet(ready, opening)) return@withContext null
        try {
            val installed = eligibleInstalled(app.packageName, apk.certificateSha256, apk.versionCode)
            if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
                return@withContext Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.fromParts("package", app.packageName, null),
                )
            }
            val file = fileFor(apk)
            if (!verifyContents(file, apk)) throw UpdatePackageInvalid()
            validateArchive(file, apk, installed.sdk)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
            @Suppress("DEPRECATION")
            Intent(Intent.ACTION_INSTALL_PACKAGE, uri)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri("安装包", uri) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: UpdatePackageInvalid) {
            mutableState.value = UpdateInstallState.Failed("安装包或已安装版本已变化，请重新检查更新。")
            null
        } catch (_: Exception) {
            mutableState.value = UpdateInstallState.Failed("没有准备好系统安装，请重新检查更新。")
            null
        } finally {
            mutableState.compareAndSet(opening, ready)
        }
    }

    fun refreshInstalled() {
        val current = state.value
        val apk = (current as? UpdateInstallState.Ready)?.apk
            ?: (current as? UpdateInstallState.Opening)?.apk
            ?: (current as? UpdateInstallState.PendingConfirmation)?.apk
            ?: return
        scope.launch(Dispatchers.IO) {
            val local = readInstalledIdentity(app) ?: return@launch
            if (local.versionCode >= apk.versionCode && local.certificateSha256 == apk.certificateSha256) {
                if (mutableState.compareAndSet(current, UpdateInstallState.Installed(apk.versionName))) {
                    prune(null)
                }
            }
        }
    }

    private fun restore(): UpdateInstallState {
        partial.delete()
        val apk = load()
        val local = readInstalledIdentity(app)
        if (apk == null || local == null || local.certificateSha256 != apk.certificateSha256) {
            if (local != null) prune(null)
            return UpdateInstallState.Idle
        }
        if (local.versionCode >= apk.versionCode) {
            prune(null)
            return if (local.versionCode == apk.versionCode) UpdateInstallState.Installed(apk.versionName)
            else UpdateInstallState.Idle
        }
        val file = fileFor(apk)
        val ready = try {
            verifyContents(file, apk) && validateArchive(file, apk, local.sdk).let { true }
        } catch (_: Exception) {
            false
        }
        if (!ready) {
            prune(null)
            return UpdateInstallState.Idle
        }
        prune(file)
        return UpdateInstallState.Ready(apk)
    }

    private fun eligibleInstalled(packageName: String, certificate: String, versionCode: Long): InstalledUpdateIdentity {
        val installed = readInstalledIdentity(app) ?: throw UpdatePackageInvalid()
        if (packageName != installed.packageName || certificate != installed.certificateSha256 ||
            versionCode <= installed.versionCode
        ) throw UpdatePackageInvalid()
        return installed
    }

    private fun validateArchive(file: File, apk: PreparedUpdate, sdk: Int) {
        val info = readArchive(app.packageManager, file) ?: throw UpdatePackageInvalid()
        val uriCertificate = packageCertificate(info) ?: throw UpdatePackageInvalid()
        val manifest = info.applicationInfo ?: throw UpdatePackageInvalid()
        if (info.packageName != app.packageName || packageVersionCode(info) != apk.versionCode ||
            info.versionName != apk.versionName || uriCertificate != apk.certificateSha256 ||
            manifest.minSdkVersion > sdk || !info.splitNames.isNullOrEmpty()
        ) throw UpdatePackageInvalid()
    }

    private fun fileFor(apk: PreparedUpdate) = File(verified, "${apk.sha256}.apk")

    private fun checkDirectory(directory: File) {
        if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Could not create the update directory")
    }

    private fun prune(keep: File?) {
        verified.listFiles()?.filter { it != keep }?.forEach { it.delete() }
    }

    private fun save(apk: PreparedUpdate): Boolean = preferences.edit()
        .putLong("code", apk.versionCode)
        .putString("version", apk.versionName)
        .putString("certificate", apk.certificateSha256)
        .putLong("size", apk.sizeBytes)
        .putString("sha256", apk.sha256)
        .commit()

    private fun load(): PreparedUpdate? {
        val version = preferences.getString("version", null) ?: return null
        val certificate = preferences.getString("certificate", null)?.takeIf { it.matches(updateDigest) } ?: return null
        val digest = preferences.getString("sha256", null)?.takeIf { it.matches(updateDigest) } ?: return null
        val code = preferences.getLong("code", 0).takeIf { it in 1..Int.MAX_VALUE.toLong() } ?: return null
        val size = preferences.getLong("size", 0).takeIf { it in 1..100_000_000 } ?: return null
        return PreparedUpdate(code, version, certificate, size, digest)
    }
}

private fun verifyContents(file: File, apk: PreparedUpdate): Boolean {
    if (!file.isFile || file.length() != apk.sizeBytes) return false
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().toSha256Hex() == apk.sha256
}

@Suppress("DEPRECATION")
private fun readArchive(manager: PackageManager, file: File): PackageInfo? {
    val signers = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    return if (Build.VERSION.SDK_INT >= 33) {
        manager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(signers.toLong()))
    } else {
        manager.getPackageArchiveInfo(file.absolutePath, signers)
    }
}
