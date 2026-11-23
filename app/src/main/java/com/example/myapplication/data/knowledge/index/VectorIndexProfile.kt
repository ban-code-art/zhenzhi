package com.example.myapplication.data.knowledge.index

/**
 * Separates the provider embedding profile from an on-disk vector representation.
 * FLOAT32 keeps the historical key so existing v6 rows remain readable.
 */
object VectorIndexProfile {
    private const val MARKER = "|vector-encoding="

    data class Identity(val providerFingerprint: String, val encoding: String)

    fun of(providerFingerprint: String, encoding: String): String {
        require(providerFingerprint.isNotBlank()) { "provider fingerprint must not be blank" }
        require(encoding in SUPPORTED_ENCODINGS) { "unsupported vector encoding: $encoding" }
        return if (encoding == VectorCodec.ENCODING_FLOAT32) {
            providerFingerprint
        } else {
            "$providerFingerprint$MARKER$encoding"
        }
    }

    fun parse(indexFingerprint: String): Identity? {
        if (indexFingerprint.isBlank()) return null
        val markerAt = indexFingerprint.lastIndexOf(MARKER)
        if (markerAt < 0) return Identity(indexFingerprint, VectorCodec.ENCODING_FLOAT32)
        val providerFingerprint = indexFingerprint.substring(0, markerAt)
        val encoding = indexFingerprint.substring(markerAt + MARKER.length)
        if (providerFingerprint.isBlank() || encoding !in SUPPORTED_ENCODINGS) return null
        return Identity(providerFingerprint, encoding)
    }

    private val SUPPORTED_ENCODINGS = setOf(
        VectorCodec.ENCODING_FLOAT32,
        VectorCodec.ENCODING_FLOAT16,
        VectorCodec.ENCODING_INT8,
    )
}
