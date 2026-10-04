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

            // RTDB uses setValue() for serialized classes
            userNode.setValue(userProfile)

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