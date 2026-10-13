package com.zouitel.presentaion.admin

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.model.UserProfile
import com.zouitel.presentaion.model.VehicleInfo
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AdminDriverModel : ScreenModel {
    private val db = Firebase.database.reference()

    private val _applications = MutableStateFlow<List<UserProfile>>(emptyList())
    val applications: StateFlow<List<UserProfile>> = _applications.asStateFlow()

    init {
        listenToDriverApplications()
    }

    private fun listenToDriverApplications() {
        screenModelScope.launch(Dispatchers.IO) {
            db.child("users").valueEvents.collect { snapshot ->
                val profiles = snapshot.children.mapNotNull { child ->
                    try {
                        val uid = child.key ?: return@mapNotNull null
                        val fullName = runCatching { child.child("fullName").value<String>() }.getOrDefault("")
                        val phoneNumber = runCatching { child.child("phoneNumber").value<String>() }
                            .getOrElse { runCatching { child.child("phone").value<String>() }.getOrDefault("") }
                        val driverStatusStr = runCatching { child.child("driverStatus").value<String>() }.getOrDefault("NONE")
                        val driverStatus = runCatching { Status.valueOf(driverStatusStr) }.getOrDefault(Status.NONE)

                        val make = runCatching { child.child("vehicleInfo").child("make").value<String>() }.getOrDefault("Toyota")
                        val model = runCatching { child.child("vehicleInfo").child("model").value<String>() }.getOrDefault("Corolla")
                        val color = runCatching { child.child("vehicleInfo").child("color").value<String>() }.getOrDefault("Silver")
                        val licensePlate = runCatching { child.child("vehicleInfo").child("licensePlate").value<String>() }.getOrDefault("")

                        UserProfile(
                            uid = uid,
                            fullName = fullName,
                            phoneNumber = phoneNumber,
                            driverStatus = driverStatus,
                            vehicleInfo = VehicleInfo(
                                make = make,
                                model = model,
                                color = color,
                                licensePlate = licensePlate
                            )
                        )
                    } catch (e: Exception) {
                        null
                    }
                }.filter { it.driverStatus == Status.PENDING }

                _applications.value = profiles
            }
        }
    }

    fun updateDriverStatus(uid: String, newStatus: Status) {
        screenModelScope.launch(Dispatchers.IO) {
            db.child("users").child(uid).child("driverStatus").setValue(newStatus.name)
        }
    }
}