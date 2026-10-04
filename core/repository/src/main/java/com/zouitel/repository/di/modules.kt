package com.zouitel.repository.di

import com.zouitel.domain.repository.SettingsRepository
import com.zouitel.domain.usecase.SettingsUseCase
import com.zouitel.repository.mapper.SettingsMapper
import com.zouitel.repository.repositoryImpl.SettingsRepositoryImpl
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.lazyModule

val localRepoLazyModule = lazyModule {
    //Mappers.
    factoryOf(::SettingsMapper)

    //Repositories.
    singleOf(::SettingsRepositoryImpl) bind SettingsRepository::class

    //UseCases.
    factoryOf(SettingsUseCase::ObserveSettings)
    factoryOf(SettingsUseCase::SaveSettings)
}