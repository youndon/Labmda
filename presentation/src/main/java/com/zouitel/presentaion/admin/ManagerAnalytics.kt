package com.zouitel.presentaion.admin

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.tasks.await

object ManagerAnalytics {
    private val db = Firebase.database.reference()

    // Trigger a targeted notification payload or log metric when admin approves a courier
    suspend fun logAdminCourierApproval(courierUid: String, approved: Boolean) {
        val payload = mapOf(
            "timestamp" to System.currentTimeMillis(),
            "status" to if (approved) "APPROVED" else "REJECTED"
        )
        db.child("admin_audit_logs").child("couriers").child(courierUid).setValue(payload)
    }

    // Push notification trigger placeholder for target tokens
    suspend fun sendPushNotification(targetFcmToken: String, title: String, body: String) {
        if (targetFcmToken.isBlank()) return
        val notificationPayload = mapOf(
            "title" to title,
            "body" to body,
            "timestamp" to System.currentTimeMillis()
        )
        db.child("notifications").child(targetFcmToken).push().setValue(notificationPayload)
    }
}