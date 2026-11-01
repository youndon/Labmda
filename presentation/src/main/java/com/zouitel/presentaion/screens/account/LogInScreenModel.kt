package com.zouitel.presentaion.screens.account

import cafe.adriel.voyager.core.model.ScreenModel
import com.zouitel.presentaion.data.repository.UsersRepository
import com.zouitel.presentaion.model.UserProfile
import com.zouitel.presentaion.screens.ride.DataOrException

class LogInScreenModel(
    private val usersRepository: UsersRepository
) : ScreenModel {
    suspend fun saveUserProfile(userProfile: UserProfile): DataOrException<Unit, Exception> {
        val result = DataOrException<Unit, Exception>()
        return try {
            usersRepository.saveProfile(userProfile)
            result.data = Unit
            result
        } catch (error: Exception) {
            result.e = Exception(error.message, error)
            result
        }
    }

    suspend fun getUserProfile(uid: String): DataOrException<UserProfile?, Exception> {
        val result = DataOrException<UserProfile?, Exception>()
        return try {
            result.data = usersRepository.getProfile(uid)
            result
        } catch (error: Exception) {
            result.e = Exception(error.message, error)
            result
        }
    }
}