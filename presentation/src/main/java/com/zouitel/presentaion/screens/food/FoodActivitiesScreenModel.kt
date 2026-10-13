package com.zouitel.presentaion.screens.food

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.CartItem
import com.zouitel.presentaion.common.Order
import com.zouitel.presentaion.common.OrderStatus
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FoodActivitiesScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val currentUserId = Firebase.auth.currentUser?.uid

    private val _orders = MutableStateFlow<List<Order>>(emptyList())
    val orders: StateFlow<List<Order>> = _orders.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        listenToUserOrders()
    }

    private fun listenToUserOrders() {
        screenModelScope.launch(Dispatchers.IO) {
            db.child("orders").valueEvents.collect { snapshot ->
                val fetchedOrders = snapshot.children.mapNotNull { child ->
                    try {
                        val id = child.key ?: return@mapNotNull null
                        val userId: String = child.child("userId").value()

                        // Filter orders belonging to the current user (or all if unauthenticated)
                        if (currentUserId != null && userId.isNotBlank() && userId != currentUserId) {
                            return@mapNotNull null
                        }

                        val restaurantId: String = child.child("restaurantId").value()
                        val restaurantName: String = child.child("restaurantName").value()
                        val subtotal: Double = child.child("subtotal").value()

                        val courierFee: Double = runCatching { child.child("courierFee").value<Double>() }
                            .getOrElse { runCatching { child.child("deliveryFee").value<Double>() }.getOrDefault(0.0) }

                        val totalPrice: Double = child.child("totalPrice").value()
                        val statusStr: String = child.child("status").value()
                        val deliveryAddress: String = child.child("deliveryAddress").value()
                        val createdAtTimestamp: Long = child.child("createdAtTimestamp").value()
                        val paymentMethod: String = child.child("paymentMethod").value()

                        val courierId: String = runCatching { child.child("courierId").value<String>() }.getOrDefault("")
                        val courierName: String = runCatching { child.child("courierName").value<String>() }.getOrDefault("")
                        val courierPhone: String = runCatching { child.child("courierPhone").value<String>() }.getOrDefault("")

                        val status = runCatching { OrderStatus.valueOf(statusStr) }.getOrDefault(
                            OrderStatus.PLACED)

                        val items = child.child("items").children.map { itemChild ->
                            CartItem(
                                id = itemChild.child("id").value(),
                                name = itemChild.child("name").value(),
                                basePrice = itemChild.child("basePrice").value(),
                                quantity = itemChild.child("quantity").value(),
                                selectedOptions = emptyList()
                            )
                        }

                        Order(
                            id = id,
                            userId = userId,
                            restaurantId = restaurantId,
                            restaurantName = restaurantName,
                            items = items,
                            subtotal = subtotal,
                            courierFee = courierFee,
                            totalPrice = totalPrice,
                            status = status,
                            deliveryAddress = deliveryAddress,
                            createdAtTimestamp = createdAtTimestamp,
                            paymentMethod = paymentMethod,
                            courierId = courierId,
                            courierName = courierName,
                            courierPhone = courierPhone
                        )
                    } catch (e: Exception) {
                        null
                    }
                }.sortedByDescending { it.createdAtTimestamp }

                _orders.value = fetchedOrders
                _isLoading.value = false
            }
        }
    }

    fun cancelOrder(orderId: String) {
        screenModelScope.launch(Dispatchers.IO) {
            db.child("orders").child(orderId).child("status").setValue(OrderStatus.CANCELLED.name)
        }
    }
}