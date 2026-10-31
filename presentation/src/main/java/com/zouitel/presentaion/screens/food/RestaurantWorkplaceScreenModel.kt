package com.zouitel.presentaion.screens.food

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.common.UserMode
import com.zouitel.presentaion.common.FirebaseImageUploader
import com.zouitel.presentaion.common.PickedImage
import com.zouitel.presentaion.model.UserProfile
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ManagedRestaurantInfo(
    val id: String = "",
    val name: String = "",
    val imageUrl: String = "",
    val deliveryFee: Double = 0.0,
    val estimatedTimeMinutes: Int = 20,
    val managerPhone: String = "",
    val address: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)

sealed interface ImageUploadState {
    data object Idle : ImageUploadState
    data object Uploading : ImageUploadState
    data class Success(val url: String) : ImageUploadState
    data class Error(val message: String) : ImageUploadState
}

class RestaurantWorkplaceScreenModel(
    private val repository: FoodRepository = FoodRepository()
) : ScreenModel {
    private val db = Firebase.database.reference()
    private val auth = Firebase.auth

    private val _profile = MutableStateFlow(UserProfile())
    val profile: StateFlow<UserProfile> = _profile.asStateFlow()

    private val _restaurantInfo = MutableStateFlow(ManagedRestaurantInfo())
    val restaurantInfo: StateFlow<ManagedRestaurantInfo> = _restaurantInfo.asStateFlow()

    private val _meals = MutableStateFlow<List<Meal>>(emptyList())
    val meals: StateFlow<List<Meal>> = _meals.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        checkRestaurantStatus()
    }
    private var mealsJob: Job? = null

    private fun checkRestaurantStatus() {
        val uid = auth.currentUser?.uid ?: run {
            _isLoading.value = false
            return
        }

        screenModelScope.launch(Dispatchers.IO) {
            db.child("users").child(uid).valueEvents.collect { snapshot ->
                if (snapshot.exists) {
                    val name = runCatching { snapshot.child("fullName").value<String>() }
                        .getOrElse { runCatching { snapshot.child("name").value<String>() }.getOrDefault("") }
                    val phone = runCatching { snapshot.child("phoneNumber").value<String>() }
                        .getOrElse { runCatching { snapshot.child("phone").value<String>() }.getOrDefault("") }

                    val foodModeStr = runCatching { snapshot.child("foodMode").value<String>() }.getOrDefault("CLIENT")
                    val foodMode = runCatching { UserMode.valueOf(foodModeStr) }.getOrDefault(UserMode.CLIENT)

                    val statusStr = runCatching { snapshot.child("foodStatus").value<String>() }.getOrDefault("NONE")
                    val foodStatus = runCatching { Status.valueOf(statusStr) }.getOrDefault(Status.NONE)

                    val isOnline = runCatching { snapshot.child("isFoodServiceOnline").value<Boolean>() }.getOrDefault(false)

                    _profile.value = UserProfile(
                        uid = uid,
                        fullName = name,
                        phoneNumber = phone,
                        foodMode = foodMode,
                        foodStatus = foodStatus,
                        isFoodServiceOnline = isOnline
                    )

                    if (foodStatus == Status.APPROVED) {
                        _restaurantInfo.value = ManagedRestaurantInfo(
                            id = uid,
                            name = runCatching { snapshot.child("sourceName").value<String>() }.getOrDefault("My Restaurant"),
                            imageUrl = runCatching { snapshot.child("restaurantImageUrl").value<String>() }.getOrDefault(""),
                            deliveryFee = runCatching { snapshot.child("deliveryFee").value<Double>() }.getOrDefault(0.0),
                            estimatedTimeMinutes = runCatching { snapshot.child("estimatedTimeMinutes").value<Int>() }.getOrDefault(20),
                            managerPhone = runCatching { snapshot.child("managerPhone").value<String>() }.getOrDefault(""),
                            address = runCatching { snapshot.child("restaurantAddress").value<String>() }.getOrDefault(""),
                            latitude = runCatching { snapshot.child("restaurantLatitude").value<Double>() }.getOrDefault(0.0),
                            longitude = runCatching { snapshot.child("restaurantLongitude").value<Double>() }.getOrDefault(0.0)
                        )
                        if (mealsJob == null) observeMeals(uid)
                    }
                } else {
                    _profile.value = UserProfile(uid = uid)
                }
                _isLoading.value = false
            }
        }
    }

    private fun observeMeals(restaurantId: String) {
        mealsJob = screenModelScope.launch(Dispatchers.IO) {
            repository.getMealsForRestaurant(restaurantId).collect { _meals.value = it }
        }
    }

    private fun fetchManagedRestaurantDetails(uid: String) {
        screenModelScope.launch(Dispatchers.IO) {
            db.child("users").child(uid).valueEvents.collect { snapshot ->
                val restaurantName = runCatching { snapshot.child("sourceName").value<String>() }.getOrDefault("My Restaurant")
                val imageUrl = runCatching { snapshot.child("restaurantImageUrl").value<String>() }.getOrDefault("")
                val deliveryFee = runCatching { snapshot.child("deliveryFee").value<Double>() }.getOrDefault(0.0)
                val estimatedTime = runCatching { snapshot.child("estimatedTimeMinutes").value<Int>() }.getOrDefault(20)
                val managerPhone = runCatching { snapshot.child("managerPhone").value<String>() }.getOrDefault("")

                _restaurantInfo.value = ManagedRestaurantInfo(
                    id = uid,
                    name = restaurantName,
                    imageUrl = imageUrl,
                    deliveryFee = deliveryFee,
                    estimatedTimeMinutes = estimatedTime,
                    managerPhone = managerPhone
                )
            }
        }
    }

    fun updateFoodMode(foodMode: UserMode) {
        val uid = auth.currentUser?.uid ?: return
        screenModelScope.launch(Dispatchers.IO) {
            db.child("users").child(uid).child("foodMode").setValue(foodMode.name)
        }
    }

    fun submitApplication(
        restaurantName: String,
        imageUrl: String,
        deliveryFee: Double,
        estimatedTimeMinutes: Int,
        managerPhone: String,
        address: String,
        latitude: Double,
        longitude: Double
    ) {
        val uid = auth.currentUser?.uid ?: return
        _isLoading.value = true

        screenModelScope.launch(Dispatchers.IO) {
            val updates = mapOf(
                "sourceName" to restaurantName,
                "restaurantImageUrl" to imageUrl,
                "deliveryFee" to deliveryFee,
                "estimatedTimeMinutes" to estimatedTimeMinutes,
                "managerPhone" to managerPhone,
                "restaurantAddress" to address,
                "restaurantLatitude" to latitude,
                "restaurantLongitude" to longitude,
                "foodStatus" to Status.PENDING.name,
                "foodMode" to UserMode.MANAGER.name
            )
            db.child("users").child(uid).updateChildren(updates)

            // Public restaurant visibility is created by the admin approval flow.

            _isLoading.value = false
        }
    }

    fun updateOnlineStatus(isOnline: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        screenModelScope.launch(Dispatchers.IO) {
            db.child("users").child(uid).child("isFoodServiceOnline").setValue(isOnline)
            db.child("restaurants").child(uid).child("isOpen").setValue(isOnline)
        }
    }

    private val _imageUpload = MutableStateFlow<ImageUploadState>(ImageUploadState.Idle)
    val imageUpload: StateFlow<ImageUploadState> = _imageUpload.asStateFlow()
    private var uploadJob: Job? = null

    /**
     * Uploads the picked image to Firebase Storage and exposes the URL to
     * store/display via [imageUpload].
     */
    fun uploadRestaurantImage(image: PickedImage) {
        if (_imageUpload.value is ImageUploadState.Uploading) return
        _imageUpload.value = ImageUploadState.Uploading

        uploadJob = screenModelScope.launch {
            try {
                val url = FirebaseImageUploader.upload(
                    bytes = image.readBytes(),
                    ownerUid = auth.currentUser?.uid ?: throw IllegalStateException("Sign in to upload an image."),
                    category = "restaurant"
                )
                _imageUpload.value = ImageUploadState.Success(url)
            } catch (e: CancellationException) {
                // Reset only when abandoned from outside (e.g. the upload was cancelled);
                // resetImageUpload() has already reset the state when it cancelled us.
                if (isActive) _imageUpload.value = ImageUploadState.Idle
                throw e
            } catch (e: Exception) {
                _imageUpload.value = ImageUploadState.Error(e.message ?: "Upload failed")
            }
        }
    }

    /** For an already-approved manager changing their photo later. */
    fun saveRestaurantImageUrl(url: String) {
        val uid = auth.currentUser?.uid ?: return
        screenModelScope.launch(Dispatchers.IO) {
            db.child("users").child(uid).child("restaurantImageUrl").setValue(url)
            db.child("restaurants").child(uid).child("imageUrl").setValue(url)
        }
    }

    /** Clears the upload state and cancels an upload still in flight, so its result can't leak into the next form. */
    fun resetImageUpload() {
        uploadJob?.cancel()
        uploadJob = null
        _imageUpload.value = ImageUploadState.Idle
    }
}