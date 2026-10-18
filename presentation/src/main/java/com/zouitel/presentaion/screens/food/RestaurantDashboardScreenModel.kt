package com.zouitel.presentaion.screens.food

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class RestaurantDashboardScreenModel(
    private val restaurantId: String,
    private val repository: FoodRepository = FoodRepository()
) : ScreenModel {

    private val _meals = MutableStateFlow<List<Meal>>(emptyList())
    val meals: StateFlow<List<Meal>> = _meals.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        observeMeals()
    }

    private fun observeMeals() {
        screenModelScope.launch(Dispatchers.IO) {
            repository.getMealsForRestaurant(restaurantId)
                .catch { _isLoading.value = false }
                .collect {
                    _meals.value = it
                    _isLoading.value = false
                }
        }
    }
}