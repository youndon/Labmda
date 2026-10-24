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
    fun firstString(vararg names: String): String =
        names.firstNotNullOfOrNull { name -> runCatching { child(name).value<String>() }.getOrNull()?.takeIf { it.isNotBlank() } } ?: ""
    fun dbl(snap: DataSnapshot, name: String): Double =
        runCatching { snap.child(name).value<Double>() }
            .getOrElse { runCatching { snap.child(name).value<Long>().toDouble() }
                .getOrElse { runCatching { snap.child(name).value<Int>().toDouble() }.getOrDefault(0.0) } }
    fun firstDbl(snap: DataSnapshot, vararg names: String): Double =
        names.firstNotNullOfOrNull { name -> runCatching { snap.child(name).value<Double>() }.getOrNull()
            ?: runCatching { snap.child(name).value<Long>().toDouble() }.getOrNull()
            ?: runCatching { snap.child(name).value<Int>().toDouble() }.getOrNull() } ?: 0.0

    val status = runCatching { OrderStatus.valueOf(str("status")) }.getOrDefault(OrderStatus.PLACED)
    val typeValue = firstString("orderType")
    val orderType = runCatching { OrderType.valueOf(typeValue) }
        .getOrElse { if (firstString("shopId").isNotBlank()) OrderType.RETAIL else OrderType.FOOD }

    val items = child("items").children.map { itemChild ->
        CartItem(
            id = runCatching { itemChild.child("id").value<String>() }
                .getOrElse { runCatching { itemChild.child("productId").value<String>() }.getOrElse { runCatching { itemChild.child("mealId").value<String>() }.getOrDefault("") } },
            name = runCatching { itemChild.child("name").value<String>() }
                .getOrElse { runCatching { itemChild.child("productName").value<String>() }.getOrElse { runCatching { itemChild.child("mealName").value<String>() }.getOrDefault("Item") } },
            basePrice = firstDbl(itemChild, "basePrice", "price"),
            quantity = runCatching { itemChild.child("quantity").value<Int>() }
                .getOrElse { runCatching { itemChild.child("quantity").value<Long>().toInt() }.getOrDefault(1) },
            specialInstructions = runCatching { itemChild.child("specialInstructions").value<String>() }.getOrDefault("")
        )
    }

    return Order(
        id = id,
        clientId = str("clientId"),
        orderType = orderType,
        sourceId = firstString("sourceId", "shopId"),
        sourceName = firstString("sourceName", "shopName"),
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
        deliveryId = str("deliveryId"),
        deliveryName = str("deliveryName"),
        deliveryPhone = str("deliveryPhone")
    )
}