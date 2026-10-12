package com.zouitel.presentaion.screens.ride

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.common.UserMode
import com.zouitel.presentaion.model.UserProfile
import com.zouitel.presentaion.model.VehicleInfo
import com.zouitel.presentaion.model.VehicleType
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DriverWorkplaceScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val auth = Firebase.auth

    private val _profile = MutableStateFlow(UserProfile())
    val profile: StateFlow<UserProfile> = _profile.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        observeUserProfile()
    }

    private fun observeUserProfile() {
        val uid = auth.currentUser?.uid ?: run {
            _isLoading.value = false
            return
        }

        screenModelScope.launch {
            db.child("users").child(uid).valueEvents.collect { snapshot ->
                if (snapshot.exists) {
                    val fullName = runCatching { snapshot.child("fullName").value<String>() }.getOrDefault("")
                    val phone = runCatching { snapshot.child("phoneNumber").value<String>() }
                        .getOrElse { runCatching { snapshot.child("phone").value<String>() }.getOrDefault("") }

                    val driveModeStr = runCatching { snapshot.child("driveMode").value<String>() }.getOrDefault("PASSENGER")
                    val driverStatusStr = runCatching { snapshot.child("driverStatus").value<String>() }.getOrDefault("NONE")

                    val driveMode = runCatching { UserMode.valueOf(driveModeStr) }.getOrDefault(UserMode.PASSENGER)
                    val driverStatus = runCatching { Status.valueOf(driverStatusStr) }.getOrDefault(Status.NONE)

                    val typeStr = runCatching { snapshot.child("vehicleInfo").child("type").value<String>() }.getOrDefault("CAR")
                    val vehicleType = runCatching { VehicleType.valueOf(typeStr) }.getOrDefault(VehicleType.CAR)

                    val make = runCatching { snapshot.child("vehicleInfo").child("make").value<String>() }.getOrDefault("Toyota")
                    val model = runCatching { snapshot.child("vehicleInfo").child("model").value<String>() }.getOrDefault("Corolla")
                    val color = runCatching { snapshot.child("vehicleInfo").child("color").value<String>() }.getOrDefault("Silver")
                    val licensePlate = runCatching { snapshot.child("vehicleInfo").child("licensePlate").value<String>() }.getOrDefault("")

                    _profile.value = UserProfile(
                        uid = uid,
                        fullName = fullName,
                        phoneNumber = phone,
                        driveMode = driveMode,
                        driverStatus = driverStatus,
                        vehicleInfo = VehicleInfo(
                            type = vehicleType,
                            make = make,
                            model = model,
                            color = color,
                            licensePlate = licensePlate
                        )
                    )
                } else {
                    _profile.value = UserProfile(uid = uid, driverStatus = Status.NONE)
                }
                _isLoading.value = false
            }
        }
    }

    fun updateDriveMode(driveMode: UserMode) {
        val uid = auth.currentUser?.uid ?: return
        screenModelScope.launch {
            db.child("users").child(uid).child("driveMode").setValue(driveMode.name)
        }
    }

    fun submitDriverApplication(
        fullName: String,
        phone: String,
        vehicleType: VehicleType,
        make: String,
        model: String,
        color: String,
        licensePlate: String
    ) {
        val uid = auth.currentUser?.uid ?: return
        _isLoading.value = true

        screenModelScope.launch {
            val vehicleMap = mapOf(
                "type" to vehicleType.name,
                "make" to make,
                "model" to model,
                "color" to color,
                "licensePlate" to licensePlate,
                "serviceType" to "Ride"
            )

            val updates = mapOf<String, Any>(
                "fullName" to fullName,
                "phoneNumber" to phone,
                "driverStatus" to Status.PENDING.name,
                "driveMode" to UserMode.DRIVER.name,
                "vehicleInfo" to vehicleMap
            )

            db.child("users").child(uid).updateChildren(updates)
            _isLoading.value = false
        }
    }
}