package com.zouitel.presentaion.screens.food

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

// ScreenModel for RestaurantsScreen
class RestaurantsScreenModel(
    private val repository: FoodRepository = FoodRepository()
) : ScreenModel {

    val restaurants: StateFlow<List<Restaurant>> = repository.getRestaurants()
        .stateIn(
            scope = screenModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
}





