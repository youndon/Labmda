package com.zouitel.presentaion.screens.delivery

import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.model.VehicleInfo
import kotlinx.serialization.Serializable

@Serializable
enum class DeliveryType {
    COURIER,
    FREIGHT,
    SELF_PICKUP,
    SELF_DELIVERY,
    OTHER,
    UNDEFINED,
    UNKNOWN;
}

@Serializable
data class DeliveryAccountInfo(
    val id: String = "",
    val deliveryType: DeliveryType = DeliveryType.COURIER,
    val vehicleInfo: VehicleInfo? = null,
    val identityDocumentLink: String = "",
    val status: Status = Status.NONE,
    val isOnline: Boolean = false
)