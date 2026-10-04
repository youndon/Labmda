package com.zouitel.presentaion.model

import kotlinx.serialization.Serializable

@Serializable
enum class DriveMode {
    PASSENGER,
    DRIVER
}

@Serializable
enum class FoodMode {
    CLIENT,
    MANAGER
}

@Serializable
data class VehicleInfo(
    val make: String = "Toyota",
    val model: String = "Corolla",
    val color: String = "Silver",
    val licensePlate: String = "12345-121-16",
    val serviceType: String = "Ride" // Ride, Moto, Courier, Freight
)

@Serializable
data class DriverLocation(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val heading: Float = 0f
)

@Serializable
data class UserProfile(
    val uid: String = "",
    val name: String = "",
    val email: String? = null,
    val phoneNumber: String? = null,
    val photoUrl: String? = null,
    val isAdmin: Boolean = false,
    val currentLocation: DriverLocation? = null,
    val driveMode: DriveMode = DriveMode.PASSENGER,
    val isApprovedDriver: Boolean = true,
    val isDriverOnline: Boolean = false,
    val foodMode: FoodMode = FoodMode.CLIENT,
    val vehicleInfo: VehicleInfo? = VehicleInfo()
) {
    fun canSwitchToDriverMode(): Boolean = isApprovedDriver && vehicleInfo != null
}