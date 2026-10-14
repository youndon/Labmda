package com.zouitel.presentaion.common

import kotlinx.serialization.Serializable

@Serializable
enum class Status {
    NONE,
    PENDING,
    SUSPENDED,
    APPROVED,
    REJECTED;
}
