package com.zouitel.presentaion.screens.account

import android.content.Context
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.UserMode
import com.zouitel.presentaion.data.repository.UsersRepository
import com.zouitel.presentaion.model.UserProfile
import dev.gitlive.firebase.auth.FirebaseAuth
import dev.gitlive.firebase.auth.FirebaseUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AccountScreenModel(
    private val context: Context,
    private val auth: FirebaseAuth,
    private val usersRepository: UsersRepository
) : ScreenModel {
    private val _currentUser: MutableStateFlow<FirebaseUser?> = MutableStateFlow(auth.currentUser)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser

    @OptIn(ExperimentalCoroutinesApi::class)
    val profile: StateFlow<UserProfile?> = currentUser
        .flatMapLatest { user ->
            val uid = user?.uid
            if (uid.isNullOrEmpty()) flowOf(null) else usersRepository.observeProfile(uid)
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

    suspend fun saveUserProfile(userProfile: UserProfile) {
        if (userProfile.uid.isBlank() || auth.currentUser?.uid != userProfile.uid) return
        usersRepository.saveProfile(userProfile)
    }

    fun getUserProfile(uid: String) = usersRepository.observeProfile(uid)

    suspend fun updateUserMode(uid: String, mode: UserMode) {
        usersRepository.updateUserMode(uid, mode)
    }

    suspend fun updateFoodUserMode(uid: String, mode: UserMode) {
        usersRepository.updateFoodUserMode(uid, mode)
    }
}