package com.zouitel.presentaion.screens.shops

import com.zouitel.presentaion.payments.ServiceActionsRepository

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.model.CartItem
import com.zouitel.presentaion.model.Order
import com.zouitel.presentaion.model.OrderStatus
import com.zouitel.presentaion.model.OrderType
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ShopOrdersScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val serviceActions = ServiceActionsRepository()
    private val currentUid = Firebase.auth.currentUser?.uid

    private val _orders = MutableStateFlow<List<Order>>(emptyList())
    val orders: StateFlow<List<Order>> = _orders.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        listenToShopOrders()
    }

    private fun listenToShopOrders() {
        val uid = currentUid
        if (uid.isNullOrBlank()) {
            _orders.value = emptyList()
            _isLoading.value = false
            return
        }
        screenModelScope.launch {
            db.child("orders").orderByChild("merchantId").equalTo(uid).valueEvents.collect { snapshot ->
                val fetchedOrders = snapshot.children.mapNotNull { child ->
                    try {
                        val id = child.key ?: return@mapNotNull null

                        val targetShopId: String = runCatching { child.child("sourceId").value<String>() }.getOrDefault("")
                        val clientId: String = runCatching { child.child("clientId").value<String>() }.getOrDefault("")
                        val shopName: String = runCatching { child.child("sourceName").value<String>() }.getOrDefault("Store")
                        val subtotal: Double = runCatching { child.child("subtotal").value<Double>() }.getOrDefault(0.0)
                        val courierFee: Double = runCatching { child.child("deliveryFee").value<Double>() }.getOrDefault(3.0)
                        val totalPrice: Double = runCatching { child.child("totalPrice").value<Double>() }.getOrDefault(0.0)
                        val statusStr: String = runCatching { child.child("status").value<String>() }.getOrDefault("PLACED")
                        val pickupLocation: String = runCatching { child.child("pickupLocation").value<String>() }.getOrDefault("")
                        val pickupLat: Double = runCatching { child.child("pickupLat").value<Double>() }.getOrDefault(0.0)
                        val pickupLng: Double = runCatching { child.child("pickupLng").value<Double>() }.getOrDefault(0.0)
                        val dropoffLocation: String = runCatching { child.child("dropoffLocation").value<String>() }.getOrDefault("")
                        val dropoffLat: Double = runCatching { child.child("dropoffLat").value<Double>() }.getOrDefault(0.0)
                        val dropoffLng: Double = runCatching { child.child("dropoffLng").value<Double>() }.getOrDefault(0.0)
                        val paymentMethod: String = runCatching { child.child("paymentMethod").value<String>() }.getOrDefault("Cash")
                        val deliveryId: String = runCatching { child.child("deliveryId").value<String>() }.getOrDefault("")
                        val deliveryName: String = runCatching { child.child("deliveryName").value<String>() }.getOrDefault("")
                        val deliveryPhone: String = runCatching { child.child("deliveryPhone").value<String>() }.getOrDefault("")
                        val createdAtTimestamp: Long = runCatching {
                            child.child("createdAtTimestamp").value<Long>()
                        }.getOrDefault(0L)

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
                            clientId = clientId,
                            orderType = OrderType.RETAIL,
                            sourceId = targetShopId,
                            sourceName = shopName,
                            items = items,
                            subtotal = subtotal,
                            deliveryFee = courierFee,
                            totalPrice = totalPrice,
                            status = status,
                            paymentMethod = paymentMethod,
                            paymentStatus = runCatching { child.child("paymentStatus").value<String>() }.getOrDefault("UNPAID"),
                            paymentId = runCatching { child.child("paymentId").value<String>() }.getOrDefault(""),
                            deliveryId = deliveryId,
                            deliveryName = deliveryName,
                            deliveryPhone = deliveryPhone,
                            cashCollectionStatus = runCatching { child.child("cashCollectionStatus").value<String>() }.getOrDefault(""),
                            cashCollectedBy = runCatching { child.child("cashCollectedBy").value<String>() }.getOrDefault(""),
                            cashCollectedByRole = runCatching { child.child("cashCollectedByRole").value<String>() }.getOrDefault(""),
                            cashCollectedAmountCentavos = runCatching { child.child("cashCollectedAmountCentavos").value<Long>() }.getOrDefault(0L),
                            cashMerchantOwedCentavos = runCatching { child.child("cashMerchantOwedCentavos").value<Long>() }.getOrDefault(0L),
                            cashPlatformOwedCentavos = runCatching { child.child("cashPlatformOwedCentavos").value<Long>() }.getOrDefault(0L),
                            cashCollectorEarningsCentavos = runCatching { child.child("cashCollectorEarningsCentavos").value<Long>() }.getOrDefault(0L),
                            cashRemittedCentavos = runCatching { child.child("cashRemittedCentavos").value<Long>() }.getOrDefault(0L),
                            cashOutstandingRemittanceCentavos = runCatching { child.child("cashOutstandingRemittanceCentavos").value<Long>() }.getOrDefault(0L),
                            cashRemittanceStatus = runCatching { child.child("cashRemittanceStatus").value<String>() }.getOrDefault(""),
                            pickupLocation = pickupLocation,
                            pickupLat = pickupLat,
                            pickupLng = pickupLng,
                            dropoffLocation = dropoffLocation,
                            dropoffLat = dropoffLat,
                            dropoffLng = dropoffLng,
                            createdAtTimestamp = createdAtTimestamp,
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
            serviceActions.transitionOrderStatus(order.id, nextStatus.name)
        }
    }
}