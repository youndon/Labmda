package com.zouitel.presentaion.screens.food

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class RestaurantsScreenModel(
    private val repository: FoodRepository = FoodRepository()
) : ScreenModel {

    private val _restaurants = MutableStateFlow<List<Restaurant>>(emptyList())
    val restaurants: StateFlow<List<Restaurant>> = _restaurants.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = screenModelScope.launch {
            _error.value = null
            _isLoading.value = _restaurants.value.isEmpty()

            repository.getRestaurants()
                .catch { cause ->
                    _error.value = cause.message ?: "Restaurants could not be loaded. Check your connection and try again."
                    _isLoading.value = false
                }
                .collect { items ->
                    _restaurants.value = items
                    _isLoading.value = false
                    _error.value = null
                }
        }
    }
}
