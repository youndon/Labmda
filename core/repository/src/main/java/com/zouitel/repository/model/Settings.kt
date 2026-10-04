package com.zouitel.repository.model

data class Settings(
    val layout: String,
    val ordination: String,
    val darlTheme: Boolean,
    val isSound: Boolean,
    val isLocked: Boolean,
    val isScreenshot: Boolean,
    val isZenMode: Boolean,
    val isShakable: Boolean
)