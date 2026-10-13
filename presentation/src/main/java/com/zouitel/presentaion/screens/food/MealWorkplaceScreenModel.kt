package com.zouitel.presentaion.screens.food

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MealWorkplaceScreenModel(
    private val repository: FoodRepository = FoodRepository()
) : ScreenModel {

    fun saveMeal(
        restaurantId: String,
        mealId: String = "",
        name: String,
        description: String,
        basePrice: Double,
        category: MealCategory,
        imageUrl: String,
        onSuccess: () -> Unit
    ) {
        screenModelScope.launch(Dispatchers.IO) {
            repository.addMeal(restaurantId, mealId, name, description, basePrice, category, imageUrl)
            onSuccess()
        }
    }

    fun deleteMeal(
        restaurantId: String,
        mealId: String,
        onSuccess: () -> Unit
    ) {
        screenModelScope.launch(Dispatchers.IO) {
            repository.deleteMeal(restaurantId, mealId)
            onSuccess()
        }
    }
}