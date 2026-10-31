package com.zouitel.presentaion.screens.delivery

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.model.UserProfile
import com.zouitel.presentaion.model.VehicleInfo
import com.zouitel.presentaion.model.VehicleType
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DeliveryWorkplaceScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val auth = Firebase.auth

    private val _profile = MutableStateFlow(UserProfile())
    val profile: StateFlow<UserProfile> = _profile.asStateFlow()

    private val _deliveryAccount = MutableStateFlow<DeliveryAccountInfo?>(null)
    val deliveryAccount: StateFlow<DeliveryAccountInfo?> = _deliveryAccount.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadUserProfileAndService()
    }

    private fun loadUserProfileAndService() {
        val uid = auth.currentUser?.uid ?: run {
            _isLoading.value = false
            return
        }

        screenModelScope.launch(Dispatchers.IO) {
            db.child("users").child(uid).valueEvents.collect { snapshot ->
                if (snapshot.exists) {
                    val name = runCatching { snapshot.child("fullName").value<String>() }
                        .getOrElse {
                            runCatching {
                                snapshot.child("name").value<String>()
                            }.getOrDefault("")
                        }
                    val phone = runCatching { snapshot.child("phoneNumber").value<String>() }
                        .getOrElse {
                            runCatching {
                                snapshot.child("phone").value<String>()
                            }.getOrDefault("")
                        }

                    val child = snapshot.child("deliveryServices")
                    val accountInfo = if (child.exists) {
                        try {
                            val id =
                                runCatching { child.child("id").value<String>() }.getOrDefault("")
                            val serviceStr = runCatching {
                                child.child("deliveryType").value<String>()
                            }.getOrDefault("COURIER")
                            val deliveryType =
                                runCatching { DeliveryType.valueOf(serviceStr) }.getOrDefault(
                                    DeliveryType.COURIER
                                )

                            val vehicleType = runCatching {
                                child.child("vehicleType").value<String>()
                            }.getOrDefault("")
                            val identityDocumentLink = runCatching {
                                child.child("identityDocumentLink").value<String>()
                            }.getOrDefault("")
                            val statusStr = runCatching {
                                child.child("status").value<String>()
                            }.getOrDefault("NONE")
                            val status =
                                runCatching { Status.valueOf(statusStr) }.getOrDefault(Status.NONE)
                            val isOnline = runCatching {
                                child.child("isOnline").value<Boolean>()
                            }.getOrDefault(false)

                            DeliveryAccountInfo(
                                id = id,
                                deliveryType = deliveryType,
                                vehicleInfo = VehicleInfo(
                                    type = runCatching { VehicleType.valueOf(vehicleType.uppercase()) }
                                        .getOrDefault(VehicleType.OTHER),
                                    serviceType = "Delivery"
                                ),
                                identityDocumentLink = identityDocumentLink,
                                status = status,
                                isOnline = isOnline
                            )
                        } catch (e: Exception) {
                            null
                        }
                    } else {
                        null
                    }

                    _profile.value = UserProfile(
                        uid = uid,
                        fullName = name,
                        phoneNumber = phone,
                        deliveryServices = accountInfo
                    )
                    _deliveryAccount.value = accountInfo
                } else {
                    _profile.value = UserProfile(uid = uid)
                    _deliveryAccount.value = null
                }
                _isLoading.value = false
            }
        }
    }

    fun saveDeliveryService(
        deliveryType: DeliveryType,
        vehicleType: String,
        identityDocumentLink: String,
        keepStatus: Status? = null
    ) {
        val uid = auth.currentUser?.uid ?: return
        _isLoading.value = true

        screenModelScope.launch(Dispatchers.IO) {
            val ref = db.child("users").child(uid).child("deliveryServices")
            val statusToSet = keepStatus ?: Status.PENDING

            val serviceMap = mapOf(
                "id" to uid,
                "deliveryType" to deliveryType.name,
                "vehicleType" to vehicleType,
                "identityDocumentLink" to identityDocumentLink,
                "status" to statusToSet.name,
                "isOnline" to false
            )

            ref.setValue(serviceMap)
            _isLoading.value = false
        }
    }

    fun updateOnlineStatus(isOnline: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        screenModelScope.launch(Dispatchers.IO) {
            db.child("users").child(uid).child("deliveryServices").child("isOnline")
                .setValue(isOnline)
        }
    }

    fun deleteDeliveryService() {
        val uid = auth.currentUser?.uid ?: return
        screenModelScope.launch(Dispatchers.IO) {
            db.child("users").child(uid).child("deliveryServices").removeValue()
        }
    }
}