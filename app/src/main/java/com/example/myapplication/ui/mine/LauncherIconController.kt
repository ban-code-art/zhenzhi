package com.example.myapplication.ui.mine

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.R

internal enum class LauncherIcon(
    val label: String,
    val alias: String,
    @get:DrawableRes val iconRes: Int,
    @get:DrawableRes val roundIconRes: Int,
) {
    DEFAULT("默认图标", "LauncherIconDefault", R.mipmap.ic_launcher_zhenzhi, R.mipmap.ic_launcher_zhenzhi_round),
    CUSTOM_01("青环针影", "LauncherIcon01", R.mipmap.ic_launcher_custom_01, R.mipmap.ic_launcher_custom_01_round),
    CUSTOM_02("双弧经络", "LauncherIcon02", R.mipmap.ic_launcher_custom_02, R.mipmap.ic_launcher_custom_02_round),
    CUSTOM_03("流线针韵", "LauncherIcon03", R.mipmap.ic_launcher_custom_03, R.mipmap.ic_launcher_custom_03_round),
    CUSTOM_04("金针穴位", "LauncherIcon04", R.mipmap.ic_launcher_custom_04, R.mipmap.ic_launcher_custom_04_round),
    CUSTOM_05("曲线环针", "LauncherIcon05", R.mipmap.ic_launcher_custom_05, R.mipmap.ic_launcher_custom_05_round),
}

internal enum class LauncherIconShape(val label: String) {
    SYSTEM("系统默认"),
    NORMAL("普通版"),
    ROUND("圆形版"),
}

internal data class LauncherIconSelection(val icon: LauncherIcon, val shape: LauncherIconShape) {
    init {
        require(shape != LauncherIconShape.SYSTEM || icon == LauncherIcon.DEFAULT)
    }

    val alias: String
        get() = when (shape) {
            LauncherIconShape.SYSTEM -> icon.alias
            // Preserve the component name already shipped for each custom design.
            LauncherIconShape.NORMAL -> if (icon == LauncherIcon.DEFAULT) "${icon.alias}Normal" else icon.alias
            LauncherIconShape.ROUND -> "${icon.alias}Round"
        }

    companion object {
        val DEFAULT = LauncherIconSelection(LauncherIcon.DEFAULT, LauncherIconShape.SYSTEM)
        val options = listOf(DEFAULT) + LauncherIcon.entries.flatMap { icon ->
            listOf(
                LauncherIconSelection(icon, LauncherIconShape.NORMAL),
                LauncherIconSelection(icon, LauncherIconShape.ROUND),
            )
        }
    }
}

internal class LauncherIconController(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val activityPackage = MainActivity::class.java.name.substringBeforeLast('.')

    fun current(): LauncherIconSelection = synchronized(switchLock) {
        val states = readStates()
        val enabled = states.filter { (icon, state) ->
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (icon == LauncherIconSelection.DEFAULT && state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        }.keys
        val selected = enabled.firstOrNull { it != LauncherIconSelection.DEFAULT } ?: LauncherIconSelection.DEFAULT
        // An interrupted switch on Android 12 or earlier can leave both entries enabled.
        if (enabled.size != 1) updateStates(selected, states)
        selected
    }

    fun switchTo(icon: LauncherIconSelection): LauncherIconSelection = synchronized(switchLock) {
        updateStates(icon, readStates())
        icon
    }

    private fun readStates(): Map<LauncherIconSelection, Int> = LauncherIconSelection.options.associateWith {
        packageManager.getComponentEnabledSetting(component(it))
    }

    private fun updateStates(selected: LauncherIconSelection, previous: Map<LauncherIconSelection, Int>) {
        // On older Android versions the chosen entry must be enabled before retiring the old one.
        val orderedIcons = listOf(selected) + LauncherIconSelection.options.filter { it != selected }
        val changes = orderedIcons.mapNotNull { icon ->
            val state = when {
                icon == LauncherIconSelection.DEFAULT && icon != selected -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                icon != LauncherIconSelection.DEFAULT && icon == selected -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            }
            if (previous.getValue(icon) == state) null else icon to state
        }
        if (changes.isEmpty()) return

        val flags = PackageManager.DONT_KILL_APP
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.setComponentEnabledSettings(changes.map { (icon, state) ->
                PackageManager.ComponentEnabledSetting(component(icon), state, flags)
            })
            return
        }

        val applied = mutableListOf<LauncherIconSelection>()
        try {
            for ((icon, state) in changes) {
                packageManager.setComponentEnabledSetting(component(icon), state, flags)
                applied += icon
            }
        } catch (failure: RuntimeException) {
            for (icon in applied.asReversed()) {
                try {
                    packageManager.setComponentEnabledSetting(component(icon), previous.getValue(icon), flags)
                } catch (rollbackFailure: RuntimeException) {
                    failure.addSuppressed(rollbackFailure)
                }
            }
            throw failure
        }
    }

    private fun component(icon: LauncherIconSelection) =
        ComponentName(appContext.packageName, "$activityPackage.${icon.alias}")

    private companion object {
        val switchLock = Any()
    }
}


/** Resolve the changing launcher alias before giving the stable app activity its own task. */
class LauncherIconEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val main = Intent(Intent.ACTION_MAIN, null, this, MainActivity::class.java)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            startActivity(main)
        } finally {
            finish()
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal fun rasterizeLauncherIconBitmap(
    drawable: Drawable,
    size: Int = 224,
    round: Boolean = false,
    clipToMask: Boolean = false,
): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    if (clipToMask) {
        val path = Path()
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        if (round) {
            path.addOval(rect, Path.Direction.CW)
        } else {
            val radius = size * 0.22f
            path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        }
        canvas.clipPath(path)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && drawable is AdaptiveIconDrawable) {
        // The layers contain a 108 dp canvas. 72 dp is visible inside the system mask.
        val inset = size / 4
        val layers = listOfNotNull(drawable.background, drawable.foreground)
        for (layer in layers) {
            layer.setBounds(-inset, -inset, size + inset, size + inset)
            layer.draw(canvas)
        }
    } else {
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
    }
    return bitmap
}

internal fun prepareLauncherTaskBitmap(context: Context, selection: LauncherIconSelection): Bitmap? {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val iconSize = (am?.launcherLargeIconSize ?: 192).coerceIn(96, 256)
    val resId = if (selection.shape == LauncherIconShape.ROUND) selection.icon.roundIconRes else selection.icon.iconRes
    val drawable = ContextCompat.getDrawable(context, resId) ?: return null
    return rasterizeLauncherIconBitmap(
        drawable = drawable,
        size = iconSize,
        round = selection.shape == LauncherIconShape.ROUND,
        clipToMask = true,
    )
}

@Suppress("DEPRECATION")
internal fun applyLauncherTaskAppearance(activity: Activity, bitmap: Bitmap?) {
    if (bitmap == null || activity.isDestroyed) return
    val label = activity.getString(R.string.app_name)
    activity.setTaskDescription(ActivityManager.TaskDescription(label, bitmap))
}
