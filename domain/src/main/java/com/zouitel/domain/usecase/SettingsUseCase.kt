package com.zouitel.domain.usecase

import com.zouitel.domain.model.Settings
import com.zouitel.domain.repository.SettingsRepository

sealed class SettingsUseCase {
    class ObserveSettings(private val repo: SettingsRepository): SettingsUseCase() {
        operator fun invoke() = repo.observe
    }
    class SaveSettings(private val repo: SettingsRepository): SettingsUseCase() {
        suspend operator fun invoke(settings: Settings) = repo.update(settings)
    }
}