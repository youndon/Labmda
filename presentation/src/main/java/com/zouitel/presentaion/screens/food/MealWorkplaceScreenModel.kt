package com.zouitel.presentaion.screens.food

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch

// ScreenModel for MealWorkplaceScreen
class MealWorkplaceScreenModel(
    private val repository: FoodRepository = FoodRepository()
) : ScreenModel {

    fun saveMeal(
        restaurantId: String,
        name: String,
        description: String,
        basePrice: Double,
        category: MealCategory,
        imageUrl: String,
        onSuccess: () -> Unit
    ) {
        screenModelScope.launch {
            repository.addMeal(restaurantId, name, description, basePrice, category, imageUrl)
            onSuccess()
        }
    }
}