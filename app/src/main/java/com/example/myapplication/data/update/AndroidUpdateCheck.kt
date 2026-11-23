package com.example.myapplication.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.example.myapplication.BuildConfig
import kotlinx.coroutines.CoroutineScope

private val preferredDomesticMirror = MirrorSelectionMemory()

internal fun createAppUpdateCheck(context: Context, scope: CoroutineScope): UpdateCheckCoordinator {
    val source = UpdateSource.configured(
        BuildConfig.S1_UPDATE_REPOSITORY, BuildConfig.S1_UPDATE_CHANNEL, BuildConfig.S1_UPDATE_SIGNER,
    )
    val client = GitHubUpdateCheck(source, MirrorMetadataTransport(source, preferredDomesticMirror))
    return UpdateCheckCoordinator(scope) { client.check { readInstalledIdentity(context) } }
}

@Suppress("DEPRECATION")
internal fun readInstalledIdentity(context: Context): InstalledUpdateIdentity? {
    val manager = context.packageManager
    val name = context.packageName
    val info = try {
        when {
            Build.VERSION.SDK_INT >= 33 -> manager.getPackageInfo(
                name, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
            Build.VERSION.SDK_INT >= 28 -> manager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
            else -> manager.getPackageInfo(name, PackageManager.GET_SIGNATURES)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        return null
    } catch (_: SecurityException) {
        return null
    }

    val certificate = packageCertificate(info) ?: return null
    val version = packageVersionCode(info).takeIf { it > 0 } ?: return null
    return InstalledUpdateIdentity(name, version, certificate, Build.VERSION.SDK_INT)
}

@Suppress("DEPRECATION")
internal fun packageCertificate(info: PackageInfo): String? {
    val signer = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.singleOrNull()
    else info.signatures?.singleOrNull()
    return signer?.toByteArray()?.let(::updateSha256)
}

@Suppress("DEPRECATION")
internal fun packageVersionCode(info: PackageInfo): Long =
    if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
