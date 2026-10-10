package com.zouitel.presentaion.common

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LiveOrderTrackingScreenModel(val orderId: String) : ScreenModel {
    private val db = Firebase.database.reference()

    private val _order = MutableStateFlow<Order?>(null)
    val order: StateFlow<Order?> = _order.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        listenToOrderUpdates()
    }

    private fun listenToOrderUpdates() {
        screenModelScope.launch {
            db.child("orders").child(orderId).valueEvents.collect { snapshot ->
                if (!snapshot.exists) {
                    _order.value = null
                    _isLoading.value = false
                    return@collect
                }

                try {
                    val id = snapshot.key ?: orderId
                    val userId: String = snapshot.child("userId").value()
                    val restaurantId: String = snapshot.child("restaurantId").value()
                    val restaurantName: String = snapshot.child("restaurantName").value()
                    val subtotal: Double = snapshot.child("subtotal").value()
                    val courierFee: Double = snapshot.child("courierFee").value()
                    val totalPrice: Double = snapshot.child("totalPrice").value()
                    val statusStr: String = snapshot.child("status").value()
                    val deliveryAddress: String = snapshot.child("deliveryAddress").value()
                    val createdAtTimestamp: Long = snapshot.child("createdAtTimestamp").value()
                    val paymentMethod: String = snapshot.child("paymentMethod").value()

                    val courierId: String = runCatching { snapshot.child("courierId").value<String>() }.getOrDefault("")
                    val courierName: String = runCatching { snapshot.child("courierName").value<String>() }.getOrDefault("")
                    val courierPhone: String = runCatching { snapshot.child("courierPhone").value<String>() }.getOrDefault("")

                    val status = runCatching { OrderStatus.valueOf(statusStr) }.getOrDefault(
                        OrderStatus.PLACED)

                    val items = snapshot.child("items").children.map { itemChild ->
                        CartItem(
                            id = itemChild.child("id").value(),
                            name = itemChild.child("name").value(),
                            basePrice = itemChild.child("basePrice").value(),
                            quantity = itemChild.child("quantity").value(),
                            selectedOptions = emptyList()
                        )
                    }

                    _order.value = Order(
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
                    _order.value = null
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    fun cancelOrder() {
        screenModelScope.launch {
            db.child("orders").child(orderId).child("status").setValue(OrderStatus.CANCELLED.name)
        }
    }
}