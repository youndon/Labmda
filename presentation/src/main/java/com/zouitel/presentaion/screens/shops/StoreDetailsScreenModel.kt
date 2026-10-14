package com.zouitel.presentaion.screens.shops

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.model.CartItem
import com.zouitel.presentaion.model.Order
import com.zouitel.presentaion.model.OrderType
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StoreDetailsScreenModel(val shop: Shop) : ScreenModel {
    private val db = Firebase.database.reference()
    private val auth = Firebase.auth

    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        listenToStoreProducts()
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

    // Pushes the store order directly into the universal 'orders' node
    fun placeShopOrder(
        cartItems: List<ShopCartItem>,
        subtotal: Double,
        courierFee: Double,
        totalPrice: Double,
        deliveryAddress: String,
        paymentMethod: String,
        onSuccess: (String) -> Unit
    ) {
        val uid = auth.currentUser?.uid ?: "guest_client"
        screenModelScope.launch {
            val orderRef = db.child("orders").push()
            val orderId = orderRef.key ?: System.currentTimeMillis().toString()

            val mappedItems = cartItems.map { shopItem ->
                CartItem(
                    id = shopItem.productId,
                    name = shopItem.productName,
                    basePrice = shopItem.price,
                    quantity = shopItem.quantity,
                    specialInstructions = shopItem.specialInstructions
                )
            }

            val universalOrder = Order(
                id = orderId,
                clientId = uid,
                orderType = OrderType.RETAIL,
                sourceId = shop.id,
                sourceName = shop.name,
                items = mappedItems,
                subtotal = subtotal,
                deliveryFee = courierFee,
                totalPrice = totalPrice,
                clientAddress = deliveryAddress,
                createdAtTimestamp = System.currentTimeMillis(),
                paymentMethod = paymentMethod
            )

            orderRef.setValue(universalOrder)
            onSuccess(orderId)
        }
    }
}