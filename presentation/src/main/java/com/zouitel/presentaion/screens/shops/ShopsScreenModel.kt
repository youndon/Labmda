package com.zouitel.presentaion.screens.shops

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.common.UserMode
import com.zouitel.presentaion.model.UserProfile
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ShopsScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val auth = Firebase.auth

    private val _approvedShops = MutableStateFlow<List<Shop>>(emptyList())
    val approvedShops: StateFlow<List<Shop>> = _approvedShops.asStateFlow()

    private val _userProfile = MutableStateFlow(UserProfile())
    val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()

    private val _managerShop = MutableStateFlow<Shop?>(null)
    val managerShop: StateFlow<Shop?> = _managerShop.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        val uid = auth.currentUser?.uid

        screenModelScope.launch {
            if (uid != null) {
                launch {
                    db.child("user_profiles").child(uid).valueEvents.collect { snapshot ->
                        if (snapshot.exists) {
                            val shopModeStr = runCatching { snapshot.child("shopMode").value<String>() }.getOrDefault("CLIENT")
                            _userProfile.value = UserProfile(
                                uid = uid,
                                shopMode = runCatching { UserMode.valueOf(shopModeStr) }.getOrDefault(UserMode.CLIENT),
                            )
                        }
                    }
                }
            }

            launch {
                db.child("publicShops").valueEvents.collect { snapshot ->
                    val list = mutableListOf<Shop>()
                    var ownedShop: Shop? = null

                    snapshot.children.forEach { child ->
                        try {
                            val id = child.key ?: return@forEach
                            val ownerUid: String = ""
                            val name: String = child.child("name").value()
                            val description: String = child.child("description").value()
                            val category: String = child.child("category").value()
                            val phone: String = child.child("phone").value()
                            val address: String = child.child("address").value()
                            val imageUrl: String = child.child("imageUrl").value()
                            val statusStr: String = child.child("status").value()
                            val createdAtTimestamp: Long = child.child("createdAtTimestamp").value()

                            // Read online/offline status
                            val isOnline = runCatching { child.child("isOnline").value<Boolean>() }.getOrDefault(false)

                            val status = runCatching { Status.valueOf(statusStr) }.getOrDefault(Status.PENDING)

                            val shop = Shop(
                                id = id,
                                ownerUid = ownerUid,
                                name = name,
                                description = description,
                                category = category,
                                phone = phone,
                                address = address,
                                imageUrl = imageUrl,
                                status = status,
                                createdAtTimestamp = createdAtTimestamp,
                                isOnline = isOnline
                            )

                            if (uid != null && ownerUid == uid && status == Status.APPROVED) {
                                ownedShop = shop
                            }

                            if (status == Status.APPROVED) {
                                list.add(shop)
                            }
                        } catch (e: Exception) {
                            // Ignore malformed children
                        }
                    }

                    _approvedShops.value = list
                    _managerShop.value = ownedShop
                    _isLoading.value = false
                }
            }
        }
    }
}