package com.zouitel.presentaion.screens.courier

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.model.UserProfile
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CourierWorkplaceScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val auth = Firebase.auth

    private val _profile = MutableStateFlow(UserProfile())
    val profile: StateFlow<UserProfile> = _profile.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        checkCourierStatus()
    }

    private fun checkCourierStatus() {
        val uid = auth.currentUser?.uid ?: run {
            _isLoading.value = false
            return
        }

        screenModelScope.launch {
            db.child("users").child(uid).valueEvents.collect { snapshot ->
                if (snapshot.exists) {
                    val name = runCatching { snapshot.child("fullName").value<String>() }
                        .getOrElse { runCatching { snapshot.child("name").value<String>() }.getOrDefault("") }
                    val phone = runCatching { snapshot.child("phone").value<String>() }.getOrDefault("")
                    // Fixed: read "courierStatus" instead of "status"
                    val statusStr = runCatching { snapshot.child("courierStatus").value<String>() }.getOrDefault("NONE")

                    val courierStatus = runCatching { Status.valueOf(statusStr) }.getOrDefault(Status.NONE)

                    _profile.value = UserProfile(
                        uid = uid,
                        fullName = name,
                        phoneNumber = phone,
                        courierStatus = courierStatus
                    )
                } else {
                    _profile.value = UserProfile(uid = uid, courierStatus = Status.NONE)
                }
                _isLoading.value = false
            }
        }
    }

    fun submitApplication(fullName: String, phone: String, vehicleType: String, documentLink: String) {
        val uid = auth.currentUser?.uid ?: return
        _isLoading.value = true

        screenModelScope.launch {
            val updates = mapOf(
                "fullName" to fullName,
                "phone" to phone,
                "vehicleType" to vehicleType,
                "identityDocumentLink" to documentLink,
                // Fixed: write to "courierStatus" instead of "status"
                "courierStatus" to Status.PENDING.name
            )
            db.child("users").child(uid).updateChildren(updates)
            _isLoading.value = false
        }
    }
}