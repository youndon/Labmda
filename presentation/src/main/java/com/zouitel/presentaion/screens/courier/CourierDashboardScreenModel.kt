package com.zouitel.presentaion.screens.courier

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CourierDashboardScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val currentUid: String? get() = Firebase.auth.currentUser?.uid

    private val _activeCouriers = MutableStateFlow<List<Courier>>(emptyList())
    val activeCouriers: StateFlow<List<Courier>> = _activeCouriers.asStateFlow()

    private val _completedCouriers = MutableStateFlow<List<Courier>>(emptyList())
    val completedCouriers: StateFlow<List<Courier>> = _completedCouriers.asStateFlow()

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

                        // Freight orders belong to the freight dashboard
                        val deliveryType: String = runCatching { child.child("deliveryType").value<String>() }.getOrDefault("")
                        if (deliveryType == "FREIGHT") return@mapNotNull null

                        val statusStr: String = runCatching { child.child("status").value<String>() }.getOrDefault("PLACED")
                        val status = runCatching { OrderStatus.valueOf(statusStr) }.getOrDefault(OrderStatus.PLACED)

                        if (status != OrderStatus.OUT_FOR_DELIVERY && status != OrderStatus.DELIVERED) {
                            return@mapNotNull null
                        }

                        val restaurantId: String = runCatching { child.child("shopId").value<String>() }
                            .getOrElse { runCatching { child.child("restaurantId").value<String>() }.getOrDefault("") }

                        val restaurantName: String = runCatching { child.child("shopName").value<String>() }
                            .getOrElse { runCatching { child.child("restaurantName").value<String>() }.getOrDefault("Store / Restaurant") }

                        val courierId: String = runCatching { child.child("courierId").value<String>() }.getOrDefault("")
                        val courierName: String = runCatching { child.child("courierName").value<String>() }.getOrDefault("")
                        val courierPhone: String = runCatching { child.child("courierPhone").value<String>() }.getOrDefault("")

                        val customerAddress: String = runCatching { child.child("deliveryAddress").value<String>() }.getOrDefault("")
                        val totalPrice: Double = runCatching { child.child("totalPrice").value<Double>() }.getOrDefault(0.0)
                        val courierFee: Double = runCatching { child.child("courierFee").value<Double>() }.getOrDefault(3.0)
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

                        Courier(
                            id = id,
                            orderId = id,
                            restaurantId = restaurantId,
                            restaurantName = restaurantName,
                            courierId = courierId,
                            courierName = courierName,
                            courierPhone = courierPhone,
                            customerAddress = customerAddress,
                            items = items,
                            totalPrice = totalPrice,
                            courierFee = courierFee,
                            status = status,
                            dispatchedAtTimestamp = dispatchedAt,
                            deliveredAtTimestamp = deliveredAt
                        )
                    } catch (e: Exception) {
                        null
                    }
                }

                val uid = currentUid

                // Available = unassigned, or assigned to me
                _activeCouriers.value = allTasks
                    .filter { it.status == OrderStatus.OUT_FOR_DELIVERY }
                    .filter { it.courierId.isBlank() || it.courierId == uid }
                    .sortedByDescending { it.dispatchedAtTimestamp }

                _completedCouriers.value = allTasks
                    .filter { it.status == OrderStatus.DELIVERED && (uid == null || it.courierId == uid) }
                    .sortedByDescending { it.deliveredAtTimestamp }

                _isLoading.value = false
            }
        }
    }

    // Claim using name/phone from the user's profile in the 'users' node
    fun claimCourierTask(courier: Courier) {
        val uid = currentUid ?: return

        screenModelScope.launch {
            // Don't overwrite an order another courier already took
            val existing = runCatching {
                db.child("orders").child(courier.orderId).child("courierId")
                    .valueEvents.first().value<String>()
            }.getOrDefault("")
            if (existing.isNotBlank() && existing != uid) return@launch

            val profile = db.child("users").child(uid).valueEvents.first()
            val name = runCatching { profile.child("fullName").value<String>() }
                .getOrElse { runCatching { profile.child("name").value<String>() }.getOrDefault("") }
            val phone = runCatching { profile.child("phone").value<String>() }.getOrDefault("")

            db.child("orders").child(courier.orderId).updateChildren(
                mapOf(
                    "courierId" to uid,
                    "courierName" to name.ifBlank { "Courier" },
                    "courierPhone" to phone
                )
            )
        }
    }

    fun rejectCourierTask(courier: Courier) {
        screenModelScope.launch {
            db.child("orders").child(courier.orderId).updateChildren(
                mapOf(
                    "courierId" to "",
                    "courierName" to "",
                    "courierPhone" to ""
                )
            )
        }
    }

    fun markCourierOrderDelivered(courier: Courier) {
        screenModelScope.launch {
            db.child("orders").child(courier.orderId).updateChildren(
                mapOf(
                    "status" to OrderStatus.DELIVERED.name,
                    "deliveredAtTimestamp" to System.currentTimeMillis()
                )
            )
        }
    }
}