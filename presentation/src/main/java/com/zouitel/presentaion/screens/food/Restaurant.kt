package com.zouitel.presentaion.screens.food

// 1. Restaurant Model
data class Restaurant(
    val id: String,
    val name: String,
    val imageUrl: String,
    val rating: Double,
    val deliveryFee: Double,
    val estimatedTimeMinutes: Int,
    val isOpen: Boolean = true,
    val menu: List<Meal> = emptyList(),
    val managers: List<String> = emptyList(), // suppose to be manager phones number
    val paymentMethods: List<String> = emptyList() // e.g., ["Cash on Courier", "Card on Courier"]
)

enum class MealCategory {
    BREAKFAST,
    LUNCH,
    DINNER,
    SNACKS,
    DESSERTS,
    BEVERAGES,
    CUSTOMIZED,
    OTHERS,
    UNKNOWN;
}
// 3. Meal Item Model (Linked to a Category & Restaurant)
data class Meal(
    val id: String = "",
    val restaurantId: String = "",
    val category: MealCategory = MealCategory.UNKNOWN,
    val name: String = "",
    val description: String = "",
    val basePrice: Double = 0.0,
    val imageUrls: List<String> = emptyList(),
    val isAvailable: Boolean = true
)