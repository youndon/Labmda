package com.zouitel.presentaion.screens.food

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.common.UserMode
import com.zouitel.presentaion.model.UserProfile
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ManagedRestaurantInfo(
    val id: String = "",
    val name: String = "",
    val imageUrl: String = "",
    val deliveryFee: Double = 0.0,
    val estimatedTimeMinutes: Int = 20,
    val managerPhone: String = ""
)

class RestaurantWorkplaceScreenModel(
    private val repository: FoodRepository = FoodRepository()
) : ScreenModel {
    private val db = Firebase.database.reference()
    private val auth = Firebase.auth

    private val _profile = MutableStateFlow(UserProfile())
    val profile: StateFlow<UserProfile> = _profile.asStateFlow()

    private val _restaurantInfo = MutableStateFlow(ManagedRestaurantInfo())
    val restaurantInfo: StateFlow<ManagedRestaurantInfo> = _restaurantInfo.asStateFlow()

    private val _meals = MutableStateFlow<List<Meal>>(emptyList())
    val meals: StateFlow<List<Meal>> = _meals.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        checkRestaurantStatus()
    }

    private fun checkRestaurantStatus() {
        val uid = auth.currentUser?.uid ?: run {
            _isLoading.value = false
            return
        }

        screenModelScope.launch(Dispatchers.IO) {
            db.child("users").child(uid).valueEvents.collect { snapshot ->
                if (snapshot.exists) {
                    val name = runCatching { snapshot.child("fullName").value<String>() }
                        .getOrElse { runCatching { snapshot.child("name").value<String>() }.getOrDefault("") }
                    val phone = runCatching { snapshot.child("phoneNumber").value<String>() }
                        .getOrElse { runCatching { snapshot.child("phone").value<String>() }.getOrDefault("") }

                    val foodModeStr = runCatching { snapshot.child("foodMode").value<String>() }.getOrDefault("CLIENT")
                    val foodMode = runCatching { UserMode.valueOf(foodModeStr) }.getOrDefault(UserMode.CLIENT)

                    val statusStr = runCatching { snapshot.child("foodStatus").value<String>() }.getOrDefault("NONE")
                    val foodStatus = runCatching { Status.valueOf(statusStr) }.getOrDefault(Status.NONE)

                    val isOnline = runCatching { snapshot.child("isFoodServiceOnline").value<Boolean>() }.getOrDefault(false)

                    _profile.value = UserProfile(
                        uid = uid,
                        fullName = name,
                        phoneNumber = phone,
                        foodMode = foodMode,
                        foodStatus = foodStatus,
                        isFoodServiceOnline = isOnline
                    )

                    if (foodStatus == Status.APPROVED) {
                        fetchManagedRestaurantDetails(uid)
                        observeMeals(uid)
                    }
                } else {
                    _profile.value = UserProfile(uid = uid, foodStatus = Status.NONE, foodMode = UserMode.CLIENT)
                }
                _isLoading.value = false
            }
        }
    }

    private fun fetchManagedRestaurantDetails(uid: String) {
        screenModelScope.launch(Dispatchers.IO) {
            db.child("users").child(uid).valueEvents.collect { snapshot ->
                val restaurantName = runCatching { snapshot.child("restaurantName").value<String>() }.getOrDefault("My Restaurant")
                val imageUrl = runCatching { snapshot.child("restaurantImageUrl").value<String>() }.getOrDefault("")
                val deliveryFee = runCatching { snapshot.child("deliveryFee").value<Double>() }.getOrDefault(0.0)
                val estimatedTime = runCatching { snapshot.child("estimatedTimeMinutes").value<Int>() }.getOrDefault(20)
                val managerPhone = runCatching { snapshot.child("managerPhone").value<String>() }.getOrDefault("")

                _restaurantInfo.value = ManagedRestaurantInfo(
                    id = uid,
                    name = restaurantName,
                    imageUrl = imageUrl,
                    deliveryFee = deliveryFee,
                    estimatedTimeMinutes = estimatedTime,
                    managerPhone = managerPhone
                )
            }
        }
    }

    private fun observeMeals(restaurantId: String) {
        screenModelScope.launch(Dispatchers.IO) {
            repository.getMealsForRestaurant(restaurantId).collect { mealList ->
                _meals.value = mealList
            }
        }
    }

    fun updateFoodMode(foodMode: UserMode) {
        val uid = auth.currentUser?.uid ?: return
        screenModelScope.launch(Dispatchers.IO) {
            db.child("users").child(uid).child("foodMode").setValue(foodMode.name)
        }
    }

    fun submitApplication(
        restaurantName: String,
        imageUrl: String,
        deliveryFee: Double,
        estimatedTimeMinutes: Int,
        managerPhone: String
    ) {
        val uid = auth.currentUser?.uid ?: return
        _isLoading.value = true

        screenModelScope.launch(Dispatchers.IO) {
            val updates = mapOf(
                "restaurantName" to restaurantName,
                "restaurantImageUrl" to imageUrl,
                "deliveryFee" to deliveryFee,
                "estimatedTimeMinutes" to estimatedTimeMinutes,
                "managerPhone" to managerPhone,
                "foodStatus" to Status.APPROVED.name,
                "foodMode" to UserMode.MANAGER.name
            )
            db.child("users").child(uid).updateChildren(updates)

            val publicMap = mapOf(
                "id" to uid,
                "name" to restaurantName,
                "imageUrl" to imageUrl,
                "deliveryFee" to deliveryFee,
                "estimatedTimeMinutes" to estimatedTimeMinutes,
                "managers" to listOf(managerPhone)
            )
            db.child("restaurants").child(uid).updateChildren(publicMap)

            _isLoading.value = false
        }
    }

    fun updateOnlineStatus(isOnline: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        screenModelScope.launch(Dispatchers.IO) {
            db.child("users").child(uid).child("isFoodServiceOnline").setValue(isOnline)
            db.child("restaurants").child(uid).child("isOpen").setValue(isOnline)
        }
    }
}