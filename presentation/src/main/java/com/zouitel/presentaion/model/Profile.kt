package com.zouitel.presentaion.model

import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.common.UserMode
import com.zouitel.presentaion.screens.delivery.DeliveryAccountInfo
import com.zouitel.presentaion.screens.ride.DriverAccountInfo
import kotlinx.serialization.Serializable





@Serializable
data class UserProfile(
    val uid: String = "",
    val fullName: String = "",
    val email: String? = null,
    val phoneNumber: String? = null,
    val photoUrl: String? = null,
    //---------------------------
//    val driveMode: UserMode = UserMode.PASSENGER,
//    val driverStatus: Status = Status.NONE,
//    val isDriverOnline: Boolean = false,
    //---------------------------------
    val foodMode: UserMode = UserMode.CLIENT,
    val foodStatus: Status = Status.NONE,
    val isFoodServiceOnline: Boolean = false,
    //----------------------------------
    val shopMode: UserMode = UserMode.CLIENT,
    val shopStatus: Status = Status.NONE,
    val isShopServiceOnline: Boolean = false,
    //--------------------------------
    val driverService: DriverAccountInfo? = null,
    //--------------------------------
    val deliveryServices: DeliveryAccountInfo? = null
)