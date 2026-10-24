package com.zouitel.presentaion.screens.food

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.DriveImageUploader
import com.zouitel.presentaion.common.DriveTokenProvider
import com.zouitel.presentaion.common.PickedImage
import com.zouitel.presentaion.common.Status
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MealWorkplaceScreenModel(
    private val repository: FoodRepository = FoodRepository()
) : ScreenModel {

    private val db = Firebase.database.reference()

    private val _imageUpload = MutableStateFlow<ImageUploadState>(ImageUploadState.Idle)
    val imageUpload: StateFlow<ImageUploadState> = _imageUpload.asStateFlow()

    private val _operationError = MutableStateFlow<String?>(null)
    val operationError: StateFlow<String?> = _operationError.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    /**
     * Uploads a meal photo to the signed-in user's Google Drive and publishes the link to
     * store/display through [imageUpload].
     */
    fun uploadMealImage(image: PickedImage, accessToken: DriveTokenProvider) {
        if (_imageUpload.value is ImageUploadState.Uploading) return
        _imageUpload.value = ImageUploadState.Uploading

        screenModelScope.launch {
            try {
                val url = DriveImageUploader.upload(
                    bytes = image.readBytes(),
                    accessToken = accessToken(),
                    label = "meal"
                )
                _imageUpload.value = ImageUploadState.Success(url)
            } catch (e: CancellationException) {
                _imageUpload.value = ImageUploadState.Idle
                throw e
            } catch (e: Exception) {
                _imageUpload.value = ImageUploadState.Error(e.message ?: "Upload failed")
            }
        }
    }

    /** Call once the screen has consumed a [ImageUploadState.Success] so it isn't re-applied. */
    fun resetImageUpload() {
        _imageUpload.value = ImageUploadState.Idle
    }

    fun clearOperationError() {
        _operationError.value = null
    }

    fun saveMeal(
        restaurantId: String,
        mealId: String = "",
        name: String,
        description: String,
        basePrice: Double,
        category: MealCategory,
        imageUrl: String,
        onSuccess: () -> Unit
    ) {
        if (_isSaving.value || _isDeleting.value) return
        _operationError.value = null

        if (restaurantId.isBlank()) {
            _operationError.value = "A restaurant ID is required."
            return
        }
        if (name.isBlank()) {
            _operationError.value = "Enter a meal name."
            return
        }
        if (!basePrice.isFinite() || basePrice < 0.0) {
            _operationError.value = "Enter a valid non-negative meal price."
            return
        }

        _isSaving.value = true
        screenModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ensureCanManageRestaurant(restaurantId)
                    repository.addMeal(
                        restaurantId = restaurantId,
                        mealId = mealId,
                        name = name.trim(),
                        description = description.trim(),
                        basePrice = basePrice,
                        category = category,
                        imageUrl = imageUrl.trim()
                    )
                }
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _operationError.value = e.message ?: "Could not save the meal. Check your connection and database permissions."
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun deleteMeal(
        restaurantId: String,
        mealId: String,
        onSuccess: () -> Unit
    ) {
        if (_isSaving.value || _isDeleting.value) return
        _operationError.value = null

        if (restaurantId.isBlank() || mealId.isBlank()) {
            _operationError.value = "A restaurant and meal ID are required to delete a meal."
            return
        }

        _isDeleting.value = true
        screenModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ensureCanManageRestaurant(restaurantId)
                    repository.deleteMeal(restaurantId, mealId)
                }
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _operationError.value = e.message ?: "Could not delete the meal. Check your connection and database permissions."
            } finally {
                _isDeleting.value = false
            }
        }
    }

    /** Client-side guard for better UX; Realtime Database rules must independently enforce this. */
    private suspend fun ensureCanManageRestaurant(restaurantId: String) {
        val uid = Firebase.auth.currentUser?.uid
            ?: throw IllegalStateException("Please sign in before managing meals.")
        if (uid != restaurantId) {
            throw IllegalStateException("You can only manage the menu for your own restaurant.")
        }

        val status = db.child("users").child(uid).child("foodStatus").valueEvents.first()
            .value<String>()
        if (status != Status.APPROVED.name) {
            throw IllegalStateException("Your restaurant must be approved before you can manage its menu.")
        }
    }
}
