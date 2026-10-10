package com.zouitel.presentaion.admin

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Status
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FreightApplication(
    val uid: String,
    val fullName: String,
    val phone: String,
    val vehicleType: String,
    val documentLink: String
)

// ScreenModel for Admin to manage Freight driver applications
class AdminFreightModel : ScreenModel {
    private val db = Firebase.database.reference()

    private val _applications = MutableStateFlow<List<FreightApplication>>(emptyList())
    val applications: StateFlow<List<FreightApplication>> = _applications.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        listenToApplications()
    }

    private fun listenToApplications() {
        screenModelScope.launch {
            db.child("users").valueEvents.collect { snapshot ->
                val pending = snapshot.children.mapNotNull { child ->
                    try {
                        val uid = child.key ?: return@mapNotNull null

                        val statusStr = runCatching { child.child("freightStatus").value<String>() }.getOrDefault("NONE")
                        val status = runCatching { Status.valueOf(statusStr) }.getOrDefault(Status.NONE)
                        if (status != Status.PENDING) return@mapNotNull null

                        FreightApplication(
                            uid = uid,
                            fullName = runCatching { child.child("fullName").value<String>() }.getOrDefault(""),
                            phone = runCatching { child.child("phone").value<String>() }.getOrDefault(""),
                            vehicleType = runCatching { child.child("freightVehicleType").value<String>() }.getOrDefault(""),
                            documentLink = runCatching { child.child("freightDocumentLink").value<String>() }.getOrDefault("")
                        )
                    } catch (e: Exception) {
                        null
                    }
                }

                _applications.value = pending
                _isLoading.value = false
            }
        }
    }

    fun updateFreightStatus(uid: String, newStatus: Status) {
        screenModelScope.launch {
            db.child("users").child(uid).child("freightStatus").setValue(newStatus.name)
        }
    }
}