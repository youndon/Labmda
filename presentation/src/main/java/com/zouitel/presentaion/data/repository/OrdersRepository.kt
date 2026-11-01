package com.zouitel.presentaion.data.repository

import com.zouitel.presentaion.model.Order
import com.zouitel.presentaion.model.OrderType
import com.zouitel.presentaion.screens.food.toOrderOrNull
import dev.gitlive.firebase.database.DatabaseReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Realtime Database access for food and retail orders.
 *
 * This repository only observes order data. Mutations that affect payment or order
 * lifecycle remain delegated to trusted callable functions.
 */
class OrdersRepository(
    private val database: DatabaseReference
) {
    fun observeFoodOrders(restaurantId: String): Flow<List<Order>> =
        database.child("orders")
            .orderByChild("sourceId")
            .equalTo(restaurantId)
            .valueEvents
            .map { snapshot ->
                snapshot.children
                    .mapNotNull { it.toOrderOrNull() }
                    .filter { it.orderType == OrderType.FOOD }
                    .sortedByDescending { it.createdAtTimestamp }
            }

    fun observeShopOrders(merchantId: String): Flow<List<Order>> =
        database.child("orders")
            .orderByChild("merchantId")
            .equalTo(merchantId)
            .valueEvents
            .map { snapshot ->
                snapshot.children
                    .mapNotNull { it.toOrderOrNull(defaultOrderType = OrderType.RETAIL) }
                    .sortedByDescending { it.createdAtTimestamp }
            }

    fun observeClientFoodOrders(clientId: String): Flow<List<Order>> =
        database.child("orders")
            .orderByChild("clientId")
            .equalTo(clientId)
            .valueEvents
            .map { snapshot ->
                snapshot.children
                    .mapNotNull { it.toOrderOrNull() }
                    .filter { it.orderType == OrderType.FOOD }
                    .sortedByDescending { it.createdAtTimestamp }
            }

    fun observeClientShopOrders(clientId: String): Flow<List<Order>> =
        database.child("orders")
            .orderByChild("clientId")
            .equalTo(clientId)
            .valueEvents
            .map { snapshot ->
                snapshot.children
                    .mapNotNull { it.toOrderOrNull(defaultOrderType = OrderType.RETAIL) }
                    .filter { it.orderType == OrderType.RETAIL }
                    .sortedByDescending { it.createdAtTimestamp }
            }
}
