package com.zouitel.presentaion.data.repository

import com.zouitel.presentaion.common.UserMode
import com.zouitel.presentaion.model.UserProfile
import dev.gitlive.firebase.database.DatabaseReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Realtime Database access for user profile data. */
class UsersRepository(
    private val database: DatabaseReference
) {
    fun observeProfile(uid: String): Flow<UserProfile?> =
        database.child("users").child(uid).valueEvents.map { snapshot ->
            if (snapshot.exists) runCatching { snapshot.value<UserProfile>() }.getOrNull() else null
        }

    suspend fun getProfile(uid: String): UserProfile? {
        if (uid.isBlank()) return null
        val snapshot = database.child("users").child(uid).get()
        return if (snapshot.exists) runCatching { snapshot.value<UserProfile>() }.getOrNull() else null
    }

    suspend fun saveProfile(profile: UserProfile) {
        require(profile.uid.isNotBlank()) { "A user ID is required to save a profile." }
        database.child("users").child(profile.uid).updateChildren(
            mapOf(
                "uid" to profile.uid,
                "fullName" to profile.fullName,
                "email" to profile.email,
                "phoneNumber" to profile.phoneNumber,
                "photoUrl" to profile.photoUrl
            )
        )
    }

    suspend fun updateUserMode(uid: String, mode: UserMode) {
        require(uid.isNotBlank()) { "A user ID is required." }
        database.child("users").child(uid).updateChildren(mapOf("driveMode" to mode))
    }

    suspend fun updateFoodUserMode(uid: String, mode: UserMode) {
        require(uid.isNotBlank()) { "A user ID is required." }
        database.child("users").child(uid).updateChildren(mapOf("foodMode" to mode.name))
    }

    fun observePassengerInfo(uid: String): Flow<Pair<String, String>> =
        database.child("users").child(uid).valueEvents.map { snapshot ->
            val name = readString(snapshot.child("fullName")).ifBlank {
                readString(snapshot.child("name"))
            }
            val phone = readString(snapshot.child("phone")).ifBlank {
                readString(snapshot.child("phoneNumber"))
            }
            name to phone
        }
}

private fun readString(snapshot: dev.gitlive.firebase.database.DataSnapshot): String =
    runCatching { snapshot.value<String>() }.getOrDefault("")
