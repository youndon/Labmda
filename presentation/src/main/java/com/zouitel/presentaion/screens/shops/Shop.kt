package com.zouitel.presentaion.screens.shops

import com.zouitel.presentaion.common.Status
import kotlinx.serialization.Serializable

@Serializable
data class Shop(
    val id: String = "",
    val ownerUid: String = "",
    val name: String = "",
    val description: String = "",
    val category: String = "General Store",
    val phone: String = "",
    val address: String = "",
    val imageUrl: String = "",
    val status: Status = Status.PENDING,
    val createdAtTimestamp: Long = 0L,
    val isOnline: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null
)