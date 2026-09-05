package com.example.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "life_auth_session")

data class SupabaseAuthSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val registerNumber: String,
    val expiresAt: Long
)

class AuthSessionManager(private val context: Context) {
    companion object {
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val CURRENT_USER_ID = stringPreferencesKey("current_user_id")
        val CURRENT_REGISTER_NUMBER = stringPreferencesKey("current_register_number")
        val EXPIRES_AT = longPreferencesKey("expires_at")
    }

    val currentUserId: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[CURRENT_USER_ID]
    }

    val currentRegisterNumber: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[CURRENT_REGISTER_NUMBER]
    }

    val sessionFlow: Flow<SupabaseAuthSession?> = context.dataStore.data.map { preferences ->
        val token = preferences[ACCESS_TOKEN]
        val refresh = preferences[REFRESH_TOKEN]
        val userId = preferences[CURRENT_USER_ID]
        val reg = preferences[CURRENT_REGISTER_NUMBER]
        val expiresAt = preferences[EXPIRES_AT] ?: 0L

        if (token != null && userId != null && reg != null) {
            SupabaseAuthSession(
                accessToken = token,
                refreshToken = refresh ?: "",
                userId = userId,
                registerNumber = reg,
                expiresAt = expiresAt
            )
        } else {
            null
        }
    }

    suspend fun getSession(): SupabaseAuthSession? {
        return sessionFlow.firstOrNull()
    }

    suspend fun saveSession(
        accessToken: String,
        refreshToken: String,
        userId: String,
        registerNumber: String,
        expiresInSeconds: Long = 3600
    ) {
        val expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000)
        context.dataStore.edit { preferences ->
            preferences[ACCESS_TOKEN] = accessToken
            preferences[REFRESH_TOKEN] = refreshToken
            preferences[CURRENT_USER_ID] = userId
            preferences[CURRENT_REGISTER_NUMBER] = registerNumber
            preferences[EXPIRES_AT] = expiresAt
        }
    }

    suspend fun updateTokens(accessToken: String, refreshToken: String, expiresInSeconds: Long = 3600) {
        val expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000)
        context.dataStore.edit { preferences ->
            preferences[ACCESS_TOKEN] = accessToken
            preferences[REFRESH_TOKEN] = refreshToken
            preferences[EXPIRES_AT] = expiresAt
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit { preferences ->
            preferences.remove(ACCESS_TOKEN)
            preferences.remove(REFRESH_TOKEN)
            preferences.remove(CURRENT_USER_ID)
            preferences.remove(CURRENT_REGISTER_NUMBER)
            preferences.remove(EXPIRES_AT)
        }
    }
}
