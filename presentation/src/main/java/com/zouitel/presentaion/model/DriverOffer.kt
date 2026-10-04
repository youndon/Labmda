package com.zouitel.presentaion.model

// Data model for InDrive fare counter-offers
data class DriverOffer(
    val id: String = "",
    val driverId: String = "",
    val driverName: String = "Driver",
    val driverRating: Double = 4.8,
    val offeredFare: Double = 0.0,
    val etaMinutes: Int = 5,
    val timestamp: Long = System.currentTimeMillis()
)