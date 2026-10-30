package com.zouitel.presentaion.screens.shops

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.screens.payment.PaymentCreationResult
import com.zouitel.presentaion.screens.payment.PaymentRepository
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

data class MapPoint(val lat: Double, val lng: Double)

class StoreDetailsScreenModel(
    val shop: Shop,
    private val payments: PaymentRepository = PaymentRepository()
) : ScreenModel {
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

    /**
     * The UI may show an estimate, but it does not send totals or item prices to the backend.
     * The backend reloads current product prices and delivery pricing before creating the order.
     */
    fun placeShopOrder(
        cartItems: List<ShopCartItem>,
        pickupLat: Double,
        pickupLng: Double,
        dropoffAddress: String,
        dropoffLat: Double,
        dropoffLng: Double,
        paymentMethod: String,
        onSuccess: (PaymentCreationResult) -> Unit,
        onFailure: (String) -> Unit = {}
    ) {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            onFailure("Please sign in to place an order.")
            return
        }
        if (cartItems.isEmpty()) {
            onFailure("Your cart is empty.")
            return
        }

        screenModelScope.launch {
            try {
                val result = payments.createOrder(
                    idempotencyKey = UUID.randomUUID().toString(),
                    orderType = "RETAIL",
                    sourceId = shop.id,
                    items = cartItems.map { item ->
                        mapOf(
                            "id" to item.productId,
                            "quantity" to item.quantity,
                            "specialInstructions" to item.specialInstructions
                        )
                    },
                    paymentMethod = paymentMethod,
                    pickupLocation = shop.address,
                    pickupLat = pickupLat,
                    pickupLng = pickupLng,
                    dropoffLocation = dropoffAddress,
                    dropoffLat = dropoffLat,
                    dropoffLng = dropoffLng
                )
                onSuccess(result)
            } catch (e: Exception) {
                onFailure(e.message ?: "Could not place the order. Please try again.")
            }
        }
    }
}