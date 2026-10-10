package com.zouitel.presentaion.model

import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.common.UserMode
import kotlinx.serialization.Serializable

@Serializable
data class VehicleInfo(
    val make: String = "Toyota",
    val model: String = "Corolla",
    val color: String = "Silver",
    val licensePlate: String = "12345-121-16",
    val serviceType: String = "Ride"
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
    val fullName: String = "",
    val email: String? = null,
    val phoneNumber: String? = null,
    val photoUrl: String? = null,
    val currentLocation: DriverLocation? = null,
    //---------------------------
    val driveMode: UserMode = UserMode.PASSENGER,
    val driverStatus: Status = Status.NONE,
    val isDriverOnline: Boolean = false,
    //---------------------------------
    val foodMode: UserMode = UserMode.CLIENT,
    val orderStatus: Status = Status.NONE,
    val isFoodServiceOnline: Boolean = false,
    //----------------------------------
    val shopMode: UserMode = UserMode.CLIENT,
    val shopStatus: Status = Status.NONE,
    val isShopServiceOnline: Boolean = false,
    //----------------------------------
    val vehicleInfo: VehicleInfo? = VehicleInfo(),
    //--------------------------------
    val courierMode: UserMode = UserMode.COURIER,
    val courierStatus: Status = Status.NONE,
    val isCourierOnline: Boolean = false,
    //--------------------------------
    val freightMode: UserMode = UserMode.FREIGHT,
    val freightStatus: Status = Status.NONE,
    val isFreightServiceOnline: Boolean = false
) {
}