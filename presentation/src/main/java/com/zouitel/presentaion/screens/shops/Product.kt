package com.zouitel.presentaion.screens.shops

import kotlinx.serialization.Serializable

@Serializable
data class Product(
    val id: String = "",
    val shopId: String = "",
    val name: String = "",
    val description: String = "",
    val price: Double = 0.0,
    val category: String = "General",
    val imageUrl: String = "",
    val isAvailable: Boolean = true,
    val stockQuantity: Int = 10
)