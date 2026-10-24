package com.zouitel.presentaion.common

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.model.CartItem
import com.zouitel.presentaion.model.Order
import com.zouitel.presentaion.model.OrderStatus
import com.zouitel.presentaion.model.OrderType
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.Dispatchers
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
        screenModelScope.launch(Dispatchers.IO) {
            db.child("orders").child(orderId).valueEvents.collect { snapshot ->
                if (!snapshot.exists) {
                    _order.value = null
                    _isLoading.value = false
                    return@collect
                }

                try {
                    val id = snapshot.key ?: orderId

                    fun str(key: String) = runCatching { snapshot.child(key).value<String>() }.getOrDefault("")
                    fun dbl(key: String) = runCatching { snapshot.child(key).value<Double>() }.getOrDefault(0.0)

                    val statusStr = str("status")
                    val status = runCatching { OrderStatus.valueOf(statusStr) }.getOrDefault(OrderStatus.PLACED)
                    val orderType = runCatching { OrderType.valueOf(str("orderType")) }.getOrDefault(OrderType.RETAIL)

                    val items = snapshot.child("items").children.map { itemChild ->
                        CartItem(
                            id = runCatching { itemChild.child("id").value<String>() }.getOrDefault(""),
                            name = runCatching { itemChild.child("name").value<String>() }.getOrDefault("Item"),
                            basePrice = runCatching { itemChild.child("basePrice").value<Double>() }.getOrDefault(0.0),
                            quantity = runCatching { itemChild.child("quantity").value<Int>() }.getOrDefault(1),
                            specialInstructions = runCatching { itemChild.child("specialInstructions").value<String>() }.getOrDefault("")
                        )
                    }

                    _order.value = Order(
                        id = id,
                        clientId = str("clientId"),
                        orderType = orderType,
                        sourceId = str("sourceId"),
                        sourceName = str("sourceName"),
                        items = items,
                        subtotal = dbl("subtotal"),
                        deliveryFee = dbl("deliveryFee"),
                        totalPrice = dbl("totalPrice"),
                        status = status,
                        pickupLocation = str("pickupLocation"),
                        pickupLat = dbl("pickupLat"),
                        pickupLng = dbl("pickupLng"),
                        dropoffLocation = str("dropoffLocation"),
                        dropoffLat = dbl("dropoffLat"),
                        dropoffLng = dbl("dropoffLng"),
                        createdAtTimestamp = runCatching { snapshot.child("createdAtTimestamp").value<Long>() }.getOrDefault(0L),
                        paymentMethod = str("paymentMethod"),
                        deliveryId = str("deliveryId"),
                        deliveryName = str("deliveryName"),
                        deliveryPhone = str("deliveryPhone")
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
        screenModelScope.launch(Dispatchers.IO) {
            db.child("orders").child(orderId).child("status").setValue(OrderStatus.CANCELLED.name)
        }
    }
}