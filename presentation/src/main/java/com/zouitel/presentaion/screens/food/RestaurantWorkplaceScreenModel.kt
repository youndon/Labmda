package com.zouitel.presentaion.screens.food

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.common.UserMode
import com.zouitel.presentaion.common.DriveImageUploader
import com.zouitel.presentaion.common.DriveTokenProvider
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
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

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var mealsJob: Job? = null

    private val _imageUpload = MutableStateFlow<ImageUploadState>(ImageUploadState.Idle)
    val imageUpload: StateFlow<ImageUploadState> = _imageUpload.asStateFlow()
    private var uploadJob: Job? = null

    init {
        checkRestaurantStatus()
    }

    private fun checkRestaurantStatus() {
        val uid = auth.currentUser?.uid ?: run {
            _isLoading.value = false
            _error.value = "Please sign in to manage a restaurant."
            return
        }

        screenModelScope.launch(Dispatchers.IO) {
            db.child("users").child(uid).valueEvents
                .catch { cause ->
                    _error.value = cause.message ?: "Could not load your restaurant profile."
                    _isLoading.value = false
                }
                .collect { snapshot ->
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
                            if (mealsJob?.isActive != true) observeMeals(uid)
                        } else {
                            // Do not keep displaying a previously approved restaurant/menu if the
                            // account is later suspended, rejected, or returned to pending review.
                            mealsJob?.cancel()
                            mealsJob = null
                            _meals.value = emptyList()
                            _restaurantInfo.value = ManagedRestaurantInfo(id = uid)
                        }
                    } else {
                        _profile.value = UserProfile(uid = uid)
                        mealsJob?.cancel()
                        mealsJob = null
                        _meals.value = emptyList()
                        _restaurantInfo.value = ManagedRestaurantInfo(id = uid)
                    }
                    _isLoading.value = false
                }
        }
    }

    private fun observeMeals(restaurantId: String) {
        mealsJob = screenModelScope.launch(Dispatchers.IO) {
            repository.getMealsForRestaurant(restaurantId)
                .catch { cause ->
                    _error.value = cause.message ?: "Could not load the restaurant menu. Check your database permissions."
                }
                .collect { _meals.value = it }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun updateFoodMode(foodMode: UserMode) {
        val uid = auth.currentUser?.uid ?: run {
            _error.value = "Please sign in to change food mode."
            return
        }
        screenModelScope.launch(Dispatchers.IO) {
            try {
                db.child("users").child(uid).child("foodMode").setValue(foodMode.name)
                _error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not update food mode."
            }
        }
    }

    /**
     * Submits a new/rejected application as PENDING. For an already approved restaurant, this
     * updates editable details without demoting the approved account or creating a public record.
     * The public restaurant node must already have been created by the trusted approval workflow.
     */
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
        val uid = auth.currentUser?.uid ?: run {
            _error.value = "Please sign in before submitting restaurant details."
            return
        }

        if (restaurantName.isBlank() || managerPhone.isBlank() || address.isBlank()) {
            _error.value = "Enter the restaurant name, manager phone, and address."
            return
        }
        if (!deliveryFee.isFinite() || deliveryFee < 0.0) {
            _error.value = "Delivery fee must be a valid non-negative amount."
            return
        }
        if (estimatedTimeMinutes !in 1..1440) {
            _error.value = "Estimated preparation time must be between 1 and 1440 minutes."
            return
        }
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            _error.value = "Choose a valid restaurant location on the map."
            return
        }

        _isLoading.value = true
        _error.value = null
        screenModelScope.launch(Dispatchers.IO) {
            try {
                val currentStatus = _profile.value.foodStatus
                val userFields = mapOf<String, Any?>(
                    "sourceName" to restaurantName.trim(),
                    "restaurantImageUrl" to imageUrl.trim(),
                    "deliveryFee" to deliveryFee,
                    "estimatedTimeMinutes" to estimatedTimeMinutes,
                    "managerPhone" to managerPhone.trim(),
                    "restaurantAddress" to address.trim(),
                    "restaurantLatitude" to latitude,
                    "restaurantLongitude" to longitude
                )

                when (currentStatus) {
                    Status.APPROVED -> {
                        val restaurantRef = db.child("restaurants").child(uid)
                        val restaurantSnapshot = restaurantRef.valueEvents.first()
                        if (!restaurantSnapshot.exists) {
                            throw IllegalStateException(
                                "Your restaurant has no public record yet. Contact an administrator instead of creating one from the app."
                            )
                        }

                        // One multi-location update keeps the manager's profile and public catalog
                        // in sync. Rules must restrict these writes to the approved owner.
                        val updates = linkedMapOf<String, Any?>()
                        userFields.forEach { (key, value) -> updates["users/$uid/$key"] = value }
                        updates["users/$uid/foodMode"] = UserMode.MANAGER.name
                        updates["restaurants/$uid/name"] = restaurantName.trim()
                        updates["restaurants/$uid/imageUrl"] = imageUrl.trim()
                        updates["restaurants/$uid/deliveryFee"] = deliveryFee
                        updates["restaurants/$uid/estimatedTimeMinutes"] = estimatedTimeMinutes
                        updates["restaurants/$uid/address"] = address.trim()
                        updates["restaurants/$uid/latitude"] = latitude
                        updates["restaurants/$uid/longitude"] = longitude
                        db.updateChildren(updates)
                    }
                    Status.NONE, Status.REJECTED -> {
                        val applicationUpdates = userFields.toMutableMap()
                        applicationUpdates["foodStatus"] = Status.PENDING.name
                        applicationUpdates["foodMode"] = UserMode.MANAGER.name
                        db.child("users").child(uid).updateChildren(applicationUpdates)
                    }
                    Status.PENDING -> throw IllegalStateException("Your restaurant application is already pending review.")
                    else -> throw IllegalStateException("Your current restaurant status does not allow editing these details.")
                }
                _error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not save restaurant details. Please try again."
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateOnlineStatus(isOnline: Boolean) {
        val uid = auth.currentUser?.uid ?: run {
            _error.value = "Please sign in to change restaurant availability."
            return
        }
        if (_profile.value.foodStatus != Status.APPROVED) {
            _error.value = "Only an approved restaurant can change its online status."
            return
        }

        screenModelScope.launch(Dispatchers.IO) {
            try {
                val restaurantSnapshot = db.child("restaurants").child(uid).valueEvents.first()
                if (!restaurantSnapshot.exists) {
                    throw IllegalStateException("Your restaurant's public record is missing. Contact an administrator.")
                }
                db.updateChildren(
                    mapOf(
                        "users/$uid/isFoodServiceOnline" to isOnline,
                        "restaurants/$uid/isOpen" to isOnline
                    )
                )
                _error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not update restaurant availability."
            }
        }
    }

    /**
     * Uploads the picked image to the signed-in user's Google Drive and exposes the link to
     * store/display via [imageUpload].
     */
    fun uploadRestaurantImage(image: PickedImage, accessToken: DriveTokenProvider) {
        if (_imageUpload.value is ImageUploadState.Uploading) return
        _imageUpload.value = ImageUploadState.Uploading

        uploadJob = screenModelScope.launch {
            try {
                val url = DriveImageUploader.upload(
                    bytes = image.readBytes(),
                    accessToken = accessToken(),
                    label = "restaurant-cover"
                )
                _imageUpload.value = ImageUploadState.Success(url)
            } catch (e: CancellationException) {
                if (isActive) _imageUpload.value = ImageUploadState.Idle
                throw e
            } catch (e: Exception) {
                _imageUpload.value = ImageUploadState.Error(e.message ?: "Upload failed")
            }
        }
    }

    /** For an already-approved manager changing their photo later. */
    fun saveRestaurantImageUrl(url: String) {
        val uid = auth.currentUser?.uid ?: run {
            _error.value = "Please sign in to update the restaurant photo."
            return
        }
        if (_profile.value.foodStatus != Status.APPROVED) {
            _error.value = "Only an approved restaurant can update its public photo."
            return
        }
        if (url.isBlank()) {
            _error.value = "The uploaded image URL is empty."
            return
        }

        screenModelScope.launch(Dispatchers.IO) {
            try {
                val restaurantSnapshot = db.child("restaurants").child(uid).valueEvents.first()
                if (!restaurantSnapshot.exists) {
                    throw IllegalStateException("Your restaurant's public record is missing. Contact an administrator.")
                }
                db.updateChildren(
                    mapOf(
                        "users/$uid/restaurantImageUrl" to url.trim(),
                        "restaurants/$uid/imageUrl" to url.trim()
                    )
                )
                _error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not save the restaurant photo."
            }
        }
    }

    /** Clears the upload state and cancels an upload still in flight. */
    fun resetImageUpload() {
        uploadJob?.cancel()
        uploadJob = null
        _imageUpload.value = ImageUploadState.Idle
    }
}
