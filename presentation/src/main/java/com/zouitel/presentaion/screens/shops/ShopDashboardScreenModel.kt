package com.zouitel.presentaion.screens.shops

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.FirebaseImageUploader
import com.zouitel.presentaion.common.PickedImage
import com.zouitel.presentaion.screens.food.ImageUploadState
import com.zouitel.presentaion.screens.food.MerchantDashboardMetrics
import com.zouitel.presentaion.screens.food.MerchantOrderSnapshot
import com.zouitel.presentaion.screens.food.calculateMerchantDashboardMetrics
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ShopDashboardScreenModel(val shopId: String) : ScreenModel {
    private val db = Firebase.database.reference()
    private val currentUid = Firebase.auth.currentUser?.uid

    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products.asStateFlow()

    private val _metrics = MutableStateFlow(MerchantDashboardMetrics())
    val metrics: StateFlow<MerchantDashboardMetrics> = _metrics.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _imageUpload = MutableStateFlow<ImageUploadState>(ImageUploadState.Idle)
    val imageUpload: StateFlow<ImageUploadState> = _imageUpload.asStateFlow()
    private var uploadJob: Job? = null

    init {
        listenToProducts()
        listenToOrders()
    }

    private fun listenToProducts() {
        screenModelScope.launch {
            db.child("shops").child(shopId).child("products").valueEvents
                .catch { _isLoading.value = false }
                .collect { snapshot ->
                    val list = snapshot.children.mapNotNull { child ->
                        try {
                            val id = child.key ?: return@mapNotNull null
                            Product(
                                id = id,
                                shopId = shopId,
                                name = child.child("name").value<String?>() ?: "",
                                description = child.child("description").value<String?>() ?: "",
                                price = child.child("price").value<Double?>() ?: 0.0,
                                category = child.child("category").value<String?>() ?: "General",
                                imageUrl = child.child("imageUrl").value<String?>() ?: "",
                                isAvailable = child.child("isAvailable").value<Boolean?>() ?: true,
                                stockQuantity = child.child("stockQuantity").value<Int?>() ?: 10
                            )
                        } catch (_: Exception) {
                            null
                        }
                    }
                    _products.value = list
                    _isLoading.value = false
                }
        }
    }

    private fun listenToOrders() {
        val uid = currentUid ?: return
        screenModelScope.launch {
            db.child("orders").orderByChild("merchantId").equalTo(uid).valueEvents
                .catch { _metrics.value = MerchantDashboardMetrics() }
                .collect { snapshot ->
                    val orders = snapshot.children.mapNotNull { child ->
                        runCatching {
                            MerchantOrderSnapshot(
                                status = child.child("status").value<String?>() ?: "PLACED",
                                salesAmount = child.child("subtotal").value<Double?>() ?: child.child("totalPrice").value<Double?>() ?: 0.0,
                                createdAtTimestamp = child.child("createdAtTimestamp").value<Long?>() ?: 0L
                            )
                        }.getOrNull()
                    }
                    _metrics.value = calculateMerchantDashboardMetrics(orders)
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
        if (name.isBlank() || price < 0.0 || stockQuantity < 0) return
        screenModelScope.launch {
            val productRef = if (productId.isNullOrBlank()) {
                db.child("shops").child(shopId).child("products").push()
            } else {
                db.child("shops").child(shopId).child("products").child(productId)
            }
            val pId = productRef.key ?: System.currentTimeMillis().toString()
            val existing = _products.value.firstOrNull { it.id == productId }
            val product = Product(
                id = pId,
                shopId = shopId,
                name = name.trim(),
                description = description.trim(),
                price = price,
                category = category.trim().ifBlank { "General" },
                imageUrl = imageUrl,
                isAvailable = if (stockQuantity == 0) false else (existing?.isAvailable ?: true),
                stockQuantity = stockQuantity
            )
            productRef.setValue(product)
            onSuccess()
        }
    }

    fun uploadProductImage(image: PickedImage) {
        if (_imageUpload.value is ImageUploadState.Uploading) return
        _imageUpload.value = ImageUploadState.Uploading
        uploadJob = screenModelScope.launch {
            try {
                val url = FirebaseImageUploader.upload(bytes = image.readBytes(), ownerUid = currentUid ?: throw IllegalStateException("Sign in to upload an image."),
                    category = "product")
                _imageUpload.value = ImageUploadState.Success(url)
            } catch (e: CancellationException) {
                if (isActive) _imageUpload.value = ImageUploadState.Idle
                throw e
            } catch (e: Exception) {
                _imageUpload.value = ImageUploadState.Error(e.message ?: "Upload failed")
            }
        }
    }

    fun resetImageUpload() {
        uploadJob?.cancel()
        uploadJob = null
        _imageUpload.value = ImageUploadState.Idle
    }

    fun toggleProductAvailability(product: Product) {
        screenModelScope.launch {
            val available = !product.isAvailable && product.stockQuantity > 0
            db.child("shops").child(shopId).child("products").child(product.id)
                .child("isAvailable").setValue(available)
        }
    }

    fun deleteProduct(productId: String) {
        screenModelScope.launch {
            db.child("shops").child(shopId).child("products").child(productId).removeValue()
        }
    }
}
