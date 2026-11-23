package com.example.myapplication.data.appearance

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.appearanceDataStore by preferencesDataStore(name = "device_appearance")

internal data class AppearanceState(
    val preferences: AppearancePreferences = AppearancePreferences(),
    val ready: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

internal class AppearanceStore(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    private data class Edit(val generation: Long, val value: AppearancePreferences)

    private val lock = Any()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val mutableState = MutableStateFlow(AppearanceState())
    private var persisted = AppearancePreferences()
    private var nextGeneration = 0L
    private var pending: Edit? = null
    private var failed: AppearancePreferences? = null

    val state: StateFlow<AppearanceState> = mutableState.asStateFlow()

    init {
        wake.trySend(Unit)
        scope.launch {
            for (signal in wake) {
                if (!mutableState.value.ready && !load()) continue
                val edit = synchronized(lock) { pending.also { pending = null } } ?: continue
                save(edit)
            }
        }
    }

    fun update(transform: (AppearancePreferences) -> AppearancePreferences) {
        synchronized(lock) {
            val current = mutableState.value
            if (!current.ready) return
            val next = transform(current.preferences).normalized()
            if (next != current.preferences) enqueue(next)
        }
    }

    fun retry() {
        synchronized(lock) {
            if (!mutableState.value.ready) {
                mutableState.value = mutableState.value.copy(error = null)
                wake.trySend(Unit)
            } else {
                failed?.let(::enqueue)
            }
        }
    }

    private fun enqueue(value: AppearancePreferences) {
        pending = Edit(++nextGeneration, value)
        failed = null
        mutableState.value = AppearanceState(value, ready = true, saving = true)
        wake.trySend(Unit)
    }

    private suspend fun load(): Boolean {
        val value = try {
            dataStore.data.first().appearancePreferences()
        } catch (_: IOException) {
            synchronized(lock) {
                mutableState.value = mutableState.value.copy(error = "无法读取外观设置，请重试")
            }
            return false
        }
        synchronized(lock) {
            persisted = value
            mutableState.value = AppearanceState(value, ready = true)
        }
        return true
    }

    private suspend fun save(edit: Edit) {
        val saved = try {
            dataStore.edit { it.writeAppearance(edit.value) }
            true
        } catch (_: IOException) {
            false
        }
        synchronized(lock) {
            if (saved) persisted = edit.value
            if (edit.generation != nextGeneration) return
            failed = if (saved) null else edit.value
            mutableState.value = AppearanceState(
                preferences = persisted,
                ready = true,
                error = if (saved) null else "外观设置未保存，请重试",
            )
        }
    }

    companion object {
        private var appStore: AppearanceStore? = null

        @Synchronized
        fun forApplication(context: Context): AppearanceStore = appStore ?: AppearanceStore(
            (context.applicationContext ?: context).appearanceDataStore,
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ).also { appStore = it }
    }
}

private object AppearanceKeys {
    val mode = stringPreferencesKey("mode")
    val seed = stringPreferencesKey("seed")
    val palette = stringPreferencesKey("palette")
    val colors = stringPreferencesKey("custom_colors")
    val pureBlack = booleanPreferencesKey("pure_black")
    val navigation = stringPreferencesKey("navigation")
    val dockMotion = stringPreferencesKey("dock_motion")
    val tabMotion = stringPreferencesKey("tab_motion")
    val glass = stringPreferencesKey("glass")
    val font = stringPreferencesKey("font")
    val customFont = stringPreferencesKey("custom_font")
    val textScale = floatPreferencesKey("text_scale")
    val lastTextScale = floatPreferencesKey("last_text_scale")
}

private inline fun <reified T : Enum<T>> Preferences.enum(key: Preferences.Key<String>, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == this[key] } ?: fallback

internal fun Preferences.appearancePreferences(): AppearancePreferences {
    val defaults = AppearancePreferences()
    val storedColors = this[AppearanceKeys.colors]
    val parsedColors = storedColors?.split(',')?.mapNotNull(String::toIntOrNull).orEmpty()
    return AppearancePreferences(
        mode = enum(AppearanceKeys.mode, defaults.mode),
        seed = this[AppearanceKeys.seed].let { stored ->
            if (stored == "system") null else stored?.toIntOrNull() ?: defaults.seed
        },
        palette = enum(AppearanceKeys.palette, defaults.palette),
        customColors = if (storedColors == "" || parsedColors.isNotEmpty()) parsedColors else defaults.customColors,
        pureBlack = this[AppearanceKeys.pureBlack] ?: defaults.pureBlack,
        navigation = enum(AppearanceKeys.navigation, defaults.navigation),
        dockMotion = enum(AppearanceKeys.dockMotion, defaults.dockMotion),
        tabMotion = enum(AppearanceKeys.tabMotion, defaults.tabMotion),
        glass = enum(AppearanceKeys.glass, defaults.glass),
        font = enum(AppearanceKeys.font, defaults.font),
        textScale = this[AppearanceKeys.textScale],
        lastCustomTextScale = this[AppearanceKeys.lastTextScale] ?: defaults.lastCustomTextScale,
        customFont = this[AppearanceKeys.customFont],
    ).normalized()
}

private fun MutablePreferences.writeAppearance(value: AppearancePreferences) {
    this[AppearanceKeys.mode] = value.mode.name
    this[AppearanceKeys.seed] = value.seed?.toString() ?: "system"
    this[AppearanceKeys.palette] = value.palette.name
    this[AppearanceKeys.colors] = value.customColors.joinToString(",")
    this[AppearanceKeys.pureBlack] = value.pureBlack
    this[AppearanceKeys.navigation] = value.navigation.name
    this[AppearanceKeys.dockMotion] = value.dockMotion.name
    this[AppearanceKeys.tabMotion] = value.tabMotion.name
    this[AppearanceKeys.glass] = value.glass.name
    this[AppearanceKeys.font] = value.font.name
    if (value.customFont == null) remove(AppearanceKeys.customFont)
    else this[AppearanceKeys.customFont] = value.customFont
    if (value.textScale == null) remove(AppearanceKeys.textScale)
    else this[AppearanceKeys.textScale] = value.textScale
    this[AppearanceKeys.lastTextScale] = value.lastCustomTextScale
}
