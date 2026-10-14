package com.zouitel.presentaion.model

import kotlinx.serialization.Serializable

@Serializable
enum class VehicleType {
    CAR, BIKE, E_BIKE, SCOOTER, MOTORCYCLE, TRUCK, VAN, OTHER
}

@Serializable
data class VehicleInfo(
    val type: VehicleType? = null,
    val make: String = "Toyota",
    val model: String = "Corolla",
    val color: String = "Silver",
    val licensePlate: String = "12345-121-16",
    val serviceType: String = "Ride"
)