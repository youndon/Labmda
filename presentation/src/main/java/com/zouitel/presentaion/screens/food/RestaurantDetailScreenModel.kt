package com.zouitel.presentaion.screens.food

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

// ScreenModel for Restaurants
class RestaurantDetailScreenModel(
    restaurantId: String,
    repository: FoodRepository = FoodRepository()
) : ScreenModel {

    val meals: StateFlow<List<Meal>> = repository.getMealsForRestaurant(restaurantId)
        .stateIn(
            scope = screenModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
}