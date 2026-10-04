package com.zouitel.datastore.mapper

import com.zouitel.datastore.model.Settings
import com.zouitel.repository.model.Settings as OutSettings

class SettingsMapper {
    fun toRepo(settings: Settings) = OutSettings(
        layout = settings.layout,
        ordination = settings.ordination,
        darlTheme = settings.darkTheme,
        isSound = settings.isSound,
        isLocked = settings.isLocked,
        isScreenshot = settings.isScreenshot,
        isZenMode = settings.isZenMode,
        isShakable = settings.isShakable
    )

    fun fromRepo(settings: OutSettings) = Settings(
        layout = settings.layout,
        ordination = settings.ordination,
        darkTheme = settings.darlTheme,
        isSound = settings.isSound,
        isLocked = settings.isLocked,
        isScreenshot = settings.isScreenshot,
        isZenMode = settings.isZenMode,
        isShakable = settings.isShakable
    )
}