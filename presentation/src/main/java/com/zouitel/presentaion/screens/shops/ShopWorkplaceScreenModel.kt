package com.zouitel.presentaion.screens.shops

import dev.gitlive.firebase.functions.functions

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.common.UserMode
import com.zouitel.presentaion.common.FirebaseImageUploader
import com.zouitel.presentaion.common.PickedImage
import com.zouitel.presentaion.screens.food.ImageUploadState
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ShopWorkplaceScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val auth = Firebase.auth
    private val functions = Firebase.functions("asia-southeast1")

    private val _userShop = MutableStateFlow<Shop?>(null)
    val userShop: StateFlow<Shop?> = _userShop.asStateFlow()

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _imageUpload = MutableStateFlow<ImageUploadState>(ImageUploadState.Idle)
    val imageUpload: StateFlow<ImageUploadState> = _imageUpload.asStateFlow()
    private var uploadJob: Job? = null

    init {
        observeUserShopApplication()
        observeShopOnlineStatus()
    }

    private fun observeUserShopApplication() {
        val uid = auth.currentUser?.uid ?: run {
            _isLoading.value = false
            return
        }

        screenModelScope.launch {
            db.child("shops").orderByChild("ownerUid").equalTo(uid).valueEvents.collect { snapshot ->
                val myShop = snapshot.children.mapNotNull { child ->
                    try {
                        val id = child.key ?: return@mapNotNull null
                        val ownerUid: String = child.child("ownerUid").value()
                        if (ownerUid != uid) return@mapNotNull null

                        val name: String = child.child("name").value()
                        val description: String = child.child("description").value()
                        val category: String = child.child("category").value()
                        val phone: String = child.child("phone").value()
                        val address: String = child.child("address").value()
                        val imageUrl: String = child.child("imageUrl").value()
                        val statusStr: String = child.child("status").value()
                        val createdAtTimestamp: Long = child.child("createdAtTimestamp").value()
                        val isOnline: Boolean = child.child("isOnline").value()
                        val latitude = runCatching { child.child("latitude").value<Double>() }.getOrNull()
                        val longitude = runCatching { child.child("longitude").value<Double>() }.getOrNull()
                        val status = runCatching { Status.valueOf(statusStr) }.getOrDefault(Status.PENDING)

                        Shop(
                            id = id,
                            ownerUid = ownerUid,
                            name = name,
                            description = description,
                            category = category,
                            phone = phone,
                            address = address,
                            imageUrl = imageUrl,
                            status = status,
                            createdAtTimestamp = createdAtTimestamp,
                            isOnline = isOnline,
                            latitude = latitude,
                            longitude = longitude
                        )
                    } catch (e: Exception) {
                        null
                    }
                }.firstOrNull()

                _userShop.value = myShop
                _isLoading.value = false
            }
        }
    }

    private fun observeShopOnlineStatus() {
        val uid = auth.currentUser?.uid ?: return
        screenModelScope.launch {
            db.child("users").child(uid).child("isShopServiceOnline").valueEvents.collect { snapshot ->
                val online = runCatching { snapshot.value<Boolean>() }.getOrDefault(false)
                _isOnline.value = online
            }
        }
    }

    /**
     * Uploads the store photo/logo to Firebase Storage and exposes the URL
     * to store/display via [imageUpload].
     */
    fun uploadShopImage(image: PickedImage) {
        if (_imageUpload.value is ImageUploadState.Uploading) return
        _imageUpload.value = ImageUploadState.Uploading

        uploadJob = screenModelScope.launch {
            try {
                val url = FirebaseImageUploader.upload(
                    bytes = image.readBytes(),
                    ownerUid = auth.currentUser?.uid ?: throw IllegalStateException("Sign in to upload an image."),
                    category = "shop"
                )
                _imageUpload.value = ImageUploadState.Success(url)
            } catch (e: CancellationException) {
                // Reset only when abandoned from outside (e.g. the consent screen closed);
                // resetImageUpload() has already reset the state when it cancelled us.
                if (isActive) _imageUpload.value = ImageUploadState.Idle
                throw e
            } catch (e: Exception) {
                _imageUpload.value = ImageUploadState.Error(e.message ?: "Upload failed")
            }
        }
    }

    /** Clears the upload state and cancels an upload still in flight, so its result can't leak into the next form. */
    fun resetImageUpload() {
        uploadJob?.cancel()
        uploadJob = null
        _imageUpload.value = ImageUploadState.Idle
    }

    fun updateShopMode(shopMode: UserMode) {
        val uid = auth.currentUser?.uid ?: return
        screenModelScope.launch {
            db.child("users").child(uid).child("shopMode").setValue(shopMode.name)
        }
    }

    fun updateShopOnlineStatus(shopId: String, isOnline: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        screenModelScope.launch {
            // Update user profile service status (`isShopServiceOnline`)[cite: 41]
            db.child("users").child(uid).child("isShopServiceOnline").setValue(isOnline)
            // Update store active state
            db.child("shops").child(shopId).child("isOnline").setValue(isOnline)
        }
    }

    fun submitShopApplication(
        name: String,
        description: String,
        category: String,
        phone: String,
        address: String,
        imageUrl: String,
        isOnline: Boolean,
        latitude: Double?,
        longitude: Double?
    ) {
        val uid = auth.currentUser?.uid ?: return
        _isLoading.value = true

        screenModelScope.launch {
            runCatching {
                functions.httpsCallable("submitShopApplication")(
                    mapOf("name" to name, "description" to description, "category" to category,
                        "phone" to phone, "address" to address, "imageUrl" to imageUrl,
                        "latitude" to latitude, "longitude" to longitude)
                )
            }.onFailure { error ->
                _imageUpload.value = ImageUploadState.Error(error.message ?: "Application could not be submitted.")
            }
            _isLoading.value = false
        }
    }

    fun updateShopApplication(
        shopId: String,
        name: String,
        description: String,
        category: String,
        phone: String,
        address: String,
        imageUrl: String,
        latitude: Double?,
        longitude: Double?
    ) {
        _isLoading.value = true
        screenModelScope.launch {
            val updates = mapOf(
                "name" to name,
                "description" to description,
                "category" to category,
                "phone" to phone,
                "address" to address,
                "imageUrl" to imageUrl,
                "latitude" to latitude,
                "longitude" to longitude
            )
            db.child("shops").child(shopId).updateChildren(updates)
            _isLoading.value = false
        }
    }
}