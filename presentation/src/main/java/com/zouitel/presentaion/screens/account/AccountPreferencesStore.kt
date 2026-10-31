package com.zouitel.presentaion.screens.account

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

data class AccountPreferences(
    val notificationsEnabled: Boolean = true,
    val darkModeEnabled: Boolean? = null,
    val language: String = "en"
)

class AccountPreferencesStore(context: Context) {
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile("account_preferences.preferences_pb") }
    )

    val preferences: Flow<AccountPreferences> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { values ->
            AccountPreferences(
                notificationsEnabled = values[NOTIFICATIONS_ENABLED] ?: true,
                darkModeEnabled = values[DARK_MODE_ENABLED],
                language = values[LANGUAGE] ?: "en"
            )
        }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { it[NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setDarkModeEnabled(enabled: Boolean?) {
        dataStore.edit { preferences ->
            if (enabled == null) preferences.remove(DARK_MODE_ENABLED) else preferences[DARK_MODE_ENABLED] = enabled
        }
    }

    suspend fun setLanguage(language: String) {
        dataStore.edit { it[LANGUAGE] = language }
    }

    private companion object {
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val DARK_MODE_ENABLED = booleanPreferencesKey("dark_mode_enabled")
        val LANGUAGE = stringPreferencesKey("app_language")
    }
}
