package com.zouitel.presentaion.admin

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.model.UserProfile
import com.zouitel.presentaion.model.VehicleInfo
import com.zouitel.presentaion.model.VehicleType
import com.zouitel.presentaion.screens.ride.DriverAccountInfo
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

    init { listenToDriverApplications() }

    private fun listenToDriverApplications() {
        screenModelScope.launch(Dispatchers.IO) {
            db.child("users").orderByChild("driverService/status")
                .equalTo(Status.PENDING.name).valueEvents.collect { snapshot ->
                    _applications.value = snapshot.children.mapNotNull { child ->
                        runCatching {
                            val uid = child.key ?: return@mapNotNull null
                            val vehicle = child.child("driverService").child("vehicleInfo")
                            val type = runCatching { VehicleType.valueOf(vehicle.child("type").value<String>()) }
                                .getOrDefault(VehicleType.CAR)
                            UserProfile(
                                uid = uid,
                                fullName = runCatching { child.child("fullName").value<String>() }.getOrDefault(""),
                                phoneNumber = runCatching { child.child("phoneNumber").value<String>() }
                                    .getOrElse { runCatching { child.child("phone").value<String>() }.getOrDefault("") },
                                driverService = DriverAccountInfo(
                                    status = Status.PENDING,
                                    vehicleInfo = VehicleInfo(
                                        type = type,
                                        make = runCatching { vehicle.child("make").value<String>() }.getOrDefault(""),
                                        model = runCatching { vehicle.child("model").value<String>() }.getOrDefault(""),
                                        color = runCatching { vehicle.child("color").value<String>() }.getOrDefault(""),
                                        licensePlate = runCatching { vehicle.child("licensePlate").value<String>() }.getOrDefault(""),
                                        serviceType = "Ride"
                                    )
                                )
                            )
                        }.getOrNull()
                    }
                }
        }
    }

    fun updateDriverStatus(uid: String, newStatus: Status) {
        screenModelScope.launch(Dispatchers.IO) {
            db.child("users").child(uid).child("driverService").child("status").setValue(newStatus.name)
        }
    }
}