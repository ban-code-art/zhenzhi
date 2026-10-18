package com.example.myapplication.data.session

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sessionDataStore by preferencesDataStore(name = "session")

class SessionManager(private val context: Context) {

    private val keyCurrentUserId = longPreferencesKey("current_user_id")

    val currentUserId: Flow<Long?> = context.sessionDataStore.data
        .map { prefs -> prefs[keyCurrentUserId] }

    suspend fun login(userId: Long) {
        context.sessionDataStore.edit { prefs -> prefs[keyCurrentUserId] = userId }
    }

    suspend fun logout() {
        context.sessionDataStore.edit { prefs -> prefs.remove(keyCurrentUserId) }
    }
}
