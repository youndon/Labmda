package com.zouitel.datastore.di

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.zouitel.datastore.Constants.DS_NAME
import com.zouitel.datastore.mapper.SettingsMapper
import com.zouitel.datastore.datasourceImpl.SettingsDataSourceImpl
import com.zouitel.repository.datasource.SettingsDataSource
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.lazyModule

val datastoreLazyModule = lazyModule {
    single {
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler(
                produceNewData = { emptyPreferences() }
            )
        ) {
            androidContext().preferencesDataStoreFile(DS_NAME)
        }
    }

    factoryOf(::SettingsMapper)
    singleOf(::SettingsDataSourceImpl) bind SettingsDataSource::class
}
