package com.zouitel.datastore

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zouitel.datastore.Constants.LOCK_MODE
import com.zouitel.datastore.Constants.MUTE
import com.zouitel.datastore.Constants.SCREENSHOT_BLOCK
import com.zouitel.datastore.Constants.SHAKE_TO_REPORT
import com.zouitel.datastore.Constants.THEME
import com.zouitel.datastore.Constants.ZEN_MODE

object Keys {
    val LAYOUT = stringPreferencesKey(Constants.LAYOUT)
    val ORDINATION = stringPreferencesKey(Constants.ORDINATION)
    val DARK_THEME = booleanPreferencesKey(THEME)
    val IS_SOUND = booleanPreferencesKey(MUTE)
    val IS_LOCK_MODE = booleanPreferencesKey(LOCK_MODE)
    val IS_SCREENSHOT = booleanPreferencesKey(SCREENSHOT_BLOCK)
    val IS_ZEN_MODE = booleanPreferencesKey(ZEN_MODE)
    val IS_SHAKABLE = booleanPreferencesKey(SHAKE_TO_REPORT)
}