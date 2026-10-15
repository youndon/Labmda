package com.zouitel.presentaion.screens.food

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FoodRepository {
    private val db = Firebase.database.reference()

    fun getRestaurants(): Flow<List<Restaurant>> {
        return db.child("restaurants").valueEvents.map { snapshot ->
            snapshot.children.mapNotNull { child ->
                val id = child.key ?: return@mapNotNull null
                val name: String = child.child("name").value<String?>()
                    ?: child.child("fullName").value<String?>()
                    ?: "Restaurant"
                val imageUrl: String = child.child("imageUrl").value<String?>() ?: ""
                val rating: Double = child.child("rating").value<Double?>() ?: 5.0
                val deliveryFee: Double = child.child("deliveryFee").value<Double?>() ?: 0.0
                val estimatedTimeMinutes: Int = child.child("estimatedTimeMinutes").value<Int?>() ?: 20
                val isOpen: Boolean = child.child("isOpen").value<Boolean?>() ?: true
                val managers: List<String> = child.child("managers").value<List<String>?>() ?: emptyList()
                val paymentMethods: List<String> = child.child("paymentMethods").value<List<String>?>() ?: emptyList()

                // Location saved by the manager on the map
                val address: String = child.child("address").value<String?>() ?: ""
                val latitude: Double = child.child("latitude").value<Double?>() ?: 0.0
                val longitude: Double = child.child("longitude").value<Double?>() ?: 0.0

                Restaurant(
                    id = id,
                    name = name,
                    imageUrl = imageUrl,
                    rating = rating,
                    deliveryFee = deliveryFee,
                    estimatedTimeMinutes = estimatedTimeMinutes,
                    isOpen = isOpen,
                    managers = managers,
                    paymentMethods = paymentMethods,
                    address = address,
                    latitude = latitude,
                    longitude = longitude
                )
            }
        }
    }

    suspend fun addRestaurant(
        name: String,
        imageUrl: String,
        deliveryFee: Double,
        estimatedTimeMinutes: Int,
        managers: List<String>,
        address: String = "",
        latitude: Double = 0.0,
        longitude: Double = 0.0
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
            "isOpen" to true,
            "managers" to managers,
            "paymentMethods" to listOf("Cash on Delivery", "Card on Delivery"),
            "address" to address,
            "latitude" to latitude,
            "longitude" to longitude
        )
        newRef.setValue(restaurantMap)
    }

    fun getMealsForRestaurant(restaurantId: String): Flow<List<Meal>> {
        return db.child("restaurants").child(restaurantId).child("meals").valueEvents.map { snapshot ->
            snapshot.children.mapNotNull { child ->
                val id = child.key ?: return@mapNotNull null
                val name: String = child.child("name").value<String?>()
                    ?: child.child("fullName").value<String?>()
                    ?: ""
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

    suspend fun addMeal(
        restaurantId: String,
        mealId: String = "",
        name: String,
        description: String,
        basePrice: Double,
        category: MealCategory,
        imageUrl: String
    ) {
        val mealRef = if (mealId.isBlank()) {
            db.child("restaurants").child(restaurantId).child("meals").push()
        } else {
            db.child("restaurants").child(restaurantId).child("meals").child(mealId)
        }
        val id = mealRef.key ?: return
        val mealMap = mapOf(
            "id" to id,
            "sourceId" to restaurantId,
            "name" to name,
            "description" to description,
            "basePrice" to basePrice,
            "category" to category.name,
            "imageUrl" to imageUrl,
            "isAvailable" to true
        )
        mealRef.setValue(mealMap)
    }

    suspend fun deleteMeal(restaurantId: String, mealId: String) {
        db.child("restaurants").child(restaurantId).child("meals").child(mealId).removeValue()
    }
}