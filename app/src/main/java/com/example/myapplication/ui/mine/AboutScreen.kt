package com.example.myapplication.ui.mine

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.myapplication.LearningApplication
import com.example.myapplication.R
import com.example.myapplication.data.update.UpdateCheckCoordinator
import com.example.myapplication.data.update.UpdateInstaller
import com.example.myapplication.ui.navigation.BackDecision
import com.example.myapplication.ui.navigation.BackPage
import com.example.myapplication.ui.navigation.BackState
import com.example.myapplication.ui.navigation.isBackImeVisible
import com.example.myapplication.ui.navigation.unifiedBackRequest
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

internal const val AboutLearningDisclaimer =
    "本应用内针灸知识仅供学习参考，不构成任何医疗建议或诊疗依据。"

internal data class AppAboutIdentity(
    val appName: String,
    val versionName: String,
    val versionCode: Long?,
    val iconResId: Int,
    val versionUnavailable: Boolean = false,
    val isRoundIcon: Boolean = false,
) {
    val versionPillText: String
        get() = if (versionUnavailable || versionName == "版本未知") "版本未知" else "v$versionName"
}

internal fun resolveAppAboutIdentity(context: Context): AppAboutIdentity {
    val appContext = context.applicationContext ?: context
    val pm = appContext.packageManager
    val packageInfo = runCatching {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(appContext.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            pm.getPackageInfo(appContext.packageName, 0)
        }
    }.getOrNull()
    val appInfo = packageInfo?.applicationInfo ?: appContext.applicationInfo
    val label = runCatching {
        appInfo?.loadLabel(pm)?.toString()?.trim()?.ifEmpty { null }
    }.getOrNull() ?: runCatching { appContext.getString(R.string.app_name) }.getOrDefault("针知")
    val rawVersion = packageInfo?.versionName?.trim()?.ifEmpty { null }
    val versionCode = packageInfo?.let {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
    }
    val activeSelection = runCatching { LauncherIconController(context).current() }.getOrNull()
    val iconRes = activeSelection?.let {
        if (it.shape == LauncherIconShape.ROUND) it.icon.roundIconRes else it.icon.iconRes
    } ?: appInfo?.icon?.takeIf { it != 0 } ?: R.mipmap.ic_launcher_zhenzhi
    return AppAboutIdentity(
        appName = label,
        versionName = rawVersion ?: "版本未知",
        versionCode = versionCode,
        iconResId = iconRes,
        versionUnavailable = rawVersion == null,
        isRoundIcon = activeSelection?.shape == LauncherIconShape.ROUND,
    )
}

@Composable
internal fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var identity by remember(context.applicationContext) { mutableStateOf(resolveAppAboutIdentity(context)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(context.applicationContext) {
        identity = resolveAppAboutIdentity(context)
        onPauseOrDispose {}
    }
    val container = remember(context.applicationContext) {
        (context.applicationContext as? LearningApplication)?.container
    }
    AboutScreen(identity = identity, onBack = onBack, updateCheck = container?.updateCheck, installer = container?.updateInstaller)
}

@Composable
internal fun AboutScreen(
    identity: AppAboutIdentity,
    onBack: () -> Unit,
    updateCheck: UpdateCheckCoordinator? = null,
    installer: UpdateInstaller? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val iconSizePx = with(density) { 68.dp.roundToPx().coerceAtLeast(68) }
    val launcherBitmap = remember(context.applicationContext, identity.iconResId, iconSizePx) {
        loadAboutIconBitmap(context, identity.iconResId, iconSizePx)
    }
    val requestBack = unifiedBackRequest(
        BackState(page = BackPage.CHILD, imeVisible = isBackImeVisible()),
    ) { decision ->
        if (decision == BackDecision.PopPage) onBack()
    }

    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        SubPageScaffold(
            title = "关于",
            onBack = requestBack,
            glassBack = true,
            actionId = "about.back",
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, top = GlassPageBarHeight + 8.dp, end = 16.dp, bottom = 32.dp)
                    .testTag("about-scroll"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, top = 16.dp, end = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(
                        modifier = Modifier.size(96.dp),
                        shape = RoundedCornerShape(28.dp),
                        color = UiRoles.surface(UiSurfaceRole.Block),
                    ) {
                        Box(Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.Center) {
                            if (launcherBitmap != null) {
                                Image(
                                    bitmap = launcherBitmap,
                                    contentDescription = null,
                                    modifier = Modifier.size(68.dp).clip(
                                        if (identity.isRoundIcon) CircleShape else RoundedCornerShape(18.dp),
                                    ),
                                )
                            } else {
                                Box(
                                    Modifier.size(68.dp).clip(
                                        if (identity.isRoundIcon) CircleShape else RoundedCornerShape(18.dp),
                                    ).background(UiRoles.iconContainer),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = identity.appName,
                        style = UiRoles.text(MaterialTheme.typography.headlineSmall),
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("about-app-name"),
                    )
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = CircleShape,
                        color = UiRoles.surface(UiSurfaceRole.Block),
                        modifier = Modifier
                            .semantics(mergeDescendants = true) {}
                            .testTag("about-version-pill"),
                    ) {
                        Text(
                            text = identity.versionPillText,
                            style = UiRoles.text(MaterialTheme.typography.labelMedium).copy(
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = TextSecondary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = AboutLearningDisclaimer,
                        style = UiRoles.text(MaterialTheme.typography.bodyMedium).copy(
                            fontSize = 15.sp,
                            lineHeight = 22.5.sp,
                        ),
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .widthIn(max = 420.dp)
                            .padding(horizontal = 16.dp)
                            .testTag("about-disclaimer"),
                    )
                    if (identity.versionUnavailable) {
                        Spacer(Modifier.height(16.dp))
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier
                                .widthIn(max = 420.dp)
                                .testTag("about-identity-error")
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        ) {
                            Column(
                                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = "未能从系统读取当前应用版本号，已按安全默认值展示。",
                                    style = UiRoles.text(MaterialTheme.typography.bodySmall),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun loadAboutIconBitmap(context: Context, iconResId: Int, sizePx: Int): ImageBitmap? = runCatching {
    val drawable = ContextCompat.getDrawable(context, iconResId)
        ?: runCatching { context.packageManager.getApplicationIcon(context.applicationInfo) }.getOrNull()
        ?: return@runCatching null
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
        drawable is android.graphics.drawable.AdaptiveIconDrawable
    ) {
        val inset = sizePx / 4
        drawable.setBounds(-inset, -inset, sizePx + inset, sizePx + inset)
    } else {
        drawable.setBounds(0, 0, sizePx, sizePx)
    }
    drawable.draw(canvas)
    bitmap.asImageBitmap()
}.getOrNull()
