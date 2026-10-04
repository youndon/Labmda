package com.zouitel.repository.mapper

import com.zouitel.repository.model.Settings
import com.zouitel.domain.model.Settings as OutSettings

class SettingsMapper {
    fun toDomain(settings: Settings) = OutSettings(
        layout = settings.layout,
        ordination = settings.ordination,
        darkTheme = settings.darlTheme,
        isSound = settings.isSound,
        isLocked = settings.isLocked,
        isScreenshot = settings.isScreenshot,
        isZenMode = settings.isZenMode,
        isShakable = settings.isShakable
    )

    fun fromDomain(settings: OutSettings) = Settings(
        layout = settings.layout,
        ordination = settings.ordination,
        darlTheme = settings.darkTheme,
        isSound = settings.isSound,
        isLocked = settings.isLocked,
        isScreenshot = settings.isScreenshot,
        isZenMode = settings.isZenMode,
        isShakable = settings.isShakable
    )
}