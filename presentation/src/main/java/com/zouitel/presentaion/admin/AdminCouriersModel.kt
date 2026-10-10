package com.zouitel.presentaion.admin

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.model.UserProfile
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ScreenModel for Admin to manage Couriers
class AdminCouriersModel : ScreenModel {
    private val db = Firebase.database.reference()

    private val _applications = MutableStateFlow<List<UserProfile>>(emptyList())
    val applications: StateFlow<List<UserProfile>> = _applications.asStateFlow()

    init {
        listenToApplications()
    }

    private fun listenToApplications() {
        screenModelScope.launch {
            db.child("users").valueEvents.collect { snapshot ->
                val profiles = snapshot.children.mapNotNull { child ->
                    try {
                        val uid = child.key ?: return@mapNotNull null
                        UserProfile(
                            uid = uid,
                            fullName = child.child("fullName").value(),
                            phoneNumber = child.child("phone").value(),
                            courierStatus = Status.valueOf(child.child("courierStatus").value())
                        )
                    } catch (e: Exception) { null }
                }.filter { it.courierStatus == Status.PENDING } // Only show pending apps

                _applications.value = profiles
            }
        }
    }

    fun updateCourierStatus(uid: String, newStatus: Status) {
        screenModelScope.launch {
            db.child("users").child(uid).child("courierStatus").setValue(newStatus.name)
        }
    }
}

