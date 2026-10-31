package com.zouitel.presentaion.screens.food

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.database.database
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
    private val db = Firebase.database.reference()

    private val _meals = MutableStateFlow<List<Meal>>(emptyList())
    val meals: StateFlow<List<Meal>> = _meals.asStateFlow()

    private val _metrics = MutableStateFlow(MerchantDashboardMetrics())
    val metrics: StateFlow<MerchantDashboardMetrics> = _metrics.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        observeMeals()
        observeOrders()
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

    private fun observeOrders() {
        screenModelScope.launch(Dispatchers.IO) {
            db.child("orders").orderByChild("sourceId").equalTo(restaurantId).valueEvents
                .catch { _metrics.value = MerchantDashboardMetrics() }
                .collect { snapshot ->
                    val orders = snapshot.children.mapNotNull { child ->
                        runCatching {
                            val type = child.child("orderType").value<String?>() ?: "FOOD"
                            if (type != "FOOD") return@runCatching null
                            MerchantOrderSnapshot(
                                status = child.child("status").value<String?>() ?: "PLACED",
                                salesAmount = child.child("subtotal").value<Double?>() ?: child.child("totalPrice").value<Double?>() ?: 0.0,
                                createdAtTimestamp = child.child("createdAtTimestamp").value<Long?>() ?: 0L
                            )
                        }.getOrNull()
                    }
                    _metrics.value = calculateMerchantDashboardMetrics(orders)
                }
        }
    }

    fun setMealAvailability(meal: Meal, available: Boolean) {
        screenModelScope.launch(Dispatchers.IO) {
            repository.setMealAvailability(restaurantId, meal.id, available)
        }
    }
}
