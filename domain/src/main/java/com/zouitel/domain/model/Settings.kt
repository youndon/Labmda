package com.zouitel.domain.model

data class Settings(
    val layout: String,
    val ordination: String,
    val darkTheme: Boolean,
    val isSound: Boolean,
    val isLocked: Boolean,
    val isScreenshot: Boolean,
    val isZenMode: Boolean,
    val isShakable: Boolean
)