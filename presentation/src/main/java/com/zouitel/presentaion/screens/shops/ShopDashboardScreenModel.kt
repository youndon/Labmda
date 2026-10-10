package com.zouitel.presentaion.screens.shops

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.OrderStatus
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ShopDashboardScreenModel(val shopId: String) : ScreenModel {
    private val db = Firebase.database.reference()

    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        listenToProducts()
    }

    private fun listenToProducts() {
        screenModelScope.launch {
            db.child("shops").child(shopId).child("products").valueEvents.collect { snapshot ->
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
                            shopId = shopId,
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
                }
                _products.value = list
                _isLoading.value = false
            }
        }
    }

    fun addOrUpdateProduct(
        productId: String?,
        name: String,
        description: String,
        price: Double,
        category: String,
        imageUrl: String,
        stockQuantity: Int,
        onSuccess: () -> Unit
    ) {
        screenModelScope.launch {
            val productRef = if (productId.isNullOrBlank()) {
                db.child("shops").child(shopId).child("products").push()
            } else {
                db.child("shops").child(shopId).child("products").child(productId)
            }

            val pId = productRef.key ?: System.currentTimeMillis().toString()
            val product = Product(
                id = pId,
                shopId = shopId,
                name = name,
                description = description,
                price = price,
                category = category,
                imageUrl = imageUrl,
                isAvailable = true,
                stockQuantity = stockQuantity
            )

            productRef.setValue(product)
            onSuccess()
        }
    }

    fun toggleProductAvailability(product: Product) {
        screenModelScope.launch {
            db.child("shops").child(shopId).child("products").child(product.id)
                .child("isAvailable").setValue(!product.isAvailable)
        }
    }

    fun deleteProduct(productId: String) {
        screenModelScope.launch {
            db.child("shops").child(shopId).child("products").child(productId).removeValue()
        }
    }

    fun updateShopOrderStatus(orderId: String, nextStatus: OrderStatus, order: ShopOrder) {
        if (nextStatus == OrderStatus.DELIVERED) return

        screenModelScope.launch {
            db.child("shop_orders").child(orderId).child("status").setValue(nextStatus.name)

//            if (nextStatus == OrderStatus.OUT_FOR_DELIVERY) {
//                val courierRecord = mapOf(
//                    "id" to order.id,
//                    "orderId" to order.id,
//                    "restaurantId" to order.shopId,
//                    "restaurantName" to order.shopName,
//                    "customerAddress" to order.deliveryAddress,
//                    "totalPrice" to order.totalPrice,
//                    "courierFee" to order.courierFee,
//                    "status" to OrderStatus.OUT_FOR_DELIVERY.name,
//                    "dispatchedAtTimestamp" to System.currentTimeMillis()
//                )
//                db.child("couriers").child(order.id).setValue(courierRecord)
//            }
        }
    }
}