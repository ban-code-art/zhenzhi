package com.example.myapplication.data.knowledge.index

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Device-local choice for vectors created by future indexing batches; default is FLOAT32. */
interface VectorEncodingPreference {
    fun observe(userId: Long): Flow<String>
    suspend fun get(userId: Long): String
    suspend fun set(userId: Long, encoding: String): Boolean

    /** Encoding currently used by retrieval for this provider profile. */
    fun observeActive(userId: Long, providerFingerprint: String): Flow<String>
    suspend fun active(userId: Long, providerFingerprint: String): String
    suspend fun activate(userId: Long, providerFingerprint: String, encoding: String): Boolean

    suspend fun desiredIndexFingerprint(userId: Long, providerFingerprint: String): String =
        VectorIndexProfile.of(providerFingerprint, get(userId))

    suspend fun activeIndexFingerprint(userId: Long, providerFingerprint: String): String =
        VectorIndexProfile.of(providerFingerprint, active(userId, providerFingerprint))
}

class DataStoreVectorEncodingPreference(private val context: Context) : VectorEncodingPreference {
    override fun observe(userId: Long): Flow<String> =
        context.vectorEncodingDataStore.data.map { prefs ->
            normalize(prefs[key(userId)])
        }

    override suspend fun get(userId: Long): String =
        normalize(context.vectorEncodingDataStore.data.first()[key(userId)])

    override suspend fun set(userId: Long, encoding: String): Boolean {
        if (userId <= 0L || encoding !in SUPPORTED) return false
        context.vectorEncodingDataStore.edit { prefs ->
            if (encoding == VectorCodec.ENCODING_FLOAT32) prefs.remove(key(userId))
            else prefs[key(userId)] = encoding
        }
        return true
    }

    override fun observeActive(userId: Long, providerFingerprint: String): Flow<String> {
        if (userId <= 0L || providerFingerprint.isBlank()) return kotlinx.coroutines.flow.flowOf(VectorCodec.ENCODING_FLOAT32)
        return context.vectorEncodingDataStore.data.map { prefs ->
            normalize(prefs[activeKey(userId, providerFingerprint)])
        }
    }

    override suspend fun active(userId: Long, providerFingerprint: String): String {
        if (userId <= 0L || providerFingerprint.isBlank()) return VectorCodec.ENCODING_FLOAT32
        return normalize(context.vectorEncodingDataStore.data.first()[activeKey(userId, providerFingerprint)])
    }

    override suspend fun activate(userId: Long, providerFingerprint: String, encoding: String): Boolean {
        if (userId <= 0L || providerFingerprint.isBlank() || encoding !in SUPPORTED) return false
        context.vectorEncodingDataStore.edit { prefs ->
            val key = activeKey(userId, providerFingerprint)
            if (encoding == VectorCodec.ENCODING_FLOAT32) prefs.remove(key)
            else prefs[key] = encoding
        }
        return true
    }

    private fun normalize(value: String?): String = value?.takeIf { it in SUPPORTED } ?: VectorCodec.ENCODING_FLOAT32
    private fun key(userId: Long) = stringPreferencesKey("encoding_user_$userId")
    private fun activeKey(userId: Long, providerFingerprint: String) =
        stringPreferencesKey("active_${userId}_${sha256(providerFingerprint)}")

    private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        private val SUPPORTED = setOf(
            VectorCodec.ENCODING_FLOAT32,
            VectorCodec.ENCODING_FLOAT16,
            VectorCodec.ENCODING_INT8,
        )
    }
}

private val Context.vectorEncodingDataStore by preferencesDataStore(name = "vector_encoding")
