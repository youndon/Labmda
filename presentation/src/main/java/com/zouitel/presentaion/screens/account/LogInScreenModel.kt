package com.zouitel.presentaion.screens.account

import cafe.adriel.voyager.core.model.ScreenModel
import com.zouitel.presentaion.model.UserProfile
import com.zouitel.presentaion.screens.ride.DataOrException
import dev.gitlive.firebase.database.DatabaseReference

class LogInScreenModel(
    private val usersRef: DatabaseReference
): ScreenModel {
    suspend fun saveUserProfile(userProfile: UserProfile): DataOrException<Unit, Exception> {
        val result = DataOrException<Unit, Exception>()
        try {
            // Creates a path like: /users/{uid}
            val userNode = usersRef.child("users").child(userProfile.uid)

            // Update identity fields only. Never serialize UserProfile here:
            // doing so would overwrite server-owned approvals and service settings.
            val updates = mutableMapOf<String, Any?>(
                "uid" to userProfile.uid
            )
            userProfile.fullName.takeIf { it.isNotBlank() }?.let { updates["fullName"] = it }
            userProfile.email?.takeIf { it.isNotBlank() }?.let { updates["email"] = it }
            userProfile.phoneNumber?.let { updates["phoneNumber"] = it }
            userProfile.photoUrl?.let { updates["photoUrl"] = it }
            userNode.updateChildren(updates)

            result.data = Unit
        } catch (e: Exception) {
            e.printStackTrace()
            result.e = Exception(e.message, e)
        }
        return result
    }

    // Example of reading a profile from RTDB
    suspend fun getUserProfile(uid: String): DataOrException<UserProfile?, Exception> {
        val result = DataOrException<UserProfile?, Exception>()
        try {
            val snapshot = usersRef.child("users").child(uid).get()
            result.data = if (snapshot.exists) runCatching { snapshot.value<UserProfile>() }.getOrNull() else null
        } catch (e: Exception) {
            result.e = Exception(e.message, e)
        }
        return result
    }
}