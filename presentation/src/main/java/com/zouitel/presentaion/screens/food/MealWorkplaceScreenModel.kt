package com.zouitel.presentaion.screens.food

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.FirebaseImageUploader
import com.zouitel.presentaion.common.PickedImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MealWorkplaceScreenModel(
    private val repository: FoodRepository = FoodRepository()
) : ScreenModel {

    private val _imageUpload = MutableStateFlow<ImageUploadState>(ImageUploadState.Idle)
    val imageUpload: StateFlow<ImageUploadState> = _imageUpload.asStateFlow()

    /**
     * Uploads a meal photo to Firebase Storage and publishes the URL to
     * store/display through [imageUpload].
     */
    fun uploadMealImage(image: PickedImage) {
        if (_imageUpload.value is ImageUploadState.Uploading) return
        _imageUpload.value = ImageUploadState.Uploading

        screenModelScope.launch {
            try {
                val url = FirebaseImageUploader.upload(
                    bytes = image.readBytes(),
                    ownerUid = auth.currentUser?.uid ?: throw IllegalStateException("Sign in to upload an image."),
                    category = "meal"
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

    fun saveMeal(
        restaurantId: String,
        mealId: String = "",
        name: String,
        description: String,
        basePrice: Double,
        category: MealCategory,
        imageUrl: String,
        isAvailable: Boolean = true,
        onSuccess: () -> Unit
    ) {
        screenModelScope.launch(Dispatchers.IO) {
            repository.addMeal(restaurantId, mealId, name, description, basePrice, category, imageUrl, isAvailable)
            onSuccess()
        }
    }

    fun deleteMeal(
        restaurantId: String,
        mealId: String,
        onSuccess: () -> Unit
    ) {
        screenModelScope.launch(Dispatchers.IO) {
            repository.deleteMeal(restaurantId, mealId)
            onSuccess()
        }
    }
}