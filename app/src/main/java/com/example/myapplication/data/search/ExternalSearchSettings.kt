package com.example.myapplication.data.search

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.net.URI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface ExternalSearchSettings {
    fun observeEndpoint(): Flow<String?>
    suspend fun endpoint(): String?
    suspend fun setEndpoint(value: String?): Boolean
}

class DataStoreExternalSearchSettings(private val context: Context) : ExternalSearchSettings {
    override fun observeEndpoint(): Flow<String?> =
        context.externalSearchDataStore.data.map { validateEndpoint(it[KEY_ENDPOINT]) }

    override suspend fun endpoint(): String? =
        validateEndpoint(context.externalSearchDataStore.data.first()[KEY_ENDPOINT])

    override suspend fun setEndpoint(value: String?): Boolean {
        val validated = validateEndpoint(value)
        if (!value.isNullOrBlank() && validated == null) return false
        context.externalSearchDataStore.edit { prefs ->
            if (validated == null) prefs.remove(KEY_ENDPOINT) else prefs[KEY_ENDPOINT] = validated
        }
        return true
    }

    companion object {
        private val KEY_ENDPOINT = stringPreferencesKey("searxng_endpoint")

        fun validateEndpoint(raw: String?): String? {
            val value = raw?.trim()?.trimEnd('/')?.takeIf(String::isNotBlank) ?: return null
            if (value.length > MAX_ENDPOINT_CHARS) return null
            return runCatching {
                val uri = URI(value)
                val scheme = uri.scheme?.lowercase()
                if (scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
                if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
                val path = uri.rawPath.orEmpty().ifBlank { "/search" }
                if (!path.endsWith("/search")) return null
                URI(scheme, null, uri.host, uri.port, path, null, null).toASCIIString()
            }.getOrNull()
        }

        private const val MAX_ENDPOINT_CHARS = 512
    }
}

private val Context.externalSearchDataStore by preferencesDataStore(name = "external_search")
