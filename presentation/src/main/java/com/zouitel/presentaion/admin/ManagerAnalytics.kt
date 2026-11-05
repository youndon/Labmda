package com.zouitel.presentaion.admin

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.database.database

/**
 * Writes auditable admin decisions only.
 *
 * Push delivery is performed by trusted Firebase Functions through FCM;
 * client code must never write notification records or target device tokens.
 */
object ManagerAnalytics {
    private val db = Firebase.database.reference()

    suspend fun logAdminCourierApproval(courierUid: String, approved: Boolean) {
        val payload = mapOf(
            "timestamp" to System.currentTimeMillis(),
            "status" to if (approved) "APPROVED" else "REJECTED"
        )
        db.child("admin_audit_logs").child("couriers").child(courierUid).setValue(payload)
    }
}
