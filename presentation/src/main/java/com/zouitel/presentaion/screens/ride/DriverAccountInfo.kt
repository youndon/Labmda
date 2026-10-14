package com.zouitel.presentaion.screens.ride

import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.model.VehicleInfo
import kotlinx.serialization.Serializable

@Serializable
data class DriverLocation(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val heading: Float = 0f
)

// Data model for InDrive fare counter-offers
@Serializable
data class DriverAccountInfo(
    val id: String = "",
    val vehicleInfo: VehicleInfo? = null,
    val driverRating: Double = 4.8,
    val offeredFare: Double = 0.0,
    val currentLocation: DriverLocation? = null,
    val status: Status = Status.NONE,
    val isOnline: Boolean = false
)