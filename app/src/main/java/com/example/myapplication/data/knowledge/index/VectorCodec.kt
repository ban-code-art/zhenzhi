package com.example.myapplication.data.knowledge.index

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Vector BLOB codec. FLOAT32 remains the default; FLOAT16 and symmetric INT8 are explicit opt-ins.
 * Decoding always returns validated Float32 values so retrieval has one scoring implementation.
 */
object VectorCodec {
    const val ENCODING_FLOAT32 = "FLOAT32"
    const val ENCODING_FLOAT16 = "FLOAT16"
    const val ENCODING_INT8 = "INT8_SYMMETRIC"

    data class Encoded(
        val vectorBlob: ByteArray,
        val dim: Int,
        val encoding: String,
        val norm: Double,
        val quantizationParams: String? = null,
    )

    sealed interface Decoded {
        data class Ok(val vector: FloatArray, val norm: Double) : Decoded
        data class Invalid(val reason: String) : Decoded
    }

    fun encodeResult(vector: FloatArray, encoding: String = ENCODING_FLOAT32): Encoded? {
        if (vector.isEmpty() || vector.any { !it.isFinite() }) return null
        val sourceNorm = normOf(vector)
        if (sourceNorm <= NORM_EPSILON || !sourceNorm.isFinite()) return null
        return when (encoding) {
            ENCODING_FLOAT32 -> {
                val blob = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                    vector.forEach(::putFloat)
                }.array()
                Encoded(blob, vector.size, encoding, sourceNorm)
            }
            ENCODING_FLOAT16 -> {
                val blob = ByteBuffer.allocate(vector.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                    vector.forEach { putShort(floatToHalf(it).toShort()) }
                }.array()
                val decoded = decode(blob, vector.size, encoding)
                if (decoded !is Decoded.Ok) null else Encoded(blob, vector.size, encoding, decoded.norm)
            }
            ENCODING_INT8 -> {
                val maxAbs = vector.maxOf { kotlin.math.abs(it.toDouble()) }
                if (maxAbs <= NORM_EPSILON || !maxAbs.isFinite()) return null
                val scale = (maxAbs / 127.0).toFloat()
                if (scale <= 0f || !scale.isFinite()) return null
                val blob = ByteArray(vector.size) { index ->
                    (vector[index] / scale).roundToInt().coerceIn(-127, 127).toByte()
                }
                val params = """{"scheme":"SYMMETRIC_INT8_V1","scale":$scale}"""
                val decoded = decode(blob, vector.size, encoding, quantizationParams = params)
                if (decoded !is Decoded.Ok) null else Encoded(blob, vector.size, encoding, decoded.norm, params)
            }
            else -> null
        }
    }

    /** Compatibility helper for FLOAT32 callers and tests. */
    fun encode(vector: FloatArray, encoding: String = ENCODING_FLOAT32): ByteArray? =
        encodeResult(vector, encoding)?.vectorBlob

    fun decode(
        blob: ByteArray,
        dim: Int,
        encoding: String = ENCODING_FLOAT32,
        expectedDim: Int? = null,
        quantizationParams: String? = null,
    ): Decoded {
        if (dim <= 0) return Decoded.Invalid("dim must be positive")
        if (expectedDim != null && dim != expectedDim) return Decoded.Invalid("dimension mismatch")
        val vector = when (encoding) {
            ENCODING_FLOAT32 -> {
                if (quantizationParams != null) return Decoded.Invalid("unexpected quantization parameters")
                if (blob.size != dim * 4) return Decoded.Invalid("blob length mismatch")
                val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
                FloatArray(dim) {
                    buffer.float.also { if (!it.isFinite()) return Decoded.Invalid("vector contains NaN/Inf") }
                }
            }
            ENCODING_FLOAT16 -> {
                if (quantizationParams != null) return Decoded.Invalid("unexpected quantization parameters")
                if (blob.size != dim * 2) return Decoded.Invalid("blob length mismatch")
                val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
                FloatArray(dim) {
                    halfToFloat(buffer.short.toInt() and 0xFFFF).also {
                        if (!it.isFinite()) return Decoded.Invalid("vector contains NaN/Inf")
                    }
                }
            }
            ENCODING_INT8 -> {
                if (blob.size != dim) return Decoded.Invalid("blob length mismatch")
                val scale = parseInt8Scale(quantizationParams)
                    ?: return Decoded.Invalid("invalid quantization parameters")
                FloatArray(dim) { index -> blob[index].toInt().toFloat() * scale }
            }
            else -> return Decoded.Invalid("unsupported encoding: $encoding")
        }
        if (vector.any { !it.isFinite() }) return Decoded.Invalid("vector contains NaN/Inf")
        val norm = normOf(vector)
        if (!norm.isFinite() || norm <= NORM_EPSILON) return Decoded.Invalid("zero-norm vector")
        return Decoded.Ok(vector, norm)
    }

    fun normOf(vector: FloatArray): Double {
        var sum = 0.0
        vector.forEach { sum += it.toDouble() * it.toDouble() }
        return kotlin.math.sqrt(sum)
    }

    fun cosine(a: FloatArray, b: FloatArray): Double? {
        if (a.size != b.size || a.isEmpty()) return null
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            normA += x * x
            normB += y * y
        }
        if (normA <= NORM_EPSILON || normB <= NORM_EPSILON) return null
        return dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))
    }

    private fun parseInt8Scale(params: String?): Float? {
        if (params.isNullOrBlank()) return null
        val root = runCatching { Json.parseToJsonElement(params) as? JsonObject }.getOrNull() ?: return null
        if (root.keys != setOf("scheme", "scale")) return null
        val scheme = runCatching { root["scheme"]?.jsonPrimitive?.content }.getOrNull()
        val scale = runCatching { root["scale"]?.jsonPrimitive?.doubleOrNull }.getOrNull()
        if (scheme != "SYMMETRIC_INT8_V1" || scale == null || !scale.isFinite() || scale <= 0.0) return null
        val asFloat = scale.toFloat()
        return asFloat.takeIf { it.isFinite() && it > 0f }
    }

    private fun Float.roundToInt(): Int = kotlin.math.round(this).toInt()

    /** IEEE-754 binary32 to binary16, round-to-nearest-even. */
    private fun floatToHalf(value: Float): Int {
        val bits = java.lang.Float.floatToRawIntBits(value)
        val sign = (bits ushr 16) and 0x8000
        var exponent = ((bits ushr 23) and 0xFF) - 127 + 15
        val mantissa = bits and 0x7FFFFF
        if (exponent <= 0) {
            if (exponent < -10) return sign
            val fullMantissa = mantissa or 0x800000
            val shift = 14 - exponent
            var halfMantissa = fullMantissa ushr shift
            val remainder = fullMantissa and ((1 shl shift) - 1)
            val halfway = 1 shl (shift - 1)
            if (remainder > halfway || (remainder == halfway && halfMantissa and 1 != 0)) halfMantissa += 1
            return sign or halfMantissa
        }
        if (exponent >= 31) return sign or 0x7C00
        var halfMantissa = mantissa ushr 13
        val remainder = mantissa and 0x1FFF
        if (remainder > 0x1000 || (remainder == 0x1000 && halfMantissa and 1 != 0)) {
            halfMantissa += 1
            if (halfMantissa == 0x400) {
                halfMantissa = 0
                exponent += 1
                if (exponent >= 31) return sign or 0x7C00
            }
        }
        return sign or (exponent shl 10) or halfMantissa
    }

    /** IEEE-754 binary16 to binary32. */
    private fun halfToFloat(half: Int): Float {
        val sign = (half and 0x8000) shl 16
        val exponent = (half ushr 10) and 0x1F
        val mantissa = half and 0x03FF
        val bits = when (exponent) {
            0 -> {
                if (mantissa == 0) sign else {
                    var normalizedMantissa = mantissa
                    var normalizedExponent = -14
                    while ((normalizedMantissa and 0x400) == 0) {
                        normalizedMantissa = normalizedMantissa shl 1
                        normalizedExponent -= 1
                    }
                    normalizedMantissa = normalizedMantissa and 0x3FF
                    sign or ((normalizedExponent + 127) shl 23) or (normalizedMantissa shl 13)
                }
            }
            0x1F -> sign or 0x7F800000 or (mantissa shl 13)
            else -> sign or ((exponent + 112) shl 23) or (mantissa shl 13)
        }
        return java.lang.Float.intBitsToFloat(bits)
    }

    const val NORM_EPSILON = 1e-9
}
