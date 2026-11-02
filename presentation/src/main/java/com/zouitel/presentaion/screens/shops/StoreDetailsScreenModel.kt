package com.zouitel.presentaion.screens.shops

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class MapPoint(val lat: Double, val lng: Double)

class StoreDetailsScreenModel(val shop: Shop) : ScreenModel {
    private val db = Firebase.database.reference()
    private val auth = Firebase.auth

    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _shopLocation = MutableStateFlow<MapPoint?>(null)
    val shopLocation: StateFlow<MapPoint?> = _shopLocation.asStateFlow()

    init {
        listenToStoreProducts()
        loadShopLocation()
    }

    private fun loadShopLocation() {
        screenModelScope.launch {
            val shopRef = db.child("shops").child(shop.id)
            val lat = runCatching { shopRef.child("latitude").valueEvents.first().value<Double>() }.getOrNull()
            val lng = runCatching { shopRef.child("longitude").valueEvents.first().value<Double>() }.getOrNull()
            if (lat != null && lng != null && (lat != 0.0 || lng != 0.0)) {
                _shopLocation.value = MapPoint(lat, lng)
            }
        }
    }
    private fun listenToStoreProducts() {
        screenModelScope.launch {
            db.child("shops").child(shop.id).child("products").valueEvents.collect { snapshot ->
                val list = snapshot.children.mapNotNull { child ->
                    try {
                        val id = child.key ?: return@mapNotNull null
                        // Fixed: read "name" instead of "fullName"
                        val name: String = child.child("name").value()
                        val description: String = child.child("description").value()
                        val price: Double = child.child("price").value()
                        val category: String = child.child("category").value()
                        val imageUrl: String = child.child("imageUrl").value()
                        val isAvailable: Boolean = child.child("isAvailable").value()
                        val stockQuantity: Int = child.child("stockQuantity").value()

                        Product(
                            id = id,
                            shopId = shop.id,
                            name = name,
                            description = description,
                            price = price,
                            category = category,
                            imageUrl = imageUrl,
                            isAvailable = isAvailable,
                            stockQuantity = stockQuantity
                        )
                    } catch (e: Exception) {
                        null
                    }
                }.filter { it.isAvailable }

                _products.value = list
                _isLoading.value = false
            }
        }
    }

    fun placeShopOrder(
        cartItems: List<ShopCartItem>,
        subtotal: Double,
        courierFee: Double,
        totalPrice: Double,
        pickupLat: Double,
        pickupLng: Double,
        dropoffAddress: String,
        dropoffLat: Double,
        dropoffLng: Double,
        paymentMethod: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        if (auth.currentUser?.uid == null || cartItems.isEmpty()) return
        screenModelScope.launch {
            runCatching {
                val method = if (paymentMethod.contains("GCASH", ignoreCase = true)) "GCASH" else "CASH"
                val quote = com.zouitel.presentaion.screens.trustedTransactionCallData(
                    "quoteShopOrder",
                    mapOf(
                        "sourceId" to shop.id,
                        "items" to cartItems.map { item ->
                            mapOf("itemId" to item.productId, "quantity" to item.quantity)
                        },
                        "pickup" to mapOf("latitude" to pickupLat, "longitude" to pickupLng),
                        "dropoff" to mapOf("latitude" to dropoffLat, "longitude" to dropoffLng),
                        "pickupAddress" to shop.address,
                        "dropoffAddress" to dropoffAddress,
                        "paymentMethod" to method
                    )
                )
                val quoteId = quote["quoteId"] as? String
                    ?: error("The server did not return a shop order quote.")
                val created = com.zouitel.presentaion.screens.trustedTransactionCallData(
                    "createOrderFromQuote",
                    mapOf("quoteId" to quoteId, "kind" to "SHOP")
                )
                val orderId = created["orderId"] as? String
                    ?: error("The server did not return the new shop order ID.")
                onSuccess(orderId)
            }.onFailure { error -> onError(error.message ?: "Could not place the shop order.") }
        }
    }
}