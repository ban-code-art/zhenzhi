package com.example.myapplication.data.session

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.sessionDataStore by preferencesDataStore(name = "session")
private val sessionIdentityMutex = Mutex()

data class SessionIdentity(val userId: Long?, val epoch: Long)

class SessionManager(private val context: Context) {

    private val keyCurrentUserId = longPreferencesKey("current_user_id")
    private val keySessionEpoch = longPreferencesKey("session_epoch")

    val identity: Flow<SessionIdentity> = context.sessionDataStore.data
        .map { prefs -> SessionIdentity(prefs[keyCurrentUserId], prefs[keySessionEpoch] ?: 0L) }

    val currentUserId: Flow<Long?> = context.sessionDataStore.data
        .map { prefs -> prefs[keyCurrentUserId] }

    suspend fun login(userId: Long) = sessionIdentityMutex.withLock {
        context.sessionDataStore.edit { prefs ->
            prefs[keySessionEpoch] = Math.addExact(prefs[keySessionEpoch] ?: 0L, 1L)
            prefs[keyCurrentUserId] = userId
        }
        Unit
    }

    suspend fun logout() = sessionIdentityMutex.withLock {
        context.sessionDataStore.edit { prefs ->
            prefs[keySessionEpoch] = Math.addExact(prefs[keySessionEpoch] ?: 0L, 1L)
            prefs.remove(keyCurrentUserId)
        }
        Unit
    }

    /**
     * Protect a user-owned operation across its transaction, including when another
     * SessionManager instance requests an account change.
     */
    suspend fun <T : Any> withCurrentIdentity(expected: SessionIdentity, block: suspend () -> T): T? {
        return sessionIdentityMutex.withLock {
            if (identity.first() != expected) null else block()
        }
    }
}
