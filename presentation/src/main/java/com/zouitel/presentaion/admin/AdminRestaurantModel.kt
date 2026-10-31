package com.zouitel.presentaion.admin

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.model.UserProfile
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.database.database
import dev.gitlive.firebase.functions.functions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AdminRestaurantModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val functions = Firebase.functions("asia-southeast1")
    private val _applications = MutableStateFlow<List<UserProfile>>(emptyList())
    val applications: StateFlow<List<UserProfile>> = _applications.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _reviewingUid = MutableStateFlow<String?>(null)
    val reviewingUid: StateFlow<String?> = _reviewingUid.asStateFlow()

    init { listenToRestaurantApplications() }

    private fun listenToRestaurantApplications() {
        screenModelScope.launch(Dispatchers.IO) {
            db.child("users").orderByChild("foodStatus")
                .equalTo(Status.PENDING.name).valueEvents.collect { snapshot ->
                    _applications.value = snapshot.children.mapNotNull { child ->
                        runCatching {
                            val uid = child.key ?: return@mapNotNull null
                            UserProfile(
                                uid = uid,
                                fullName = runCatching { child.child("fullName").value<String>() }.getOrDefault(""),
                                phoneNumber = runCatching { child.child("phoneNumber").value<String>() }
                                    .getOrElse { runCatching { child.child("phone").value<String>() }.getOrDefault("") },
                                foodStatus = Status.PENDING,
                                restaurantName = runCatching { child.child("sourceName").value<String>() }.getOrDefault(""),
                                restaurantAddress = runCatching { child.child("restaurantAddress").value<String>() }.getOrDefault(""),
                                restaurantImageUrl = runCatching { child.child("restaurantImageUrl").value<String>() }.getOrDefault("")
                            )
                        }.getOrNull()
                    }
                }
        }
    }

    fun updateRestaurantStatus(uid: String, newStatus: Status) {
        screenModelScope.launch(Dispatchers.IO) {
            _error.value = null
            _reviewingUid.value = uid
            runCatching {
                functions.httpsCallable("adminReviewRestaurant")(
                    mapOf("restaurantUid" to uid, "decision" to newStatus.name)
                )
            }.onFailure {
                _error.value = it.message ?: "Could not review this restaurant application."
            }
            _reviewingUid.value = null
        }
    }
}