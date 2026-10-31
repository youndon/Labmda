package com.zouitel.presentaion.admin

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.model.UserProfile
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.database.database
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
            db.child("users").valueEvents.collect { snapshot ->
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
            db.child("users").child(uid).child("foodStatus").setValue(newStatus.name)

            if (newStatus == Status.APPROVED) {
                val userSnapshot = db.child("users").child(uid).valueEvents.let { flow ->
                    var snapshotData: dev.gitlive.firebase.database.DataSnapshot? = null
                    val job = screenModelScope.launch {
                        flow.collect {
                            snapshotData = it
                            throw java.util.concurrent.CancellationException()
                        }
                    }
                    job.join()
                    snapshotData
                }

                userSnapshot?.let { snap ->
                    val restaurantName = runCatching { snap.child("sourceName").value<String>() }.getOrDefault("My Restaurant")
                    val imageUrl = runCatching { snap.child("restaurantImageUrl").value<String>() }.getOrDefault("")
                    val deliveryFee = runCatching { snap.child("deliveryFee").value<Double>() }.getOrDefault(0.0)
                    val estimatedTimeMinutes = runCatching { snap.child("estimatedTimeMinutes").value<Int>() }.getOrDefault(20)
                    val managerPhone = runCatching { snap.child("managerPhone").value<String>() }.getOrDefault("")

                    val restaurantMap = mapOf(
                        "id" to uid,
                        "name" to restaurantName,
                        "imageUrl" to imageUrl,
                        "rating" to 5.0,
                        "deliveryFee" to deliveryFee,
                        "estimatedTimeMinutes" to estimatedTimeMinutes,
                        "isOpen" to true,
                        "paymentMethods" to listOf("GCASH", "CASH"),
                        "address" to runCatching { snap.child("restaurantAddress").value<String>() }.getOrDefault(""),
                        "latitude" to runCatching { snap.child("restaurantLatitude").value<Double>() }.getOrDefault(0.0),
                        "longitude" to runCatching { snap.child("restaurantLongitude").value<Double>() }.getOrDefault(0.0)
                    )
                    db.child("restaurants").child(uid).setValue(restaurantMap)
                    db.child("restaurantPrivate").child(uid).setValue(mapOf("managerPhone" to managerPhone))
                }
            } else if (newStatus == Status.REJECTED) {
                db.child("restaurants").child(uid).removeValue()
                db.child("restaurantPrivate").child(uid).removeValue()
            }
        }
    }
}