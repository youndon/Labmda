package com.zouitel.presentaion.admin

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.screens.wallet.AdminPaymentsDashboard
import com.zouitel.presentaion.screens.wallet.WalletRepository
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.database.DataSnapshot
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class AdminOverviewState(
    val pendingApprovals: Int = 0,
    val pendingShops: Int = 0,
    val pendingRestaurants: Int = 0,
    val pendingDrivers: Int = 0,
    val pendingCouriers: Int = 0,
    val activeOrders: Int = 0,
    val activeRides: Int = 0,
    val paymentIssues: Int = 0,
    val paymentsProcessing: Int = 0,
    val payoutAlerts: Int = 0,
    val pendingPayoutRequests: Int = 0,
    val failedPayouts: Int = 0,
    val pendingPayoutAccounts: Int = 0,
    val outstandingCashCentavos: Long = 0L,
    val paymentDashboard: AdminPaymentsDashboard = AdminPaymentsDashboard(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val lastUpdated: Long = 0L
)

class PanelScreenModel : ScreenModel {
    private val db = Firebase.database.reference()
    private val walletRepository = WalletRepository()
    private val _state = MutableStateFlow(AdminOverviewState())
    val state: StateFlow<AdminOverviewState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        screenModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(isLoading = true, error = null)
            runCatching {
                val users = db.child("users").valueEvents.first()
                val shops = db.child("shops").valueEvents.first()
                val orders = db.child("orders").valueEvents.first()
                val trips = db.child("trips").valueEvents.first()
                val payments = walletRepository.loadAdminDashboard()

                fun status(snapshot: DataSnapshot, path: String): String =
                    path.split("/").fold(snapshot) { node, key -> node.child(key) }
                        .let { runCatching { it.value<String>() }.getOrDefault("") }
                        .uppercase()

                val pendingShops = shops.children.count { status(it, "status") == "PENDING" }
                val pendingRestaurants = users.children.count { status(it, "foodStatus") == "PENDING" }
                val pendingDrivers = users.children.count { status(it, "driverService/status") == "PENDING" }
                val pendingCouriers = users.children.count { status(it, "deliveryServices/status") == "PENDING" }

                val activeOrderStatuses = setOf("PENDING", "PLACED", "ACCEPTED", "CONFIRMED", "PREPARING", "READY_FOR_PICKUP", "OUT_FOR_DELIVERY")
                val activeRideStatuses = setOf("PENDING", "REQUESTED", "ACCEPTED", "DRIVER_ASSIGNED", "DRIVER_ARRIVING", "ARRIVED", "IN_PROGRESS", "ONGOING", "STARTED")
                val activeOrders = orders.children.count { status(it, "status") in activeOrderStatuses }
                val activeRides = trips.children.count { status(it, "status") in activeRideStatuses }

                val paymentIssues = payments.payments.count {
                    it.status.uppercase() in setOf("FAILED", "EXPIRED", "REQUIRES_ATTENTION", "REFUND_FAILED")
                }
                val paymentsProcessing = payments.payments.count {
                    it.status.uppercase() in setOf("PENDING", "PROCESSING", "REQUIRES_ACTION", "AWAITING_PAYMENT")
                }
                val pendingPayoutRequests = payments.payoutRequests.count { it.status.uppercase() == "PENDING_ADMIN_APPROVAL" }
                val failedPayouts = payments.payouts.count { it.status.uppercase() in setOf("FAILED", "REQUIRES_ACTION", "CANCELLED") }
                val pendingPayoutAccounts = payments.payoutAccountReviews.count { it.status.uppercase() == "PENDING_REVIEW" }

                AdminOverviewState(
                    pendingApprovals = pendingShops + pendingRestaurants + pendingDrivers + pendingCouriers,
                    pendingShops = pendingShops,
                    pendingRestaurants = pendingRestaurants,
                    pendingDrivers = pendingDrivers,
                    pendingCouriers = pendingCouriers,
                    activeOrders = activeOrders,
                    activeRides = activeRides,
                    paymentIssues = paymentIssues,
                    paymentsProcessing = paymentsProcessing,
                    payoutAlerts = pendingPayoutRequests + failedPayouts + pendingPayoutAccounts,
                    pendingPayoutRequests = pendingPayoutRequests,
                    failedPayouts = failedPayouts,
                    pendingPayoutAccounts = pendingPayoutAccounts,
                    outstandingCashCentavos = payments.cashOwedCentavos + payments.cashPlatformOwedCentavos,
                    paymentDashboard = payments,
                    isLoading = false,
                    lastUpdated = System.currentTimeMillis()
                )
            }.onSuccess { _state.value = it }
                .onFailure {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = it.message ?: "Could not load the admin overview. Check administrator access and Firebase connectivity."
                    )
                }
        }
    }
}
