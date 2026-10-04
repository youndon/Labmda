package com.zouitel.presentaion.screens.food

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class OrdersScreenModel : ScreenModel {
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
        screenModelScope.launch {
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
                        val deliveryFee: Double = child.child("deliveryFee").value()
                        val totalPrice: Double = child.child("totalPrice").value()
                        val statusStr: String = child.child("status").value()
                        val deliveryAddress: String = child.child("deliveryAddress").value()
                        val createdAtTimestamp: Long = child.child("createdAtTimestamp").value()
                        val paymentMethod: String = child.child("paymentMethod").value()

                        val status = runCatching { OrderStatus.valueOf(statusStr) }.getOrDefault(OrderStatus.PLACED)

                        val items = child.child("items").children.map { itemChild ->
                            CartItem(
                                mealId = itemChild.child("mealId").value(),
                                mealName = itemChild.child("mealName").value(),
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
                            deliveryFee = deliveryFee,
                            totalPrice = totalPrice,
                            status = status,
                            deliveryAddress = deliveryAddress,
                            createdAtTimestamp = createdAtTimestamp,
                            paymentMethod = paymentMethod
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
        screenModelScope.launch {
            db.child("orders").child(orderId).child("status").setValue(OrderStatus.CANCELLED.name)
        }
    }
}