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

    init {
        listenToDriverApplications()
    }

    private fun listenToDriverApplications() {
        screenModelScope.launch(Dispatchers.IO) {
            db.child("users").valueEvents.collect { snapshot ->
                val profiles = snapshot.children.mapNotNull { child ->
                    try {
                        val uid = child.key ?: return@mapNotNull null
                        val fullName = runCatching { child.child("fullName").value<String>() }
                            .getOrElse { runCatching { child.child("name").value<String>() }.getOrDefault("") }
                        val phoneNumber = runCatching { child.child("phoneNumber").value<String>() }
                            .getOrElse { runCatching { child.child("phone").value<String>() }.getOrDefault("") }

                        val driverChild = child.child("driverService")
                        val driverAccount = if (driverChild.exists) {
                            val statusStr = runCatching { driverChild.child("status").value<String>() }.getOrDefault("NONE")
                            val status = runCatching { Status.valueOf(statusStr) }.getOrDefault(Status.NONE)

                            val vehicleChild = driverChild.child("vehicleInfo")
                            val typeStr = runCatching { vehicleChild.child("type").value<String>() }.getOrDefault("CAR")
                            val vehicleType = runCatching { VehicleType.valueOf(typeStr) }.getOrDefault(VehicleType.CAR)

                            val make = runCatching { vehicleChild.child("make").value<String>() }.getOrDefault("Toyota")
                            val model = runCatching { vehicleChild.child("model").value<String>() }.getOrDefault("Corolla")
                            val color = runCatching { vehicleChild.child("color").value<String>() }.getOrDefault("Silver")
                            val licensePlate = runCatching { vehicleChild.child("licensePlate").value<String>() }.getOrDefault("")

                            DriverAccountInfo(
                                status = status,
                                vehicleInfo = VehicleInfo(
                                    type = vehicleType,
                                    make = make,
                                    model = model,
                                    color = color,
                                    licensePlate = licensePlate,
                                    serviceType = "Ride"
                                )
                            )
                        } else {
                            null
                        }

                        if (driverAccount?.status == Status.PENDING) {
                            UserProfile(
                                uid = uid,
                                fullName = fullName,
                                phoneNumber = phoneNumber,
                                driverService = driverAccount
                            )
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        null
                    }
                }

                _applications.value = profiles
            }
        }
    }

    fun updateDriverStatus(uid: String, newStatus: Status) {
        screenModelScope.launch(Dispatchers.IO) {
            db.child("users").child(uid).child("driverService").child("status").setValue(newStatus.name)
        }
    }
}