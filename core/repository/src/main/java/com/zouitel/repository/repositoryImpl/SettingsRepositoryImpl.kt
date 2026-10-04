package com.zouitel.repository.repositoryImpl

import com.zouitel.domain.repository.SettingsRepository
import com.zouitel.repository.datasource.SettingsDataSource
import com.zouitel.repository.mapper.SettingsMapper
import com.zouitel.domain.model.Settings as OutSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepositoryImpl(
    private val dataSource: SettingsDataSource,
    private val mapper: SettingsMapper
): SettingsRepository {
    override val observe: Flow<OutSettings>
        get() = dataSource.observe.map { mapper.toDomain(it) }

    override suspend fun update(settings: OutSettings) {
        dataSource.update(mapper.fromDomain(settings))
    }
}