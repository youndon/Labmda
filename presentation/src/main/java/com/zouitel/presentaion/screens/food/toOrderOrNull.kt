package com.zouitel.presentaion.screens.food

import com.zouitel.presentaion.model.CartItem
import com.zouitel.presentaion.model.Order
import com.zouitel.presentaion.model.OrderStatus
import com.zouitel.presentaion.model.OrderType
import dev.gitlive.firebase.database.DataSnapshot

/** Tolerant shared parser for current and legacy order records. */
internal fun DataSnapshot.toOrderOrNull(
    defaultOrderType: OrderType = OrderType.FOOD
): Order? {
    val id = key ?: return null

    fun str(vararg names: String, default: String = ""): String {
        names.forEach { name ->
            val value = runCatching { child(name).value<String>() }.getOrNull()
            if (!value.isNullOrBlank()) return value
        }
        return default
    }

    fun number(vararg names: String): Double {
        names.forEach { name ->
            runCatching { child(name).value<Double>() }.getOrNull()?.let { return it }
            runCatching { child(name).value<Long>().toDouble() }.getOrNull()?.let { return it }
            runCatching { child(name).value<Int>().toDouble() }.getOrNull()?.let { return it }
        }
        return 0.0
    }

    fun long(vararg names: String): Long {
        names.forEach { name ->
            runCatching { child(name).value<Long>() }.getOrNull()?.let { return it }
            runCatching { child(name).value<Int>().toLong() }.getOrNull()?.let { return it }
        }
        return 0L
    }

    fun int(snapshot: DataSnapshot, vararg names: String): Int {
        names.forEach { name ->
            runCatching { snapshot.child(name).value<Int>() }.getOrNull()?.let { return it }
            runCatching { snapshot.child(name).value<Long>().toInt() }.getOrNull()?.let { return it }
        }
        return 1
    }

    val status = runCatching { OrderStatus.valueOf(str("status")) }
        .getOrDefault(OrderStatus.PLACED)
    val orderType = runCatching { OrderType.valueOf(str("orderType")) }
        .getOrDefault(defaultOrderType)

    val items = child("items").children.map { item ->
        CartItem(
            id = strFrom(item, "id", "productId", "mealId"),
            name = strFrom(item, "name", "productName", "mealName", default = "Item"),
            basePrice = numberFrom(item, "basePrice", "price"),
            quantity = int(item, "quantity"),
            specialInstructions = runCatching { item.child("specialInstructions").value<String>() }.getOrDefault("")
        )
    }

    return Order(
        id = id,
        clientId = str("clientId"),
        orderType = orderType,
        sourceId = str("sourceId", "shopId"),
        sourceName = str("sourceName", "shopName", default = "Store / Restaurant"),
        items = items,
        subtotal = number("subtotal"),
        deliveryFee = number("deliveryFee", "courierFee"),
        totalPrice = number("totalPrice"),
        status = status,
        pickupLocation = str("pickupLocation"),
        pickupLat = number("pickupLat"),
        pickupLng = number("pickupLng"),
        dropoffLocation = str("dropoffLocation", "deliveryAddress", "fromLocation"),
        dropoffLat = number("dropoffLat"),
        dropoffLng = number("dropoffLng"),
        createdAtTimestamp = long("createdAtTimestamp", "createdAt"),
        paymentMethod = str("paymentMethod"),
        paymentStatus = str("paymentStatus", default = "UNPAID"),
        paymentId = str("paymentId"),
        deliveryId = str("deliveryId"),
        deliveryName = str("deliveryName"),
        deliveryPhone = str("deliveryPhone"),
        deliveryStage = str("deliveryStage", default = "TO_PICKUP"),
        cashCollectionStatus = str("cashCollectionStatus"),
        cashCollectedBy = str("cashCollectedBy"),
        cashCollectedByRole = str("cashCollectedByRole"),
        cashCollectedAmountCentavos = long("cashCollectedAmountCentavos"),
        cashMerchantOwedCentavos = long("cashMerchantOwedCentavos"),
        cashPlatformOwedCentavos = long("cashPlatformOwedCentavos"),
        cashCollectorEarningsCentavos = long("cashCollectorEarningsCentavos"),
        cashRemittedCentavos = long("cashRemittedCentavos"),
        cashOutstandingRemittanceCentavos = long("cashOutstandingRemittanceCentavos"),
        cashRemittanceStatus = str("cashRemittanceStatus")
    )
}

private fun strFrom(snapshot: DataSnapshot, vararg names: String, default: String = ""): String {
    names.forEach { name ->
        val value = runCatching { snapshot.child(name).value<String>() }.getOrNull()
        if (!value.isNullOrBlank()) return value
    }
    return default
}

private fun numberFrom(snapshot: DataSnapshot, vararg names: String): Double {
    names.forEach { name ->
        runCatching { snapshot.child(name).value<Double>() }.getOrNull()?.let { return it }
        runCatching { snapshot.child(name).value<Long>().toDouble() }.getOrNull()?.let { return it }
        runCatching { snapshot.child(name).value<Int>().toDouble() }.getOrNull()?.let { return it }
    }
    return 0.0
}
