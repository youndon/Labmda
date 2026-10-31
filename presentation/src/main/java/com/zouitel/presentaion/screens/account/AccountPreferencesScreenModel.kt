package com.zouitel.presentaion.screens.account

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AccountPreferencesScreenModel(
    private val store: AccountPreferencesStore
) : ScreenModel {
    val preferences = store.preferences.stateIn(
        screenModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AccountPreferences()
    )

    fun setNotificationsEnabled(enabled: Boolean) {
        screenModelScope.launch { store.setNotificationsEnabled(enabled) }
    }

    fun setDarkModeEnabled(enabled: Boolean?) {
        screenModelScope.launch { store.setDarkModeEnabled(enabled) }
    }

    fun setLanguage(language: String) {
        screenModelScope.launch { store.setLanguage(language) }
    }
}
