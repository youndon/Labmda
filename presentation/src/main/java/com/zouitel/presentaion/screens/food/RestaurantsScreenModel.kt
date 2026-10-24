package com.zouitel.presentaion.screens.food

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/** Screen model for the public restaurant catalog. */
class RestaurantsScreenModel(
    private val repository: FoodRepository = FoodRepository()
) : ScreenModel {

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val restaurants: StateFlow<List<Restaurant>> = repository.getRestaurants()
        .onEach { _error.value = null }
        .catch { cause ->
            // Convert Firebase listener cancellation/permission failures into UI state instead
            // of letting the exception escape the screen's collection coroutine.
            _error.value = cause.message?.takeIf { it.isNotBlank() }
                ?: "Could not load restaurants. Check your connection and database permissions."
            emit(emptyList())
        }
        .stateIn(
            scope = screenModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun clearError() {
        _error.value = null
    }
}
