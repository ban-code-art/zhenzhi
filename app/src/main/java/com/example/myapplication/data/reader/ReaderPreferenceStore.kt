package com.example.myapplication.data.reader

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.ui.knowledge.reader.ReaderSettings
import com.example.myapplication.ui.knowledge.reader.ReaderTheme
import com.example.myapplication.ui.knowledge.reader.ReaderFontColor
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put

private val Context.readerPreferencesDataStore by preferencesDataStore(name = "reader_preferences")

/** Device-local user defaults; document overrides remain in the existing reader repository. */
class ReaderPreferenceStore(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.readerPreferencesDataStore)

    data class ReaderDefaultsEntry(
        val settings: ReaderSettings,
        val hasExplicitDefaults: Boolean,
    )

    fun observeDefaultsState(userId: Long): Flow<ReaderDefaultsEntry> {
        require(userId > 0) { "INVALID_USER_ID" }
        val key = defaultsKey(userId)
        // Storage errors propagate so callers can distinguish an unavailable store from no defaults.
        return dataStore.data.map { preferences ->
            val raw = preferences[key]
            ReaderDefaultsEntry(
                settings = decode(raw),
                hasExplicitDefaults = raw != null,
            )
        }
    }

    fun observeDefaults(userId: Long): Flow<ReaderSettings> =
        observeDefaultsState(userId).map { it.settings }

    suspend fun saveDefaults(userId: Long, settings: ReaderSettings): OperationResult<Unit> {
        if (userId <= 0) return OperationResult.Rejected("INVALID_USER_ID")
        return try {
            dataStore.edit { preferences -> preferences[defaultsKey(userId)] = encode(settings) }
            OperationResult.Ok(Unit, UUID.randomUUID().toString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            OperationResult.Failed("READER_DEFAULTS_SAVE_FAILED", retryable = true)
        }
    }

    suspend fun clearDefaults(userId: Long): OperationResult<Unit> {
        if (userId <= 0) return OperationResult.Rejected("INVALID_USER_ID")
        return try {
            dataStore.edit { preferences -> preferences.remove(defaultsKey(userId)) }
            OperationResult.Ok(Unit, UUID.randomUUID().toString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            OperationResult.Failed("READER_DEFAULTS_CLEAR_FAILED", retryable = true)
        }
    }

    private fun defaultsKey(userId: Long) = stringPreferencesKey("reader_defaults_$userId")

    companion object {
        private val originalDefaults = ReaderSettings()

        /** Missing or invalid fields inherit independently, including sparse document overrides. */
        fun decode(json: String?, defaults: ReaderSettings = ReaderSettings()): ReaderSettings {
            val inherited = sanitize(defaults)
            if (json.isNullOrBlank()) return inherited
            val root = try {
                Json.parseToJsonElement(json) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            } ?: return inherited

            val theme = (root["theme"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.let { name -> ReaderTheme.entries.firstOrNull { it.name == name } }
            return ReaderSettings(
                fontScale = finiteNumber(root["fontScale"])?.coerceIn(0.8f, 1.8f) ?: inherited.fontScale,
                lineHeight = finiteNumber(root["lineHeight"])?.coerceIn(1.2f, 2.2f) ?: inherited.lineHeight,
                theme = theme ?: inherited.theme,
                fontColor = (root["fontColor"] as? JsonPrimitive)?.takeIf { it.isString }
                    ?.content?.let(ReaderFontColor::parse) ?: inherited.fontColor,
                marginDp = (root["marginDp"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.coerceIn(0, 64) ?: inherited.marginDp,
                flowMode = (root["flowMode"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { name ->
                    com.example.myapplication.domain.reader.ReaderFlowMode.entries.firstOrNull { it.name == name }
                } ?: inherited.flowMode,
                edgeTap = boolean(root["edgeTap"]) ?: inherited.edgeTap,
                immersivePreference = boolean(root["immersivePreference"]) ?: inherited.immersivePreference,
                pageIndicator = boolean(root["pageIndicator"]) ?: inherited.pageIndicator,
            )
        }

        fun encode(settings: ReaderSettings): String {
            val normalized = sanitize(settings)
            return buildJsonObject {
                put("schemaVersion", 1)
                put("fontScale", normalized.fontScale)
                put("lineHeight", normalized.lineHeight)
                put("theme", normalized.theme.name)
                put("fontColor", normalized.fontColor.encode())
                put("marginDp", normalized.marginDp); put("flowMode", normalized.flowMode.name)
                put("edgeTap", normalized.edgeTap); put("immersivePreference", normalized.immersivePreference)
                put("pageIndicator", normalized.pageIndicator)
            }.toString()
        }

        fun updateOverrides(documentJson: String?, before: ReaderSettings, after: ReaderSettings): String {
            val root = try { Json.parseToJsonElement(documentJson ?: "{}") as? JsonObject }
                catch (_: IllegalArgumentException) { null }
            val fields = root?.toMutableMap() ?: mutableMapOf()
            val normalized = sanitize(after)
            fields["schemaVersion"] = JsonPrimitive(1)
            if (before.fontScale != normalized.fontScale) fields["fontScale"] = JsonPrimitive(normalized.fontScale)
            if (before.lineHeight != normalized.lineHeight) fields["lineHeight"] = JsonPrimitive(normalized.lineHeight)
            if (before.theme != normalized.theme) fields["theme"] = JsonPrimitive(normalized.theme.name)
            if (before.fontColor != normalized.fontColor) fields["fontColor"] = JsonPrimitive(normalized.fontColor.encode())
            if (before.marginDp != normalized.marginDp) fields["marginDp"] = JsonPrimitive(normalized.marginDp)
            if (before.flowMode != normalized.flowMode) fields["flowMode"] = JsonPrimitive(normalized.flowMode.name)
            if (before.edgeTap != normalized.edgeTap) fields["edgeTap"] = JsonPrimitive(normalized.edgeTap)
            if (before.immersivePreference != normalized.immersivePreference) fields["immersivePreference"] = JsonPrimitive(normalized.immersivePreference)
            if (before.pageIndicator != normalized.pageIndicator) fields["pageIndicator"] = JsonPrimitive(normalized.pageIndicator)
            return JsonObject(fields).toString()
        }

        fun resolve(defaults: ReaderSettings, documentSettingsJson: String?): ReaderSettings =
            decode(documentSettingsJson, defaults)

        fun hasExplicitTheme(documentJson: String?): Boolean {
            if (documentJson.isNullOrBlank()) return false
            val root = try {
                Json.parseToJsonElement(documentJson) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            } ?: return false
            val themeName = (root["theme"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?: return false
            return ReaderTheme.entries.any { it.name == themeName }
        }

        private fun sanitize(settings: ReaderSettings): ReaderSettings = settings.copy(
            marginDp = settings.marginDp.coerceIn(0, 64),
            fontScale = settings.fontScale.takeIf { it.isFinite() }?.coerceIn(0.8f, 1.8f)
                ?: originalDefaults.fontScale,
            lineHeight = settings.lineHeight.takeIf { it.isFinite() }?.coerceIn(1.2f, 2.2f)
                ?: originalDefaults.lineHeight,
        )

        private fun finiteNumber(element: JsonElement?): Float? {
            val primitive = element as? JsonPrimitive ?: return null
            if (primitive.isString) return null
            return primitive.floatOrNull?.takeIf { it.isFinite() }
        }
        private fun boolean(element: JsonElement?): Boolean? = (element as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    }
}
