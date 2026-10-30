package com.zouitel.presentaion.screens.delivery

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.model.CartItem
import com.zouitel.presentaion.model.Order
import com.zouitel.presentaion.model.OrderStatus
import com.zouitel.presentaion.model.OrderType
import com.zouitel.presentaion.screens.payment.PaymentRepository
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Lightweight lat/lng so the model stays free of map types. */
data class OrderPoint(val lat: Double, val lng: Double)

class DeliveryDashboardScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val payments = PaymentRepository()
    private val currentUid: String? get() = Firebase.auth.currentUser?.uid

    private val _activeDeliveries = MutableStateFlow<List<Order>>(emptyList())
    val activeDeliveries: StateFlow<List<Order>> = _activeDeliveries.asStateFlow()

    private val _completedDeliveries = MutableStateFlow<List<Order>>(emptyList())
    val completedDeliveries: StateFlow<List<Order>> = _completedDeliveries.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    private val _cashActionError = MutableStateFlow<String?>(null)
    val cashActionError: StateFlow<String?> = _cashActionError.asStateFlow()
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
//
//    private val _orderPoints = MutableStateFlow<Map<String, OrderPoint>>(emptyMap())
//    val orderPoints: StateFlow<Map<String, OrderPoint>> = _orderPoints.asStateFlow()

    init {
        listenToOrdersTable()
    }

    private fun listenToOrdersTable() {
        val uid = currentUid
        if (uid.isNullOrBlank()) {
            _activeDeliveries.value = emptyList()
            _completedDeliveries.value = emptyList()
            _isLoading.value = false
            return
        }

        val orders = db.child("orders")
        val openTasks = orders.orderByChild("deliveryId").equalTo("").valueEvents
        val assignedTasks = orders.orderByChild("deliveryId").equalTo(uid).valueEvents

        screenModelScope.launch(Dispatchers.IO) {
            combine(openTasks, assignedTasks) { openSnapshot, assignedSnapshot ->
                openSnapshot.children + assignedSnapshot.children
            }.collect { snapshots ->
                val orderSnapshots = snapshots.distinctBy { it.key }
                val allTasks = orderSnapshots.mapNotNull { child ->
                    try {
                        val id = child.key ?: return@mapNotNull null

                        val deliveryType: String = runCatching { child.child("deliveryType").value<String>() }
                            .getOrElse { runCatching { child.child("deliveryType").value<String>() }.getOrDefault("") }
                        if (deliveryType == "FREIGHT") return@mapNotNull null

                        val statusStr: String = runCatching { child.child("status").value<String>() }.getOrDefault("PLACED")
                        val status = runCatching { OrderStatus.valueOf(statusStr) }.getOrDefault(OrderStatus.PLACED)

                        if (status != OrderStatus.OUT_FOR_DELIVERY && status != OrderStatus.DELIVERED) {
                            return@mapNotNull null
                        }

                        val clientId: String = runCatching { child.child("clientId").value<String>() }.getOrDefault("")
                        val sourceId: String = runCatching { child.child("shopId").value<String>() }
                            .getOrElse { runCatching { child.child("sourceId").value<String>() }.getOrDefault("") }

                        val sourceName: String = runCatching { child.child("shopName").value<String>() }
                            .getOrElse { runCatching { child.child("sourceName").value<String>() }.getOrDefault("Store / Restaurant") }

                        val deliveryId: String = runCatching { child.child("deliveryId").value<String>() }.getOrDefault("")
                        val deliveryName: String = runCatching { child.child("deliveryName").value<String>() }.getOrDefault("")
                        val deliveryPhone: String = runCatching { child.child("deliveryPhone").value<String>() }.getOrDefault("")

                        val clientAddress: String = runCatching { child.child("fromLocation").value<String>() }.getOrDefault("")
                        val subtotal: Double = runCatching { child.child("subtotal").value<Double>() }.getOrDefault(0.0)
                        val totalPrice: Double = runCatching { child.child("totalPrice").value<Double>() }.getOrDefault(0.0)
                        val deliveryFee: Double = runCatching { child.child("deliveryFee").value<Double>() }.getOrDefault(3.0)
                        val createdAt: Long = runCatching { child.child("createdAtTimestamp").value<Long>() }.getOrDefault(0L)
                        val paymentMethod: String = runCatching { child.child("paymentMethod").value<String>() }.getOrDefault("")

                        val pickupLocation: String = runCatching { child.child("pickupLocation").value<String>() }.getOrDefault("")
                        val pickupLat: Double = runCatching { child.child("pickupLat").value<Double>() }.getOrDefault(0.0)
                        val pickupLng: Double = runCatching { child.child("pickupLng").value<Double>() }.getOrDefault(0.0)
                        val dropoffLocation: String = runCatching { child.child("dropoffLocation").value<String>() }.getOrDefault("")
                        val dropoffLat: Double = runCatching { child.child("dropoffLat").value<Double>() }.getOrDefault(0.0)
                        val dropoffLng: Double = runCatching { child.child("dropoffLng").value<Double>() }.getOrDefault(0.0)

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

                        Order(
                            id = id,
                            clientId = clientId,
                            orderType = OrderType.RETAIL,
                            sourceId = sourceId,
                            sourceName = sourceName,
                            items = items,
                            subtotal = subtotal,
                            deliveryFee = deliveryFee,
                            totalPrice = totalPrice,
                            status = status,
                            createdAtTimestamp = createdAt,
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
                            pickupLocation = pickupLocation, pickupLat = pickupLat, pickupLng = pickupLng,
                            dropoffLocation = dropoffLocation, dropoffLat = dropoffLat, dropoffLng = dropoffLng,
                        )
                    } catch (e: Exception) {
                        null
                    }
                }

                val points = mutableMapOf<String, OrderPoint>()
                orderSnapshots.forEach { child ->
                    val id = child.key ?: return@forEach
                    val lat = runCatching { child.child("clientLat").value<Double>() }.getOrNull()
                    val lng = runCatching { child.child("clientLng").value<Double>() }.getOrNull()
                    if (lat != null && lng != null && (lat != 0.0 || lng != 0.0)) {
                        points[id] = OrderPoint(lat, lng)
                    }
                }
//                _orderPoints.value = points

                val uid = currentUid

                _activeDeliveries.value = allTasks
                    .filter { it.status == OrderStatus.OUT_FOR_DELIVERY }
                    .filter { it.deliveryId.isBlank() || it.deliveryId == uid }
                    .sortedByDescending { it.createdAtTimestamp }

                _completedDeliveries.value = allTasks
                    .filter { it.status == OrderStatus.DELIVERED && (uid == null || it.deliveryId == uid) }
                    .sortedByDescending { it.createdAtTimestamp }

                _isLoading.value = false
            }
        }
    }

    fun claimCourierTask(order: Order) {
        val uid = currentUid ?: return

        screenModelScope.launch(Dispatchers.IO) {
            val existing = runCatching {
                db.child("orders").child(order.id).child("deliveryId")
                    .valueEvents.first().value<String>()
            }.getOrDefault("")
            if (existing.isNotBlank() && existing != uid) return@launch

            val profileSnapshot = db.child("users").child(uid).valueEvents.first()
            val name = runCatching { profileSnapshot.child("fullName").value<String>() }
                .getOrElse { runCatching { profileSnapshot.child("name").value<String>() }.getOrDefault("") }
            val phone = runCatching { profileSnapshot.child("phoneNumber").value<String>() }
                .getOrElse { runCatching { profileSnapshot.child("phone").value<String>() }.getOrDefault("") }

            db.child("orders").child(order.id).updateChildren(
                mapOf(
                    "deliveryId" to uid,
                    "deliveryName" to name.ifBlank { "Delivery" },
                    "deliveryPhone" to phone
                )
            )
        }
    }

    fun rejectCourierTask(order: Order) {
        screenModelScope.launch(Dispatchers.IO) {
            db.child("orders").child(order.id).updateChildren(
                mapOf(
                    "deliveryId" to "",
                    "deliveryName" to "",
                    "deliveryPhone" to ""
                )
            )
        }
    }

    fun markCourierOrderDelivered(order: Order) {
        screenModelScope.launch(Dispatchers.IO) {
            if (order.paymentMethod.trim().equals("CASH", ignoreCase = true)) {
                // Cash amount and split are read from the server-owned order record.
                // The callable also changes the order status to DELIVERED.
                runCatching { payments.confirmCashCollected("ORDER", order.id) }
                    .onFailure { error ->
                        _cashActionError.value = error.message ?: "Cash collection could not be confirmed."
                    }
            } else {
                db.child("orders").child(order.id).child("status")
                    .setValue(OrderStatus.DELIVERED.name)
            }
        }
    }
}