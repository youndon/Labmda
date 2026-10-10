package com.zouitel.presentaion.common

import kotlinx.serialization.Serializable

@Serializable
enum class UserMode {
    CLIENT,
    MANAGER,
    PASSENGER,
    DRIVER,
    FREIGHT,
    COURIER;
}