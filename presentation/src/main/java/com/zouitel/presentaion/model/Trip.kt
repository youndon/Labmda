package com.zouitel.presentaion.model

import com.google.android.gms.maps.model.LatLng
import kotlinx.serialization.Serializable

@Serializable
data class LocationInfo(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val addressName: String = ""
) {
    /**
     * Extension helpers for LocationInfo to integrate cleanly with Google Maps
     * and Jetpack Compose UI without modifying the core data model.
     */
    fun LocationInfo.toLatLng(): LatLng = LatLng(latitude, longitude)

    val LocationInfo.displayTitle: String
        get() = addressName.ifBlank { "Selected Location" }
}


@Serializable
data class Trip(
    val id: String = "",
    val passengerId: String = "",
    val driverId: String? = null,
    val serviceType: String = "Ride", // Ride, Moto, Courier, Freight
    val pickupLocation: LocationInfo = LocationInfo(),
    val dropoffLocation: LocationInfo = LocationInfo(),
    val fareAmount: Double = 0.0,
    val status: TripStatus = TripStatus.SEARCHING,
    val rating: Int? = null,
    val createdAt: Long = System.currentTimeMillis()
)