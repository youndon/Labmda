package com.zouitel.presentaion.screens.shops

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.common.UserMode
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ShopWorkplaceScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val auth = Firebase.auth

    private val _userShop = MutableStateFlow<Shop?>(null)
    val userShop: StateFlow<Shop?> = _userShop.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        observeUserShopApplication()
    }

    private fun observeUserShopApplication() {
        val uid = auth.currentUser?.uid ?: run {
            _isLoading.value = false
            return
        }

        screenModelScope.launch {
            db.child("shops").valueEvents.collect { snapshot ->
                val myShop = snapshot.children.mapNotNull { child ->
                    try {
                        val id = child.key ?: return@mapNotNull null
                        val ownerUid: String = child.child("ownerUid").value()
                        if (ownerUid != uid) return@mapNotNull null

                        // Fixed: read "name" instead of "fullName"
                        val name: String = child.child("name").value()
                        val description: String = child.child("description").value()
                        val category: String = child.child("category").value()
                        val phone: String = child.child("phone").value()
                        val address: String = child.child("address").value()
                        val imageUrl: String = child.child("imageUrl").value()
                        val statusStr: String = child.child("status").value()
                        val createdAtTimestamp: Long = child.child("createdAtTimestamp").value()

                        val status = runCatching { Status.valueOf(statusStr) }.getOrDefault(Status.PENDING)

                        Shop(
                            id = id,
                            ownerUid = ownerUid,
                            name = name,
                            description = description,
                            category = category,
                            phone = phone,
                            address = address,
                            imageUrl = imageUrl,
                            status = status,
                            createdAtTimestamp = createdAtTimestamp
                        )
                    } catch (e: Exception) {
                        null
                    }
                }.firstOrNull()

                _userShop.value = myShop
                _isLoading.value = false
            }
        }
    }

    fun updateShopMode(shopMode: UserMode) {
        val uid = auth.currentUser?.uid ?: return
        screenModelScope.launch {
            db.child("users").child(uid).child("shopMode").setValue(shopMode.name)
        }
    }

    fun submitShopApplication(
        name: String,
        description: String,
        category: String,
        phone: String,
        address: String,
        imageUrl: String
    ) {
        val uid = auth.currentUser?.uid ?: return
        _isLoading.value = true

        screenModelScope.launch {
            val newShopRef = db.child("shops").push()
            val shopId = newShopRef.key ?: System.currentTimeMillis().toString()

            val shop = Shop(
                id = shopId,
                ownerUid = uid,
                name = name,
                description = description,
                category = category,
                phone = phone,
                address = address,
                imageUrl = imageUrl,
                status = Status.PENDING,
                createdAtTimestamp = System.currentTimeMillis()
            )

            newShopRef.setValue(shop)
            _isLoading.value = false
        }
    }
}