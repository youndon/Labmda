package com.zouitel.presentaion.screens.shops

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.CartItem
import com.zouitel.presentaion.common.Order
import com.zouitel.presentaion.common.OrderStatus
import com.zouitel.presentaion.common.OrderType
import com.zouitel.presentaion.screens.courier.Courier
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ShopOrdersScreenModel : ScreenModel {
    private val db = Firebase.database.reference()

    private val _orders = MutableStateFlow<List<Order>>(emptyList())
    val orders: StateFlow<List<Order>> = _orders.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        listenToShopOrders()
    }

    private fun listenToShopOrders() {
        screenModelScope.launch {
            db.child("orders").valueEvents.collect { snapshot ->
                val fetchedOrders = snapshot.children.mapNotNull { child ->
                    try {
                        val id = child.key ?: return@mapNotNull null

                        val targetShopId: String = runCatching { child.child("shopId").value<String>() }
                            .getOrElse { runCatching { child.child("restaurantId").value<String>() }.getOrDefault("") }

                        val userId: String = runCatching { child.child("userId").value<String>() }.getOrDefault("")

                        val shopName: String = runCatching { child.child("shopName").value<String>() }
                            .getOrElse { runCatching { child.child("restaurantName").value<String>() }.getOrDefault("Store") }
                        val subtotal: Double = runCatching { child.child("subtotal").value<Double>() }.getOrDefault(0.0)
                        val courierFee: Double = runCatching { child.child("courierFee").value<Double>() }.getOrDefault(3.0)
                        val totalPrice: Double = runCatching { child.child("totalPrice").value<Double>() }.getOrDefault(0.0)
                        val statusStr: String = runCatching { child.child("status").value<String>() }.getOrDefault("PLACED")
                        val deliveryAddress: String = runCatching { child.child("deliveryAddress").value<String>() }.getOrDefault("")
                        val createdAtTimestamp: Long = runCatching { child.child("createdAtTimestamp").value<Long>() }.getOrDefault(0L)
                        val paymentMethod: String = runCatching { child.child("paymentMethod").value<String>() }.getOrDefault("Cash")

                        val courierId: String = runCatching { child.child("courierId").value<String>() }.getOrDefault("")
                        val courierName: String = runCatching { child.child("courierName").value<String>() }.getOrDefault("")
                        val courierPhone: String = runCatching { child.child("courierPhone").value<String>() }.getOrDefault("")

                        val status = runCatching { OrderStatus.valueOf(statusStr) }.getOrDefault(OrderStatus.PLACED)

                        val items = child.child("items").children.map { itemChild ->
                            CartItem(
                                id = runCatching { itemChild.child("productId").value<String>() }
                                    .getOrElse { runCatching { itemChild.child("id").value<String>() }.getOrDefault("") },
                                name = runCatching { itemChild.child("productName").value<String>() }
                                    .getOrElse { runCatching { itemChild.child("name").value<String>() }.getOrDefault("Product") },
                                basePrice = runCatching { itemChild.child("price").value<Double>() }
                                    .getOrElse { runCatching { itemChild.child("basePrice").value<Double>() }.getOrDefault(0.0) },
                                quantity = runCatching { itemChild.child("quantity").value<Int>() }.getOrDefault(1)
                            )
                        }

                        Order(
                            id = id,
                            userId = userId,
                            orderType = OrderType.RETAIL,
                            shopId = targetShopId,
                            shopName = shopName,
                            restaurantId = targetShopId,
                            restaurantName = shopName,
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

    fun updateOrderStatus(order: Order, nextStatus: OrderStatus) {
        if (nextStatus == OrderStatus.DELIVERED) return

        screenModelScope.launch {
            db.child("orders").child(order.id).child("status").setValue(nextStatus.name)

            if (nextStatus == OrderStatus.OUT_FOR_DELIVERY) {
                val courierRecord = Courier(
                    id = order.id,
                    orderId = order.id,
                    restaurantId = order.shopId,
                    restaurantName = order.shopName,
                    customerAddress = order.deliveryAddress,
                    items = order.items,
                    totalPrice = order.totalPrice,
                    courierFee = order.courierFee,
                    status = OrderStatus.OUT_FOR_DELIVERY,
                    dispatchedAtTimestamp = System.currentTimeMillis()
                )
                db.child("couriers").child(order.id).setValue(courierRecord)
            }
        }
    }
}