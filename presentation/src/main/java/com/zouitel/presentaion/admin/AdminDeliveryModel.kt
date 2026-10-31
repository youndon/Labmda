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

    init { listenToApplications() }

    private fun listenToApplications() {
        screenModelScope.launch(Dispatchers.IO) {
            db.child("users").orderByChild("deliveryServices/status")
                .equalTo(Status.PENDING.name).valueEvents.collect { snapshot ->
                    _applications.value = snapshot.children.mapNotNull { child ->
                        runCatching {
                            val uid = child.key ?: return@mapNotNull null
                            val delivery = child.child("deliveryServices")
                            val type = runCatching {
                                DeliveryType.valueOf(delivery.child("deliveryType").value<String>())
                            }.getOrDefault(DeliveryType.COURIER)
                            UserProfile(
                                uid = uid,
                                fullName = runCatching { child.child("fullName").value<String>() }.getOrDefault(""),
                                phoneNumber = runCatching { child.child("phoneNumber").value<String>() }
                                    .getOrElse { runCatching { child.child("phone").value<String>() }.getOrDefault("") },
                                deliveryServices = DeliveryAccountInfo(deliveryType = type, status = Status.PENDING)
                            )
                        }.getOrNull()
                    }
                }
        }
    }

    fun updateCourierStatus(uid: String, newStatus: Status) {
        screenModelScope.launch(Dispatchers.IO) {
            db.child("users").child(uid).child("deliveryServices").child("status").setValue(newStatus.name)
        }
    }
}