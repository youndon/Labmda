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
import java.util.UUID

class ShopActivitiesScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val serviceActions = ServiceActionsRepository()
    private val currentUserId = Firebase.auth.currentUser?.uid

    private val _activities = MutableStateFlow<List<Order>>(emptyList())
    val activities: StateFlow<List<Order>> = _activities.asStateFlow()

    private val _refundNotice = MutableStateFlow<String?>(null)
    val refundNotice: StateFlow<String?> = _refundNotice.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        listenToUserShopActivities()
    }

    private fun listenToUserShopActivities() {
        val uid = currentUserId
        if (uid.isNullOrBlank()) {
            _activities.value = emptyList()
            _isLoading.value = false
            return
        }
        screenModelScope.launch {
            db.child("orders").orderByChild("clientId").equalTo(uid).valueEvents.collect { snapshot ->
                val fetchedOrders = snapshot.children.mapNotNull { child ->
                    try {
                        val id = child.key ?: return@mapNotNull null

                        val userId: String = runCatching { child.child("clientId").value<String>() }.getOrDefault("")

                        // If user is authenticated, filter by their clientId (allow guest if currentUserId is null)
                        if (currentUserId != null && userId.isNotBlank() && userId != currentUserId) {
                            return@mapNotNull null
                        }

                        val shopId = runCatching { child.child("shopId").value<String>() }
                            .getOrElse { runCatching { child.child("sourceId").value<String>() }.getOrDefault("") }
                        val shopName: String = runCatching { child.child("shopName").value<String>() }
                            .getOrElse { runCatching { child.child("sourceName").value<String>() }.getOrDefault("Store / Restaurant") }

                        val subtotal: Double = runCatching { child.child("subtotal").value<Double>() }.getOrDefault(0.0)
                        val courierFee: Double = runCatching { child.child("deliveryFee").value<Double>() }.getOrDefault(3.0)
                        val totalPrice: Double = runCatching { child.child("totalPrice").value<Double>() }.getOrDefault(0.0)
                        val statusStr: String = runCatching { child.child("status").value<String>() }.getOrDefault("PLACED")
                        val deliveryAddress: String = runCatching { child.child("fromLocation").value<String>() }.getOrDefault("")
                        val createdAtTimestamp: Long = runCatching { child.child("createdAtTimestamp").value<Long>() }.getOrDefault(0L)
                        val paymentMethod: String = runCatching { child.child("paymentMethod").value<String>() }.getOrDefault("Cash")

                        val status = runCatching { OrderStatus.valueOf(statusStr) }.getOrDefault(OrderStatus.PLACED)

                        val items = child.child("items").children.map { itemChild ->
                            val mealId = runCatching { itemChild.child("id").value<String>() }
                                .getOrElse { runCatching { itemChild.child("productId").value<String>() }.getOrDefault("") }
                            val mealName = runCatching { itemChild.child("name").value<String>() }
                                .getOrElse { runCatching { itemChild.child("productName").value<String>() }.getOrDefault("Item") }
                            val basePrice = runCatching { itemChild.child("basePrice").value<Double>() }
                                .getOrElse { runCatching { itemChild.child("price").value<Double>() }.getOrDefault(0.0) }
                            val quantity = runCatching { itemChild.child("quantity").value<Int>() }.getOrDefault(1)

                            CartItem(
                                id = mealId,
                                name = mealName,
                                basePrice = basePrice,
                                quantity = quantity
                            )
                        }

                        Order(
                            id = id,
                            clientId = userId,
                            orderType = OrderType.RETAIL,
                            sourceId = shopId,
                            sourceName = shopName,
                            items = items,
                            subtotal = subtotal,
                            deliveryFee = courierFee,
                            totalPrice = totalPrice,
                            status = status,
                            createdAtTimestamp = createdAtTimestamp,
                            paymentMethod = paymentMethod,
                        paymentStatus = runCatching { child.child("paymentStatus").value<String>() }.getOrDefault("UNPAID"),
                        paymentId = runCatching { child.child("paymentId").value<String>() }.getOrDefault("")
                        )
                    } catch (e: Exception) {
                        null
                    }
                }.sortedByDescending { it.createdAtTimestamp }

                _activities.value = fetchedOrders
                _isLoading.value = false
            }
        }
    }

    fun cancelShopOrder(orderId: String) {
        screenModelScope.launch {
            runCatching { serviceActions.transitionOrderStatus(orderId, OrderStatus.CANCELLED.name) }
                .onFailure { _refundNotice.value = it.message ?: "Could not cancel this order." }
        }
    }

    fun requestRefund(orderId: String) {
        screenModelScope.launch {
            runCatching { serviceActions.requestPaymentRefund("ORDER", orderId, UUID.randomUUID().toString()) }
                .onSuccess { _refundNotice.value = "Refund request submitted for administrator review. Your order has not been cancelled." }
                .onFailure { _refundNotice.value = it.message ?: "Could not submit refund request." }
        }
    }
}