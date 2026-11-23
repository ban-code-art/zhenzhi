package com.example.myapplication.ui.ai

import kotlin.math.*

// ArcMD 3bb4a334 / qabotmotion.go, MIT. The QA avatar uses the idle rig,
// including live gaze, pointer following, blinks, jelly and poked/automatic flips.
// Working/sleeping modes are not used by ArcMD's QA avatar.
internal class GhostMotion(seed: Double = .32) {
    data class Pose(
        var yaw: Double = 0.0, var pitch: Double = 0.0, var roll: Double = 0.0,
        var y: Double = 0.0, var sx: Double = 1.0, var sy: Double = 1.0,
        var blink: Double = 0.0, var lookX: Double = 0.0, var lookY: Double = 0.0,
    )
    val pose = Pose()
    private var random = ((floor(seed * 1e6).toLong() + 1) * 0x9e3779b1L).toInt().let { if (it == 0) 1 else it }
    private fun rand(): Double {
        random += 0x6d2b79f5
        var n = (random xor (random ushr 15)) * (random or 1)
        n = n xor (n + (n xor (n ushr 7)) * (n or 61))
        return (n xor (n ushr 14)).toUInt().toLong() / 4294967296.0
    }
    private class Wander(val amp: Double, val lo: Double, val hi: Double, val rate: Double, val spring: Boolean = false) {
        var value = 0.0; var velocity = 0.0; var target = 0.0; var next = 0.0
        fun aim(v: Double) { target = v; next = Double.POSITIVE_INFINITY }
        fun update(time: Double, dt: Double, rand: () -> Double) {
            if (time >= next) { target = (rand() * 2 - 1) * amp; next = time + lo + rand() * (hi - lo) }
            if (spring) {
                val omega = rate * 1.6
                velocity += (omega * omega * (target - value) - 2 * .9 * omega * velocity) * dt
                value += velocity * dt
            } else value = approach(value, target, rate, dt)
        }
    }
    private class Event(var duration: Double) {
        var p = -1.0
        val active get() = p >= 0
        fun fire() { p = 0.0 }
        fun update(dt: Double) { if (active) { p += dt / duration; if (p >= 1) p = -1.0 } }
    }
    private val yaw = Wander(35 * DEG, 2.6, 5.4, 2.0, true)
    private val pitch = Wander(14 * DEG, 2.8, 5.8, 1.8, true)
    private val roll = Wander(3.2 * DEG, 3.4, 6.6, 1.5, true)
    private val lookX = Wander(3.6, .6, 2.2, 13.0)
    private val lookY = Wander(2.4, .6, 2.2, 13.0)
    private val blink = Event(.17)
    private val dart = Event(.12)
    private val flip = Event(flipDuration(false))
    private var time = rand() * 10
    private val unusedInitialHop = rand() // Preserve upstream's deterministic random sequence.
    private var breathPhase = rand()
    private var blinkAt = time + 1 + rand() * 3
    private var flipAt = nextFlip(time, 1.0)
    private val unusedNodAt = time + 3 + rand() * 4
    private var dartAt = time + 1 + rand() * 2
    private val unusedLaughAt = time + .6 + rand() * 1.5
    private var blinkAgain = false
    private var flipPoked = false
    private var flipSide = 1.0
    private var dartX = 0.0; private var dartY = 0.0
    private var gazeAt = 0.0; private var gazeX = 0.0; private var gazeY = 0.0
    private var ptrX = 0.0; private var ptrY = 0.0; private var ptrS = 0.0
    private var targetX = 0.0; private var targetY = 0.0; private var targetS = 0.0
    private var baseYaw = 0.0; private var previousYaw = 0.0; private var gazeLead = 0.0
    private var jelly = 0.0; private var jellyVelocity = 0.0
    init { flipAt = nextFlip(time, .6) }
    private fun nextFlip(t: Double, k: Double) = t + 8 * k * (.625 + rand() * .75)
    fun pointer(x: Double, y: Double, strength: Double) {
        targetX = x.coerceIn(-1.2, 1.2); targetY = y.coerceIn(-1.2, 1.2); targetS = strength.coerceIn(0.0, 1.0)
    }
    fun poke() {
        if (flip.active && flip.p < .6) return
        flipPoked = true; flip.duration = flipDuration(true)
        flipSide = if (rand() < .5) -1.0 else 1.0
        flip.fire(); flipAt = nextFlip(time, 1.1)
    }
    fun update(elapsed: Double) {
        val dt = elapsed.coerceIn(0.0, .05)
        time += dt
        if (time >= gazeAt) {
            if (gazeX != 0.0 || gazeY != 0.0) {
                val r = rand()
                when { r < .66 -> { gazeX = -gazeX; gazeY = -gazeY }
                    r < .85 -> gazeX = -gazeX
                    else -> { gazeX = 0.0; gazeY = 0.0 } }
            } else {
                val index = floor(rand() * 4).toInt()
                gazeX = if (index == 0 || index == 3) 1.0 else -1.0
                gazeY = if (index == 0 || index == 2) -1.0 else 1.0
            }
            val reach = .84 + rand() * .16
            yaw.aim(gazeX * 35 * DEG * reach); pitch.aim(gazeY * 14 * DEG * reach)
            roll.aim(gazeX * 3.2 * DEG * reach); gazeAt = time + 2.6 + rand() * 1.8
        }
        yaw.update(time, dt, ::rand); pitch.update(time, dt, ::rand); roll.update(time, dt, ::rand)
        lookX.update(time, dt, ::rand); lookY.update(time, dt, ::rand)
        ptrS = approach(ptrS, targetS, 8.0, dt); ptrX = approach(ptrX, targetX, 14.0, dt); ptrY = approach(ptrY, targetY, 14.0, dt)
        val quiet = 1 - .75 * ptrS
        baseYaw = approach(baseYaw, yaw.value * quiet + 22 * DEG * ptrX * ptrS, 5.0, dt)
        if (time >= blinkAt && !blink.active) {
            blink.fire(); blinkAgain = !blinkAgain && rand() < .22
            blinkAt = time + if (blinkAgain) .28 else 2.2 + rand() * 2.6
        }
        blink.update(dt)
        if (time >= dartAt && !dart.active) {
            dart.fire(); dartX = (rand() * 2 - 1) * 4; dartY = (rand() * 2 - 1) * 2
            dart.duration = .25 + rand() * .45; dartAt = time + 1.2 + rand() * 2.6
        }
        dart.update(dt)
        val dartHold = if (!dart.active) 0.0 else when { dart.p < .15 -> dart.p / .15; dart.p > .8 -> (1 - dart.p) / .2; else -> 1.0 }
        if (time >= flipAt && !flip.active) {
            flipPoked = false; flip.duration = flipDuration(false)
            flipSide = if (rand() < .5) -1.0 else 1.0
            flip.fire(); flipAt = nextFlip(time, 1.0)
        }
        flip.update(dt)
        var spin = 0.0; var hop = 0.0; var sx = 1.0; var sy = 1.0; var tilt = 0.0
        if (flip.active) {
            val pre = if (flipPoked) .24 else .2 * .68
            val a = (flip.p * flip.duration - pre) / .68
            val q = a.coerceIn(0.0, 1.0); val arc = sin(PI * q)
            spin = TAU * ease(q); hop = -26 * arc
            val tl = (a - 1) * .68; val squashTime = if (flipPoked) .24 else .37
            val peak = 2.0 / 7 * squashTime; val rise = tl - peak - .11
            val depth = when { tl <= peak -> squash(tl / squashTime); rise <= 0 -> 1 + .25 * squash((tl - peak) / .11); else -> rise(rise / .33) }
            val land = 1.15 * when {
                a < 0 -> { val u = max(0.0, 1 + a * .68 / pre); if (flipPoked) u * u * (3 - 2 * u) else hopSquash(u - 1) }
                tl > 0 -> depth
                a < .2 -> hopSquash(a)
                else -> 0.0
            }
            sx += .16 * land - .06 * arc; sy += -.18 * land + .09 * arc
            tilt = flipSide * 6 * DEG * arc
        }
        breathPhase += dt / 3.6
        val breath = sin(breathPhase * TAU)
        sx += breath * .008; sy += breath * .012
        val dyaw = ((baseYaw - previousYaw + PI) % TAU + TAU) % TAU - PI
        previousYaw = baseYaw
        val rate = if (dt > 0) abs(dyaw) / dt else 0.0
        gazeLead = approach(gazeLead, if (dt > 0) (dyaw / dt * 2.4).coerceIn(-2.2, 2.2) else 0.0, 9.0, dt)
        jellyVelocity += (256 * (min(.22, .055 * rate) - jelly) - 2 * .45 * 16 * jellyVelocity) * dt
        jelly += jellyVelocity * dt
        val j = jelly.coerceIn(-.08, .28) * .6
        pose.apply {
            this.yaw = baseYaw + spin; this.pitch = this@GhostMotion.pitch.value * quiet - 12 * DEG * ptrY * ptrS
            this.roll = this@GhostMotion.roll.value * quiet + tilt
            y = hop + sin(time * TAU / 3.4) * 2; this.sx = sx * (1 + j); this.sy = sy * (1 - .55 * j)
            blink = if (this@GhostMotion.blink.active) sin(PI * this@GhostMotion.blink.p) else 0.0
            lookX = this@GhostMotion.lookX.value * quiet + 4.5 * ptrX * ptrS + dartX * dartHold + gazeLead
            lookY = this@GhostMotion.lookY.value * quiet + 3 * ptrY * ptrS + dartY * dartHold
        }
    }
    private companion object {
        const val DEG = PI / 180; const val TAU = PI * 2
        fun approach(a: Double, b: Double, rate: Double, dt: Double) = a + (b - a) * (1 - exp(-rate * dt))
        fun ease(p: Double) = if (p < .5) 4 * p * p * p else 1 - (-2 * p + 2).pow(3) / 2
        fun hopSquash(a: Double) = exp(-(min(abs(a), abs(a - 1)) / .11).pow(2))
        fun squash(u: Double): Double {
            if (u <= 0 || u >= 1) return 0.0
            val k = 7 * u; val tail = if (u > .85) 1 - (u - .85) / .15 else 1.0
            return k * k * exp(2 - k) / 4 * tail * tail * (3 - 2 * tail)
        }
        fun rise(v: Double): Double = when { v <= 0 -> 1.0; v >= 1 -> 0.0; else -> (1 + 4.2 * v) * exp(-4.2 * v) - v * v * v * .078 }
        fun flipDuration(poked: Boolean) = (if (poked) .24 else .2 * .68) + .68 + 2.0 / 7 * (if (poked) .24 else .37) + .11 + .33 + .05
    }
}
