package com.zouitel.presentaion.screens.freight

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.CartItem
import com.zouitel.presentaion.common.OrderStatus
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FreightScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val currentUser = Firebase.auth.currentUser

    private val _activeFreights = MutableStateFlow<List<Freight>>(emptyList())
    val activeFreights: StateFlow<List<Freight>> = _activeFreights.asStateFlow()

    private val _completedFreights = MutableStateFlow<List<Freight>>(emptyList())
    val completedFreights: StateFlow<List<Freight>> = _completedFreights.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        listenToOrdersTable()
    }

    private fun listenToOrdersTable() {
        screenModelScope.launch {
            db.child("orders").valueEvents.collect { snapshot ->
                val allTasks = snapshot.children.mapNotNull { child ->
                    try {
                        val id = child.key ?: return@mapNotNull null

                        // Only freight-type orders belong here
                        val deliveryType: String = runCatching { child.child("deliveryType").value<String>() }.getOrDefault("")

                        val statusStr: String = runCatching { child.child("status").value<String>() }.getOrDefault("PLACED")
                        val status = runCatching { OrderStatus.valueOf(statusStr) }.getOrDefault(OrderStatus.PLACED)

                        if (status != OrderStatus.OUT_FOR_DELIVERY && status != OrderStatus.DELIVERED) {
                            return@mapNotNull null
                        }

                        val businessId: String = runCatching { child.child("shopId").value<String>() }
                            .getOrElse { runCatching { child.child("restaurantId").value<String>() }.getOrDefault("") }

                        val businessName: String = runCatching { child.child("shopName").value<String>() }
                            .getOrElse { runCatching { child.child("restaurantName").value<String>() }.getOrDefault("Store") }

                        val freightId: String = runCatching { child.child("freightId").value<String>() }.getOrDefault("")
                        val freightName: String = runCatching { child.child("freightName").value<String>() }.getOrDefault("")
                        val freightPhone: String = runCatching { child.child("freightPhone").value<String>() }.getOrDefault("")

                        val customerAddress: String = runCatching { child.child("deliveryAddress").value<String>() }.getOrDefault("")
                        val totalPrice: Double = runCatching { child.child("totalPrice").value<Double>() }.getOrDefault(0.0)
                        val freightFee: Double = runCatching { child.child("freightFee").value<Double>() }.getOrDefault(10.0)
                        val dispatchedAt: Long = runCatching { child.child("createdAtTimestamp").value<Long>() }.getOrDefault(0L)
                        val deliveredAt: Long = runCatching { child.child("deliveredAtTimestamp").value<Long>() }.getOrDefault(0L)

                        val items = child.child("items").children.map { itemChild ->
                            CartItem(
                                id = runCatching { itemChild.child("productId").value<String>() }
                                    .getOrElse { runCatching { itemChild.child("mealId").value<String>() }.getOrDefault("") },
                                name = runCatching { itemChild.child("productName").value<String>() }
                                    .getOrElse { runCatching { itemChild.child("mealName").value<String>() }.getOrDefault("Item") },
                                basePrice = runCatching { itemChild.child("price").value<Double>() }
                                    .getOrElse { runCatching { itemChild.child("basePrice").value<Double>() }.getOrDefault(0.0) },
                                quantity = runCatching { itemChild.child("quantity").value<Int>() }.getOrDefault(1)
                            )
                        }

                        Freight(
                            id = id,
                            orderId = id,
                            businessId = businessId,
                            businessName = businessName,
                            freightId = freightId,
                            freightName = freightName,
                            freightPhone = freightPhone,
                            customerAddress = customerAddress,
                            items = items,
                            totalPrice = totalPrice,
                            freightFee = freightFee,
                            status = status,
                            dispatchedAtTimestamp = dispatchedAt,
                            deliveredAtTimestamp = deliveredAt
                        )
                    } catch (e: Exception) {
                        null
                    }
                }

                val uid = currentUser?.uid

                // Available = unassigned, or assigned to me
                _activeFreights.value = allTasks
                    .filter { it.status == OrderStatus.OUT_FOR_DELIVERY }
                    .filter { it.freightId.isBlank() || it.freightId == uid }
                    .sortedByDescending { it.dispatchedAtTimestamp }

                _completedFreights.value = allTasks
                    .filter { it.status == OrderStatus.DELIVERED && (uid == null || it.freightId == uid) }
                    .sortedByDescending { it.deliveredAtTimestamp }

                _isLoading.value = false
            }
        }
    }

    fun claimFreightTask(freight: Freight) {
        val updates = mapOf(
            "freightId" to (currentUser?.uid ?: "freight_default"),
            "freightName" to (currentUser?.displayName ?: "Active Freight Driver"),
            "freightPhone" to (currentUser?.phoneNumber ?: "")
        )
        screenModelScope.launch {
            db.child("orders").child(freight.orderId).updateChildren(updates)
        }
    }

    fun rejectFreightTask(freight: Freight) {
        val updates = mapOf(
            "freightId" to "",
            "freightName" to "",
            "freightPhone" to ""
        )
        screenModelScope.launch {
            db.child("orders").child(freight.orderId).updateChildren(updates)
        }
    }

    fun markFreightOrderDelivered(freight: Freight) {
        val updates = mapOf(
            "status" to OrderStatus.DELIVERED.name,
            "deliveredAtTimestamp" to System.currentTimeMillis()
        )
        screenModelScope.launch {
            db.child("orders").child(freight.orderId).updateChildren(updates)
        }
    }
}