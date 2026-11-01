package com.zouitel.presentaion.data.repository

import com.zouitel.presentaion.common.UserMode
import com.zouitel.presentaion.model.UserProfile
import com.zouitel.presentaion.model.VehicleInfo
import com.zouitel.presentaion.model.VehicleType
import com.zouitel.presentaion.screens.ride.DriverAccountInfo
import com.zouitel.presentaion.screens.ride.DriverLocation
import dev.gitlive.firebase.database.DataSnapshot
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

    fun observeOnlineDrivers(): Flow<List<UserProfile>> =
        database.child("publicDrivers").valueEvents.map { snapshot ->
            snapshot.children.mapNotNull { driver ->
                val uid = driver.key ?: return@mapNotNull null
                val online = runCatching { driver.child("isOnline").value<Boolean>() }.getOrDefault(false)
                if (!online) return@mapNotNull null

                val location = driver.child("currentLocation")
                val vehicle = driver.child("vehicleInfo")
                val typeName = readString(vehicle.child("type")).ifBlank { "CAR" }
                val type = runCatching { VehicleType.valueOf(typeName) }.getOrDefault(VehicleType.CAR)
                UserProfile(
                    uid = uid,
                    fullName = readString(driver.child("fullName")).ifBlank { "Driver" },
                    phoneNumber = "",
                    driverService = DriverAccountInfo(
                        vehicleInfo = VehicleInfo(type = type),
                        currentLocation = DriverLocation(
                            latitude = readDouble(location.child("latitude")),
                            longitude = readDouble(location.child("longitude")),
                            heading = readFloat(location.child("heading"))
                        ),
                        isOnline = true
                    )
                )
            }
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

private fun readDouble(snapshot: DataSnapshot): Double =
    runCatching { snapshot.value<Double>() }.getOrDefault(0.0)

private fun readFloat(snapshot: DataSnapshot): Float =
    runCatching { snapshot.value<Float>() }.getOrDefault(0f)

private fun readString(snapshot: DataSnapshot): String =
    runCatching { snapshot.value<String>() }.getOrDefault("")
