package com.zouitel.presentaion.admin

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.model.UserProfile
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.database.database
import com.zouitel.presentaion.screens.trustedTransactionCallData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AdminRestaurantModel : ScreenModel {
    private val db = Firebase.database.reference()

    private val _applications = MutableStateFlow<List<UserProfile>>(emptyList())
    val applications: StateFlow<List<UserProfile>> = _applications.asStateFlow()

    init {
        listenToRestaurantApplications()
    }

    private fun listenToRestaurantApplications() {
        screenModelScope.launch(Dispatchers.IO) {
            db.child("users").orderByChild("foodStatus").equalTo("PENDING").valueEvents.collect { snapshot ->
                val profiles = snapshot.children.mapNotNull { child ->
                    try {
                        val uid = child.key ?: return@mapNotNull null
                        val fullName = runCatching { child.child("fullName").value<String>() }.getOrDefault("")
                        val phoneNumber = runCatching { child.child("phoneNumber").value<String>() }
                            .getOrElse { runCatching { child.child("phone").value<String>() }.getOrDefault("") }

                        val foodStatusStr = runCatching { child.child("foodStatus").value<String>() }.getOrDefault("NONE")
                        val foodStatus = runCatching { Status.valueOf(foodStatusStr) }.getOrDefault(Status.NONE)

                        UserProfile(
                            uid = uid,
                            fullName = fullName,
                            phoneNumber = phoneNumber,
                            foodStatus = foodStatus
                        )
                    } catch (e: Exception) {
                        null
                    }
                }.filter { it.foodStatus == Status.PENDING }

                _applications.value = profiles
            }
        }
    }

    fun updateRestaurantStatus(uid: String, newStatus: Status) {
        screenModelScope.launch(Dispatchers.IO) {
            trustedTransactionCallData(
                "adminUpdateProviderStatus",
                mapOf("service" to "RESTAURANT", "uid" to uid, "status" to newStatus.name)
            )
        }
    }
}
