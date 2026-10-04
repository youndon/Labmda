package com.zouitel.presentaion.model

enum class TripStatus {
    SEARCHING,    // Passenger created request, waiting for driver
    ACCEPTED,     // Driver accepted, heading to pickup
    ARRIVED,      // Driver arrived at pickup point
    IN_PROGRESS,  // Passenger is in vehicle, route started
    COMPLETED,    // Drop-off finished
    CANCELLED     // Cancelled by passenger or driver
}

