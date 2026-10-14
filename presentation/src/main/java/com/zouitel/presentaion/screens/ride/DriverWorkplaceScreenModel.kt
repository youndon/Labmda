package com.zouitel.presentaion.screens.ride

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Status
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

    private val _driverAccount = MutableStateFlow<DriverAccountInfo?>(null)
    val driverAccount: StateFlow<DriverAccountInfo?> = _driverAccount.asStateFlow()

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
                    val fullName = runCatching { snapshot.child("fullName").value<String>() }
                        .getOrElse { runCatching { snapshot.child("name").value<String>() }.getOrDefault("") }
                    val phone = runCatching { snapshot.child("phoneNumber").value<String>() }
                        .getOrElse { runCatching { snapshot.child("phone").value<String>() }.getOrDefault("") }

                    val driverChild = snapshot.child("driverService")
                    val driverAccountInfo = if (driverChild.exists) {
                        try {
                            val id = runCatching { driverChild.child("id").value<String>() }.getOrDefault(uid)
                            val statusStr = runCatching { driverChild.child("status").value<String>() }.getOrDefault("NONE")
                            val status = runCatching { Status.valueOf(statusStr) }.getOrDefault(Status.NONE)
                            val isOnline = runCatching { driverChild.child("isOnline").value<Boolean>() }.getOrDefault(false)

                            val typeStr = runCatching { driverChild.child("vehicleInfo").child("type").value<String>() }.getOrDefault("CAR")
                            val vehicleType = runCatching { VehicleType.valueOf(typeStr) }.getOrDefault(VehicleType.CAR)

                            val make = runCatching { driverChild.child("vehicleInfo").child("make").value<String>() }.getOrDefault("Toyota")
                            val model = runCatching { driverChild.child("vehicleInfo").child("model").value<String>() }.getOrDefault("Corolla")
                            val color = runCatching { driverChild.child("vehicleInfo").child("color").value<String>() }.getOrDefault("Silver")
                            val licensePlate = runCatching { driverChild.child("vehicleInfo").child("licensePlate").value<String>() }.getOrDefault("")

                            DriverAccountInfo(
                                id = id,
                                vehicleInfo = VehicleInfo(
                                    type = vehicleType,
                                    make = make,
                                    model = model,
                                    color = color,
                                    licensePlate = licensePlate,
                                    serviceType = "Ride"
                                ),
                                status = status,
                                isOnline = isOnline
                            )
                        } catch (e: Exception) {
                            null
                        }
                    } else {
                        null
                    }

                    _profile.value = UserProfile(
                        uid = uid,
                        fullName = fullName,
                        phoneNumber = phone,
                        driverService = driverAccountInfo
                    )
                    _driverAccount.value = driverAccountInfo
                } else {
                    _profile.value = UserProfile(uid = uid)
                    _driverAccount.value = null
                }
                _isLoading.value = false
            }
        }
    }

    fun saveDriverService(
        vehicleType: VehicleType,
        make: String,
        model: String,
        color: String,
        licensePlate: String,
        keepStatus: Status? = null
    ) {
        val uid = auth.currentUser?.uid ?: return
        _isLoading.value = true

        screenModelScope.launch {
            val statusToSet = keepStatus ?: Status.PENDING

            val vehicleMap = mapOf(
                "type" to vehicleType.name,
                "make" to make,
                "model" to model,
                "color" to color,
                "licensePlate" to licensePlate,
                "serviceType" to "Ride"
            )

            val driverMap = mapOf(
                "id" to uid,
                "vehicleInfo" to vehicleMap,
                "status" to statusToSet.name,
                "isOnline" to false
            )

            db.child("users").child(uid).child("driverService").setValue(driverMap)
            _isLoading.value = false
        }
    }

    fun updateOnlineStatus(isOnline: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        screenModelScope.launch {
            db.child("users").child(uid).child("driverService").child("isOnline").setValue(isOnline)
        }
    }

    fun deleteDriverService() {
        val uid = auth.currentUser?.uid ?: return
        screenModelScope.launch {
            db.child("users").child(uid).child("driverService").removeValue()
        }
    }
}