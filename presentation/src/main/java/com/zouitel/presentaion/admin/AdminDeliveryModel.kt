package com.zouitel.presentaion.admin

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.model.UserProfile
import com.zouitel.presentaion.screens.delivery.DeliveryAccountInfo
import com.zouitel.presentaion.screens.delivery.DeliveryType
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AdminDeliveryModel : ScreenModel {
    private val db = Firebase.database.reference()

    private val _applications = MutableStateFlow<List<UserProfile>>(emptyList())
    val applications: StateFlow<List<UserProfile>> = _applications.asStateFlow()

    init {
        listenToApplications()
    }

    private fun listenToApplications() {
        screenModelScope.launch(Dispatchers.IO) {
            db.child("users").valueEvents.collect { snapshot ->
                val profiles = snapshot.children.mapNotNull { child ->
                    try {
                        val uid = child.key ?: return@mapNotNull null
                        val fullName = runCatching { child.child("fullName").value<String>() }
                            .getOrElse { runCatching { child.child("name").value<String>() }.getOrDefault("") }
                        val phoneNumber = runCatching { child.child("phoneNumber").value<String>() }
                            .getOrElse { runCatching { child.child("phone").value<String>() }.getOrDefault("") }

                        val deliveryChild = child.child("deliveryServices")
                        val deliveryAccount = if (deliveryChild.exists) {
                            val serviceStr = runCatching { deliveryChild.child("deliveryType").value<String>() }.getOrDefault("COURIER")
                            val deliveryType = runCatching { DeliveryType.valueOf(serviceStr) }.getOrDefault(DeliveryType.COURIER)
                            val vehicleType = runCatching { deliveryChild.child("vehicleType").value<String>() }.getOrDefault("")
                            val statusStr = runCatching { deliveryChild.child("status").value<String>() }.getOrDefault("NONE")
                            val status = runCatching { Status.valueOf(statusStr) }.getOrDefault(Status.NONE)

                            DeliveryAccountInfo(
                                deliveryType = deliveryType,
                                status = status
                            )
                        } else {
                            null
                        }

                        if (deliveryAccount?.status == Status.PENDING) {
                            UserProfile(
                                uid = uid,
                                fullName = fullName,
                                phoneNumber = phoneNumber,
                                deliveryServices = deliveryAccount
                            )
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        null
                    }
                }

                _applications.value = profiles
            }
        }
    }

    fun updateCourierStatus(uid: String, newStatus: Status) {
        screenModelScope.launch(Dispatchers.IO) {
            db.child("users").child(uid).child("deliveryServices").child("status").setValue(newStatus.name)
        }
    }
}