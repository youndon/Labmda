package com.zouitel.presentaion.model

enum class PassengerBookingState {
    IDLE,
    SELECTING_SERVICE,
    SEARCHING_DRIVER
}

data class ServiceOption(
    val id: String,
    val name: String,
    val price: Double,
    val etaMinutes: Int,
    val icon: String
)
