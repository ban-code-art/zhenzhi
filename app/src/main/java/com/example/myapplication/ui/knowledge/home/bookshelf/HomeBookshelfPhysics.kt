package com.example.myapplication.ui.knowledge.home.bookshelf

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sign

private const val Friction = 8f
private const val SpringFrequency = 20f
private const val FlingThreshold = 2.5f
private const val VelocityLimit = 18f
private const val MaxCoastTime = .22f
internal const val ShelfMaxMotionTime = .75f

internal data class ShelfMotionSample(val q: Float, val velocity: Float)

internal fun nearestShelfIndex(q: Float, count: Int, direction: Int = 0, selected: Int? = null): Int {
    val floor = floor(q).toInt()
    val fraction = q - floor
    val nearest = when {
        fraction < .5f -> floor
        fraction > .5f -> floor + 1
        direction > 0 -> floor + 1
        direction < 0 -> floor
        selected == floor + 1 -> floor + 1
        else -> floor
    }
    return nearest.coerceIn(0, (count - 1).coerceAtLeast(0))
}

internal class ShelfMotionPlan private constructor(
    private val initial: ShelfMotionSample,
    val target: Int,
    val coastTime: Float,
    private val maxQ: Float,
) {
    private val handoff = coast(coastTime)
    private val springEdge = springEdge()

    fun sample(seconds: Float): ShelfMotionSample {
        if (seconds < coastTime) return coast(seconds)
        val time = seconds - coastTime
        val edge = springEdge
        return if (edge != null && time >= edge.time) {
            spring(ShelfMotionSample(edge.q, 0f), time - edge.time)
        } else {
            spring(handoff, time)
        }
    }

    fun settled(sample: ShelfMotionSample, pitch: Float): Boolean {
        val positionTolerance = minOf(.01f, .75f / pitch)
        return abs(sample.q - target) <= positionTolerance && abs(sample.velocity) <= .12f
    }

    private fun coast(t: Float): ShelfMotionSample {
        val decay = exp(-Friction * t)
        return ShelfMotionSample(initial.q + (initial.velocity / Friction) * (1f - decay), initial.velocity * decay)
    }

    private fun spring(start: ShelfMotionSample, t: Float): ShelfMotionSample {
        val error = start.q - target
        val b = start.velocity + SpringFrequency * error
        val decay = exp(-SpringFrequency * t)
        return ShelfMotionSample(
            q = (target + (error + b * t) * decay).coerceIn(0f, maxQ),
            velocity = (start.velocity - SpringFrequency * b * t) * decay,
        )
    }

    private fun springEdge(): Edge? {
        val velocity = handoff.velocity
        if (velocity == 0f) return null
        val boundary = if (velocity > 0f) maxQ else 0f
        if (handoff.q == boundary) return Edge(0f, boundary)
        val b = velocity + SpringFrequency * (handoff.q - target)
        if (b * velocity <= 0f) return null
        val turn = velocity / (SpringFrequency * b)
        if (spring(handoff, turn).q != boundary) return null
        var low = 0f
        var high = turn
        repeat(24) {
            val mid = (low + high) * .5f
            if (spring(handoff, mid).q == boundary) high = mid else low = mid
        }
        return Edge(high, boundary)
    }

    private data class Edge(val time: Float, val q: Float)

    companion object {
        fun toTarget(q: Float, velocity: Float, target: Int, count: Int): ShelfMotionPlan {
            val maxIndex = (count - 1).coerceAtLeast(0)
            return ShelfMotionPlan(
                ShelfMotionSample(q.coerceIn(0f, maxIndex.toFloat()), if (count <= 1) 0f else velocity),
                target.coerceIn(0, maxIndex),
                0f,
                maxIndex.toFloat(),
            )
        }

        fun release(q: Float, velocity: Float, count: Int, tieDirection: Int, selected: Int?): ShelfMotionPlan {
            val v = velocity.coerceIn(-VelocityLimit, VelocityLimit)
            val direction = if (v != 0f) v.sign.toInt() else tieDirection
            if (count <= 1 || abs(v) < FlingThreshold) {
                return toTarget(q, v, nearestShelfIndex(q, count, direction, selected), count)
            }

            val predicted = nearestShelfIndex(q + v / Friction, count, direction)
            val speed = abs(v)
            val runway = speed / SpringFrequency + .08f
            val target = (if (v > 0f) {
                maxOf(predicted, ceil(q + runway).toInt())
            } else {
                minOf(predicted, floor(q - runway).toInt())
            }).coerceIn(0, count - 1)

            val distance = (target - q) * direction
            val frictionDistance = speed / Friction
            val minSpringDistance = speed / SpringFrequency
            val reserve = .03f
            val limit = when {
                distance - frictionDistance >= reserve -> Float.POSITIVE_INFINITY
                distance - minSpringDistance <= reserve -> 0f
                else -> {
                    val ratio = (reserve - distance + frictionDistance) / (frictionDistance - minSpringDistance)
                    if (ratio in 0f..1f && ratio > 0f) -ln(ratio) / Friction else 0f
                }
            }
            val timeAtSpeed = (ln(speed / 3f) / Friction).coerceAtLeast(0f)
            return ShelfMotionPlan(
                ShelfMotionSample(q, v),
                target,
                minOf(timeAtSpeed, limit, MaxCoastTime),
                (count - 1).toFloat(),
            )
        }
    }
}
