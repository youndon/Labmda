package com.zouitel.presentaion.screens.food

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FoodRepository {
    private val db = Firebase.database.reference()

    // Read all restaurants in real-time
    fun getRestaurants(): Flow<List<Restaurant>> {
        return db.child("restaurants").valueEvents.map { snapshot ->
            snapshot.children.mapNotNull { child ->
                val id = child.key ?: return@mapNotNull null
                val name: String = child.child("name").value<String?>() ?: ""
                val imageUrl: String = child.child("imageUrl").value<String?>() ?: ""
                val rating: Double = child.child("rating").value<Double?>() ?: 5.0
                val deliveryFee: Double = child.child("deliveryFee").value<Double?>() ?: 0.0
                val estimatedTimeMinutes: Int = child.child("estimatedTimeMinutes").value<Int?>() ?: 0
                val managers: List<String> = child.child("managers").value<List<String>?>() ?: emptyList()
                val isOpen: Boolean = child.child("isOpen").value<Boolean?>() ?: true

                Restaurant(
                    id = id,
                    name = name,
                    imageUrl = imageUrl,
                    rating = rating,
                    deliveryFee = deliveryFee,
                    estimatedTimeMinutes = estimatedTimeMinutes,
                    managers = managers,
                    isOpen = isOpen
                )
            }
        }
    }

    // Add a new restaurant unit
    suspend fun addRestaurant(
        name: String,
        imageUrl: String,
        deliveryFee: Double,
        estimatedTimeMinutes: Int,
        managers: List<String>
    ) {
        val newRef = db.child("restaurants").push()
        val id = newRef.key ?: return
        val restaurantMap = mapOf(
            "id" to id,
            "name" to name,
            "imageUrl" to imageUrl,
            "rating" to 5.0,
            "deliveryFee" to deliveryFee,
            "estimatedTimeMinutes" to estimatedTimeMinutes,
            "managers" to managers,
            "isOpen" to true
        )
        newRef.setValue(restaurantMap)
    }

    // Read all meals for a specific restaurant in real-time
    fun getMealsForRestaurant(restaurantId: String): Flow<List<Meal>> {
        return db.child("restaurants").child(restaurantId).child("meals").valueEvents.map { snapshot ->
            snapshot.children.mapNotNull { child ->
                val id = child.key ?: return@mapNotNull null
                val name: String = child.child("name").value<String?>() ?: ""
                val description: String = child.child("description").value<String?>() ?: ""
                val basePrice: Double = child.child("basePrice").value<Double?>() ?: 0.0
                val categoryStr: String = child.child("category").value<String?>() ?: ""
                val imageUrl: String = child.child("imageUrl").value<String?>() ?: ""
                val isAvailable: Boolean = child.child("isAvailable").value<Boolean?>() ?: true

                val category = runCatching { MealCategory.valueOf(categoryStr) }.getOrDefault(MealCategory.UNKNOWN)

                Meal(
                    id = id,
                    restaurantId = restaurantId,
                    category = category,
                    name = name,
                    description = description,
                    basePrice = basePrice,
                    imageUrls = listOf(imageUrl),
                    isAvailable = isAvailable
                )
            }
        }
    }

    // Add a meal unit to a specific restaurant
    suspend fun addMeal(
        restaurantId: String,
        name: String,
        description: String,
        basePrice: Double,
        category: MealCategory,
        imageUrl: String
    ) {
        val mealRef = db.child("restaurants").child(restaurantId).child("meals").push()
        val mealId = mealRef.key ?: return
        val mealMap = mapOf(
            "id" to mealId,
            "restaurantId" to restaurantId,
            "name" to name,
            "description" to description,
            "basePrice" to basePrice,
            "category" to category.name,
            "imageUrl" to imageUrl,
            "isAvailable" to true
        )
        mealRef.setValue(mealMap)
    }
}