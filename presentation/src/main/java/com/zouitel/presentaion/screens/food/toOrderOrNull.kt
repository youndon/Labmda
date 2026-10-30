package com.zouitel.presentaion.screens.food

import com.zouitel.presentaion.model.CartItem
import com.zouitel.presentaion.model.Order
import com.zouitel.presentaion.model.OrderStatus
import com.zouitel.presentaion.model.OrderType
import dev.gitlive.firebase.database.DataSnapshot

/** Tolerant parser: a missing or oddly typed field never drops the whole order. */
internal fun DataSnapshot.toOrderOrNull(): Order? {
    val id = key ?: return null

    fun str(name: String) = runCatching { child(name).value<String>() }.getOrDefault("")
    fun dbl(snap: DataSnapshot, name: String): Double =
        runCatching { snap.child(name).value<Double>() }
            .getOrElse { runCatching { snap.child(name).value<Long>().toDouble() }.getOrDefault(0.0) }

    val status = runCatching { OrderStatus.valueOf(str("status")) }.getOrDefault(OrderStatus.PLACED)
    val orderType = runCatching { OrderType.valueOf(str("orderType")) }.getOrDefault(OrderType.FOOD)

    val items = child("items").children.map { itemChild ->
        CartItem(
            id = runCatching { itemChild.child("id").value<String>() }.getOrDefault(""),
            name = runCatching { itemChild.child("name").value<String>() }.getOrDefault("Item"),
            basePrice = dbl(itemChild, "basePrice"),
            quantity = runCatching { itemChild.child("quantity").value<Int>() }
                .getOrElse { runCatching { itemChild.child("quantity").value<Long>().toInt() }.getOrDefault(1) },
            specialInstructions = runCatching { itemChild.child("specialInstructions").value<String>() }.getOrDefault("")
        )
    }

    return Order(
        id = id,
        clientId = str("clientId"),
        orderType = orderType,
        sourceId = str("sourceId"),
        sourceName = str("sourceName"),
        items = items,
        subtotal = dbl(this, "subtotal"),
        deliveryFee = dbl(this, "deliveryFee"),
        totalPrice = dbl(this, "totalPrice"),
        status = status,
        pickupLocation = str("pickupLocation"),
        pickupLat = dbl(this, "pickupLat"),
        pickupLng = dbl(this, "pickupLng"),
        dropoffLocation = str("dropoffLocation"),
        dropoffLat = dbl(this, "dropoffLat"),
        dropoffLng = dbl(this, "dropoffLng"),
        createdAtTimestamp = runCatching { child("createdAtTimestamp").value<Long>() }.getOrDefault(0L),
        paymentMethod = str("paymentMethod"),
        paymentStatus = str("paymentStatus").ifBlank { "UNPAID" },
        paymentId = str("paymentId"),
        deliveryId = str("deliveryId"),
        deliveryName = str("deliveryName"),
        deliveryPhone = str("deliveryPhone")
    )
}