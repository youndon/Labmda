package com.zouitel.datastore.datasourceImpl

import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.zouitel.datastore.Keys
import com.zouitel.datastore.model.Settings
import com.zouitel.repository.model.Settings as OutSettings
import com.zouitel.datastore.mapper.SettingsMapper
import com.zouitel.repository.datasource.SettingsDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

class SettingsDataSourceImpl(
    private val datastore: DataStore<Preferences>,
    private val mapper: SettingsMapper
): SettingsDataSource {

    override val observe: Flow<OutSettings>
        get() = datastore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else throw exception
            }
            .map { preferences ->
                mapper.toRepo(
                    Settings(
                        layout = preferences[Keys.LAYOUT] ?: "grid_layout",
                        ordination = preferences[Keys.ORDINATION] ?: "Default",
                        darkTheme = preferences[Keys.DARK_THEME] ?: true,
                        isSound = preferences[Keys.IS_SOUND] ?: false,
                        isLocked = preferences[Keys.IS_LOCK_MODE] ?: false,
                        isScreenshot = preferences[Keys.IS_SCREENSHOT] ?: false,
                        isZenMode = preferences[Keys.IS_ZEN_MODE] ?: false,
                        isShakable = preferences[Keys.IS_SHAKABLE] ?: true
                    )
                )
            }

    override suspend fun update(settings: OutSettings) {
        datastore.edit { preferences ->
            preferences[Keys.LAYOUT] = mapper.fromRepo(settings).layout
            preferences[Keys.DARK_THEME] = mapper.fromRepo(settings).darkTheme
            preferences[Keys.ORDINATION] = mapper.fromRepo(settings).ordination
            preferences[Keys.IS_SOUND] = mapper.fromRepo(settings).isSound
            preferences[Keys.IS_LOCK_MODE] = mapper.fromRepo(settings).isLocked
            preferences[Keys.IS_SCREENSHOT] = mapper.fromRepo(settings).isScreenshot
            preferences[Keys.IS_ZEN_MODE] = mapper.fromRepo(settings).isZenMode
            preferences[Keys.IS_SHAKABLE] = mapper.fromRepo(settings).isShakable
        }
    }
}