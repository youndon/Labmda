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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var loadJob: Job? = null

    init {
        loadData()
    }

    fun refresh() {
        loadData()
    }

    private fun loadData() {
        loadJob?.cancel()
        val uid = auth.currentUser?.uid

        loadJob = screenModelScope.launch {
            _error.value = null
            _isLoading.value = _approvedShops.value.isEmpty()
            if (uid != null) {
                launch {
                    db.child("users").child(uid).valueEvents
                        .catch { cause -> if (cause is CancellationException) throw cause }
                        .collect { snapshot ->
                        val shopModeStr = runCatching {
                            snapshot.child("shopMode").value<String>()
                        }.getOrDefault("CLIENT")
                        _userProfile.value = UserProfile(
                            uid = uid,
                            shopMode = runCatching { UserMode.valueOf(shopModeStr) }
                                .getOrDefault(UserMode.CLIENT),
                        )
                    }
                }
                launch {
                    db.child("shops").orderByChild("ownerUid").equalTo(uid).valueEvents
                        .catch { cause -> if (cause is CancellationException) throw cause }
                        .collect { snapshot ->
                        val ownedShop = snapshot.children.firstNotNullOfOrNull { child ->
                            runCatching {
                                val id = child.key ?: return@runCatching null
                                val ownerUid = child.child("ownerUid").value<String>()
                                if (ownerUid != uid) return@runCatching null
                                val statusStr = runCatching {
                                    child.child("status").value<String>()
                                }.getOrDefault("PENDING")
                                Shop(
                                    id = id,
                                    ownerUid = ownerUid,
                                    name = runCatching { child.child("name").value<String>() }.getOrDefault(""),
                                    description = runCatching { child.child("description").value<String>() }.getOrDefault(""),
                                    category = runCatching { child.child("category").value<String>() }.getOrDefault("General Store"),
                                    phone = runCatching { child.child("phone").value<String>() }.getOrDefault(""),
                                    address = runCatching { child.child("address").value<String>() }.getOrDefault(""),
                                    imageUrl = runCatching { child.child("imageUrl").value<String>() }.getOrDefault(""),
                                    status = runCatching { Status.valueOf(statusStr) }.getOrDefault(Status.PENDING),
                                    createdAtTimestamp = runCatching { child.child("createdAtTimestamp").value<Long>() }.getOrDefault(0L),
                                    isOnline = runCatching { child.child("isOnline").value<Boolean>() }.getOrDefault(false),
                                    latitude = runCatching { child.child("latitude").value<Double>() }.getOrNull(),
                                    longitude = runCatching { child.child("longitude").value<Double>() }.getOrNull()
                                )
                            }.getOrNull()
                        }
                        _managerShop.value = ownedShop?.takeIf { it.status == Status.APPROVED }
                    }
                }
            }

            launch {
                db.child("publicShops").valueEvents
                    .catch { cause ->
                        if (cause is CancellationException) throw cause
                        _error.value = cause.message ?: "Shops could not be loaded. Check your connection and try again."
                        _isLoading.value = false
                    }
                    .collect { snapshot ->
                    val list = mutableListOf<Shop>()
                    snapshot.children.forEach { child ->
                        try {
                            val id = child.key ?: return@forEach
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

                            if (status == Status.APPROVED) {
                                list.add(shop)
                            }
                        } catch (e: Exception) {
                            // Ignore malformed children
                        }
                    }

                    _approvedShops.value = list
                    _isLoading.value = false
                    _error.value = null
                }
            }
        }
    }
}