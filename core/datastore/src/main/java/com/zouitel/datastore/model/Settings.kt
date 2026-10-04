package com.zouitel.datastore.model

data class Settings(
    val layout: String = "grid_layout",
    val ordination: String = "Default",
    val darkTheme: Boolean = true,
    val isSound: Boolean = false,
    val isLocked: Boolean = false,
    val isScreenshot: Boolean = false,
    val isZenMode: Boolean = false,
    val isShakable: Boolean = true
)