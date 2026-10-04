package com.zouitel.presentaion.screens.account

import android.content.Context
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.model.FoodMode
import com.zouitel.presentaion.model.DriveMode
import com.zouitel.presentaion.model.UserProfile
import dev.gitlive.firebase.auth.FirebaseAuth
import dev.gitlive.firebase.auth.FirebaseUser
import dev.gitlive.firebase.database.DatabaseReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AccountScreenModel(
    private val context: Context,
    private val auth: FirebaseAuth,
    private val db: DatabaseReference
): ScreenModel {

    private val _currentUser: MutableStateFlow<FirebaseUser?> = MutableStateFlow(auth.currentUser)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser

    @OptIn(ExperimentalCoroutinesApi::class)
    val profile: StateFlow<UserProfile?> = currentUser
        .flatMapLatest { user ->
            val uid = user?.uid
            if (uid.isNullOrEmpty()) {
                flowOf(null)
            } else {
                db.child("users")
                    .child(uid)
                    .valueEvents
                    .map { snapshot ->
                        if (snapshot.exists) {
                            runCatching { snapshot.value<UserProfile>() }.getOrNull()
                        } else {
                            null
                        }
                    }
            }
        }
        .stateIn(
            scope = screenModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    init {
        screenModelScope.launch(Dispatchers.IO) {
            auth.authStateChanged.collect { firebaseAuth ->
                _currentUser.value = firebaseAuth
            }
        }
    }

    // Save or update user profile
    suspend fun saveUserProfile(userProfile: UserProfile) {
        if (userProfile.uid.isBlank()) return
        db.child("users")
            .child(userProfile.uid)
            .setValue(userProfile)
    }

    // Fetch user profile
    fun getUserProfile(uid: String): Flow<UserProfile?> {
        if (uid.isBlank()) return flowOf(null)
        return try {
            db.child("users")
                .child(uid)
                .valueEvents
                .map { snapshot ->
                    if (snapshot.exists) {
                        runCatching { snapshot.value<UserProfile>() }.getOrNull()
                    } else {
                        null
                    }
                }
        } catch (e: Exception) {
            e.printStackTrace()
            flowOf(null)
        }
    }

    // Toggle active ride user mode in Realtime Database
    suspend fun updateUserMode(uid: String, mode: DriveMode) {
        if (uid.isBlank()) return
        db.child("users")
            .child(uid)
            .updateChildren(
                mapOf("driveMode" to mode)
            )
    }

    // Toggle active food user mode in Realtime Database
    suspend fun updateFoodUserMode(uid: String, mode: FoodMode) {
        if (uid.isBlank()) return
        db.child("users")
            .child(uid)
            .updateChildren(
                mapOf("foodMode" to mode)
            )
    }

    suspend fun updateUserProfile(user: UserProfile) {
        if (user.uid.isBlank()) return
        db.child("users").child(user.uid).updateChildren(
            mapOf("name" to user.name)
        )
    }
}