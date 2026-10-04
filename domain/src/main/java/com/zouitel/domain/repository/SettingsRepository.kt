package com.zouitel.domain.repository

import com.zouitel.domain.model.Settings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val observe: Flow<Settings>
    suspend fun update(settings: Settings)
}