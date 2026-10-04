package com.zouitel.repository.datasource

import com.zouitel.repository.model.Settings
import kotlinx.coroutines.flow.Flow

interface SettingsDataSource {
    val observe: Flow<Settings>
    suspend fun update(settings: Settings)
}