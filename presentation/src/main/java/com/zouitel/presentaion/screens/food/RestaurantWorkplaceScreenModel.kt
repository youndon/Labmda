package com.zouitel.presentaion.screens.food

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch

// ScreenModel for RestaurantWorkplaceScreen
class RestaurantWorkplaceScreenModel(
    private val repository: FoodRepository = FoodRepository()
) : ScreenModel {

    fun saveRestaurant(
        name: String,
        imageUrl: String,
        deliveryFee: Double,
        estimatedTimeMinutes: Int,
        managers: List<String>,
        onSuccess: () -> Unit
    ) {
        screenModelScope.launch {
            repository.addRestaurant(
                name,
                imageUrl,
                deliveryFee,
                estimatedTimeMinutes,
                managers
            )
            onSuccess()
        }
    }
}