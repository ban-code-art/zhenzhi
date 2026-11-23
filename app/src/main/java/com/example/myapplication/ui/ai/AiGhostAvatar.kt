package com.example.myapplication.ui.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.example.myapplication.ui.navigation.LocalNavigationPageActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.lang.ref.SoftReference
import kotlin.math.*

/** ArcMD's live idle rig. Bitmap decoding never runs on the draw/UI thread. */
@Composable
internal fun AiGhostAvatar(running: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val view = LocalView.current
    val motion = remember { GhostMotion() }
    val painter = remember { GhostPainter() }
    var tick by remember { mutableIntStateOf(0) }
    var onScreen by remember { mutableStateOf(true) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = running && onScreen && LocalNavigationPageActive.current && lifecycle == Lifecycle.State.RESUMED
    var animate by remember { mutableStateOf(false) }
    val materials by produceState<List<Bitmap>?>(null, context) {
        value = withContext(Dispatchers.IO) { GhostMaterials.load(context) }
    }
    LaunchedEffect(active, materials != null) {
        animate = active && currentCoroutineContext()[MotionDurationScale]?.scaleFactor != 0f
        if (!animate || materials == null) return@LaunchedEffect
        var last = 0L
        while (isActive) withInfiniteAnimationFrameNanos { now ->
            if (last != 0L) motion.update((now - last) / 1e9)
            last = now
            tick++ // Observed only in draw; no per-frame message-list composition/layout.
        }
    }
    Canvas(modifier.size(40.dp).testTag("ai-ghost-avatar").onGloballyPositioned {
        val bounds = it.boundsInWindow()
        onScreen = bounds.width > 0 && bounds.height > 0 && bounds.right > 0 && bounds.bottom > 0 &&
            bounds.left < view.width && bounds.top < view.height
    }
        .semantics { role = Role.Button; contentDescription = "小幽灵，点击跳跃"
            onClick { if (animate) motion.poke(); true } }
        .pointerInput(animate) {
            if (animate) detectTapGestures(onPress = { at ->
                motion.pointer((at.x / size.width - .5) * 2, (at.y / size.height - .5) * 2, 1.0)
                try { tryAwaitRelease() } finally { motion.pointer(0.0, 0.0, 0.0) }
            }, onTap = { motion.poke() })
        }) {
        tick
        val sheets = materials ?: return@Canvas
        drawIntoCanvas { painter.draw(it.nativeCanvas, sheets, if (animate) motion.pose else GhostMotion.Pose(), size.width) }
    }
}

private object GhostMaterials {
    private var cached = SoftReference<List<Bitmap>>(null)
    @Synchronized fun load(context: android.content.Context): List<Bitmap> = cached.get() ?: (0..15).map { index ->
        context.assets.open("ai-ghost/pitch%02d.png".format(java.util.Locale.ROOT, index)).use {
            requireNotNull(BitmapFactory.decodeStream(it)) { "Missing ghost material $index" }
        }
    }.also { cached = SoftReference(it) }
}

// Material interpolation and spherical eye projection from ArcMD qabotpaint.go (MIT).
private class GhostPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val add = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private val src = Rect()
    private val tile = RectF(0f, 0f, 80f, 80f)
    private val path = Path()
    private val eye = Path()
    fun draw(canvas: Canvas, sheets: List<Bitmap>, p: GhostMotion.Pose, width: Float) {
        canvas.save()
        canvas.scale(width / 40f, width / 40f)
        canvas.translate(20f, 20f)
        canvas.scale(.8f, .8f)
        canvas.translate(0f, (p.y * .32).toFloat())
        canvas.rotate((p.roll * 180 / PI).toFloat())
        canvas.translate(0f, (16 * (1 - p.sy)).toFloat())
        canvas.scale(p.sx.toFloat(), p.sy.toFloat())
        canvas.translate(-20f, -23.2f)
        canvas.scale(.5f, .5f)
        val yaw = ((p.yaw * 180 / PI % 360 + 360) % 360) / 5
        val pitch = ((p.pitch * 180 / PI + 45) / 5).coerceIn(0.0, 15.0)
        val x = yaw.toInt(); val y = pitch.toInt(); val fx = yaw - x; val fy = pitch - y
        canvas.saveLayer(tile, null)
        paint.xfermode = add
        for (dy in 0..1) for (dx in 0..1) {
            val column = (x + dx) % 72
            val weight = (if (dx == 0) 1 - fx else fx) * (if (dy == 0) 1 - fy else fy)
            if (weight <= .001) continue
            src.set(column % 18 * 80, column / 18 * 80, column % 18 * 80 + 80, column / 18 * 80 + 80)
            paint.alpha = (255 * weight).roundToInt()
            canvas.drawBitmap(sheets[min(y + dy, 15)], src, tile, paint)
        }
        paint.xfermode = null; paint.alpha = 255
        canvas.restore()
        face(canvas, p)
        canvas.restore()
    }
    private fun face(c: Canvas, p: GhostMotion.Pose) {
        var cy = cos(p.yaw); val sy = sin(p.yaw); var cp = cos(p.pitch); val sp = sin(p.pitch)
        val facing = cy * cp
        if (facing <= -.2) return
        if (abs(cy) < .22) cy = Math.copySign(.22, cy)
        if (abs(cp) < .22) cp = Math.copySign(.22, cp)
        val z = .55 * 25.88276786624261 * if (facing < 0) -1 else 1
        fun x(a: Double) = (40 + .64 * (cy * (a - 50) + sy * z)).toFloat()
        fun y(a: Double, b: Double) = (46.4 + .64 * (sy * sp * (a - 50) + cp * (b - 50) - cy * sp * z)).toFloat()
        path.reset(); path.moveTo(x(17.0), y(17.0, 50.0))
        path.cubicTo(x(17.0), y(17.0,31.78),x(31.78),y(31.78,17.0),x(50.0),y(50.0,17.0))
        path.cubicTo(x(68.22),y(68.22,17.0),x(83.0),y(83.0,31.78),x(83.0),y(83.0,50.0))
        path.lineTo(x(83.0),y(83.0,81.5))
        path.quadTo(x(72.0),y(72.0,93.5),x(61.0),y(61.0,81.5))
        path.quadTo(x(50.0),y(50.0,93.5),x(39.0),y(39.0,81.5))
        path.quadTo(x(28.0),y(28.0,93.5),x(17.0),y(17.0,81.5)); path.close()
        c.save(); c.clipPath(path)
        fun past(v: Double, d: Double) = if (abs(v) <= d) 0.0 else Math.copySign((abs(v) - d) / (1 - d), v)
        val up = past((-p.pitch / .26 - p.lookY / 7).coerceIn(-1.0, 1.0), .34)
        val side = abs(past((p.lookX / 4.5).coerceIn(-1.0,1.0), .4))
        val tall = max(.3,1 + .55 * up - .1 * side); val wide = 1 - .05 * up + .12 * side
        val ko = 1 - p.blink; val ks = p.blink
        val x0 = floor((ko * .01 + ks * 5.4) * 50 + .5) / 50
        val y0 = floor((ko * 1.1 * tall + ks * .6) * 50 + .5) / 50
        val control = floor((ko * -3.3 * tall + ks * .6) * 50 + .5) / 50
        val radius = (ko * 12.6 * wide + ks * 2.8) / 2
        paint.color = android.graphics.Color.rgb(34, 29, 55)
        for (sign in listOf(-1, 1)) {
            val lon = asin(((sign * 12.5 + p.lookX * (ko + .5 * ks)) / 30).coerceIn(-1.0,1.0)) + p.yaw
            val lat = asin((-(1 + p.lookY * (ko + .5 * ks)) / 30).coerceIn(-1.0,1.0)) + p.pitch
            val sx = cos(lon); val syEye = cos(lat); val depth = sx * syEye
            if (depth <= .02) continue
            val cx = 30 * sin(lon) * syEye; val cyEye = -30 * sin(lat)
            c.save(); c.translate((40 + .64 * .95 * cx).toFloat(),(46.4 + .64 * (-2 + .95 * cyEye)).toFloat())
            c.scale((.64 * .95 * max(.02,sx)).toFloat(),(.64 * .95 * max(.02,syEye)).toFloat())
            eye.reset()
            for (i in 0..8) {
                val t = i / 8.0; val mt = 1 - t
                val ex = (mt * mt * -x0 + t * t * x0).toFloat()
                val ey = ((mt * mt + t * t) * y0 + 2 * mt * t * control).toFloat()
                if (i == 0) eye.moveTo(ex,ey) else eye.lineTo(ex,ey)
            }
            paint.alpha = (255 * min(1.0,depth * 5)).roundToInt()
            paint.style = Paint.Style.STROKE; paint.strokeWidth = (radius * 2).toFloat(); paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
            c.drawPath(eye, paint)
            paint.style = Paint.Style.FILL; paint.alpha = 255
            c.restore()
        }
        c.restore()
    }
}
