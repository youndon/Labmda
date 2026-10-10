package com.zouitel.presentaion.admin

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.screens.shops.Shop
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AdminShopsModel : ScreenModel {
    private val db = Firebase.database.reference()

    private val _pendingShops = MutableStateFlow<List<Shop>>(emptyList())
    val pendingShops: StateFlow<List<Shop>> = _pendingShops.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        listenToShopApplications()
    }

    private fun listenToShopApplications() {
        screenModelScope.launch {
            db.child("shops").valueEvents.collect { snapshot ->
                val list = snapshot.children.mapNotNull { child ->
                    try {
                        val id = child.key ?: return@mapNotNull null
                        val ownerUid: String = child.child("ownerUid").value()
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
                }.filter { it.status == Status.PENDING }

                _pendingShops.value = list
                _isLoading.value = false
            }
        }
    }

    fun updateShopStatus(shopId: String, newStatus: Status) {
        screenModelScope.launch {
            db.child("shops").child(shopId).child("status").setValue(newStatus.name)
        }
    }
}