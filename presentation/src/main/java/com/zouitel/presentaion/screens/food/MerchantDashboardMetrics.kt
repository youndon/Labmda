package com.zouitel.presentaion.screens.food

import java.util.Calendar
import java.util.TimeZone

data class MerchantOrderSnapshot(
    val status: String,
    val salesAmount: Double,
    val createdAtTimestamp: Long
)

data class MerchantDashboardMetrics(
    val newOrders: Int = 0,
    val preparingOrders: Int = 0,
    val outForDeliveryOrders: Int = 0,
    val todayOrders: Int = 0,
    val todaySales: Double = 0.0,
    val weekSales: Double = 0.0
)

fun calculateMerchantDashboardMetrics(orders: List<MerchantOrderSnapshot>): MerchantDashboardMetrics {
    val now = System.currentTimeMillis()
    val today = Calendar.getInstance(TimeZone.getTimeZone("Asia/Manila")).apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val week = today - 6L * 24L * 60L * 60L * 1000L
    fun timestampMillis(value: Long): Long = if (value in 1L..9_999_999_999L) value * 1000L else value
    val normalized = orders.map { it to timestampMillis(it.createdAtTimestamp) }
    val delivered = normalized.filter { it.first.status == "DELIVERED" }
    return MerchantDashboardMetrics(
        newOrders = orders.count { it.status == "PLACED" },
        preparingOrders = orders.count { it.status == "ACCEPTED" || it.status == "PREPARING" },
        outForDeliveryOrders = orders.count { it.status == "OUT_FOR_DELIVERY" },
        todayOrders = normalized.count { it.second >= today },
        todaySales = delivered.filter { it.second >= today }.sumOf { it.first.salesAmount },
        weekSales = delivered.filter { it.second >= week }.sumOf { it.first.salesAmount }
    )
}
