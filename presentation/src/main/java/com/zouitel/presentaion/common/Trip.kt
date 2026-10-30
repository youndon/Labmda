package com.zouitel.presentaion.common

import kotlinx.serialization.Serializable

@Serializable
data class Trip(
    val id: String = "",
    val passengerId: String = "",
    val passengerName: String = "",
    val passengerPhone: String = "",
    val pickupLocation: String = "",
    val dropoffLocation: String = "",
    val pickupLat: Double = 0.0,
    val pickupLng: Double = 0.0,
    val dropoffLat: Double = 0.0,
    val dropoffLng: Double = 0.0,
    // Calculated in Cloud Functions from server-controlled pricing configuration.
    val fare: Double = 0.0,
    val driverId: String = "",
    val driverName: String = "",
    val driverPhone: String = "",
    val vehicleDetails: String = "",
    val status: String = "REQUESTED",
    val paymentMethod: String = "",
    val paymentStatus: String = "UNPAID",
    val paymentId: String = "",
    val createdAtTimestamp: Long = 0L
)