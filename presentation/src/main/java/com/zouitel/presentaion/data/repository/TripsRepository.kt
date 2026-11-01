package com.zouitel.presentaion.data.repository

import com.zouitel.presentaion.common.Trip
import dev.gitlive.firebase.database.DataSnapshot
import dev.gitlive.firebase.database.DatabaseReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class DriverTripFeed(
    val availableTrips: List<Trip>,
    val activeTrips: List<Trip>,
    val vehicleType: String
)

/** Realtime Database access for passenger and driver trip data. */
class TripsRepository(
    private val database: DatabaseReference
) {
    fun observePassengerTrips(passengerId: String): Flow<List<Trip>> =
        database.child("trips")
            .orderByChild("passengerId")
            .equalTo(passengerId)
            .valueEvents
            .map { snapshot ->
                snapshot.children.mapNotNull { it.toTripOrNull() }
                    .sortedByDescending { it.createdAtTimestamp }
            }

    fun observeDriverTrips(driverId: String): Flow<DriverTripFeed> {
        val trips = database.child("trips")
        val openRequests = database.child("availableRideRequests").valueEvents
        val assignedTrips = trips.orderByChild("driverId").equalTo(driverId).valueEvents
        val vehicleType = database.child("users").child(driverId)
            .child("driverService").child("vehicleInfo").child("type").valueEvents

        return combine(openRequests, assignedTrips, vehicleType) { open, assigned, vehicle ->
            val currentVehicleType = runCatching { vehicle.value<String>() }.getOrDefault("").uppercase()
            val available = mutableListOf<Trip>()
            val active = mutableListOf<Trip>()

            (open.children + assigned.children).distinctBy { it.key }.forEach { snapshot ->
                val trip = snapshot.toTripOrNull() ?: return@forEach
                val assignedDriverId = trip.driverId
                if (
                    trip.status == "REQUESTED" &&
                    assignedDriverId.isBlank() &&
                    (trip.serviceType.isBlank() || trip.serviceType.equals("RIDE", true) ||
                        trip.serviceType.equals(currentVehicleType, true))
                ) {
                    available += trip
                } else if (
                    assignedDriverId == driverId &&
                    trip.status != "AWAITING_PAYMENT" &&
                    trip.status != "COMPLETED" &&
                    trip.status != "CANCELLED"
                ) {
                    active += trip
                }
            }

            DriverTripFeed(
                availableTrips = available.sortedByDescending { it.createdAtTimestamp },
                activeTrips = active.sortedByDescending { it.createdAtTimestamp },
                vehicleType = currentVehicleType
            )
        }
    }

    fun observeDriverOnline(driverId: String): Flow<Boolean> =
        database.child("users").child(driverId).child("driverService").child("isOnline")
            .valueEvents
            .map { snapshot -> runCatching { snapshot.value<Boolean>() }.getOrDefault(false) }

    fun observeTripPaymentMethod(tripId: String): Flow<String> =
        database.child("trips").child(tripId).child("paymentMethod")
            .valueEvents
            .map { snapshot -> runCatching { snapshot.value<String>() }.getOrDefault("") }

    suspend fun setDriverOnline(driverId: String, online: Boolean) {
        val reference = database.child("users").child(driverId).child("driverService").child("isOnline")
        if (online) {
            runCatching { reference.onDisconnect().setValue(false) }
            reference.setValue(true)
        } else {
            runCatching { reference.onDisconnect().cancel() }
            reference.setValue(false)
        }
    }

    suspend fun updateDriverLocation(driverId: String, latitude: Double, longitude: Double, heading: Float) {
        database.child("driverLocations").child(driverId).setValue(
            mapOf("latitude" to latitude, "longitude" to longitude, "heading" to heading)
        )
    }

    suspend fun setDriverOffline(driverId: String) {
        database.child("users").child(driverId).child("driverService").child("isOnline").setValue(false)
    }
}

private fun DataSnapshot.toTripOrNull(): Trip? {
    val id = key ?: return null
    fun string(name: String) = runCatching { child(name).value<String>() }.getOrDefault("")
    fun double(name: String) = runCatching { child(name).value<Double>() }.getOrDefault(0.0)
    fun long(name: String) = runCatching { child(name).value<Long>() }.getOrDefault(0L)

    return runCatching {
        Trip(
            id = id,
            passengerId = string("passengerId"),
            passengerName = string("passengerName"),
            passengerPhone = string("passengerPhone"),
            pickupLocation = string("pickupLocation"),
            dropoffLocation = string("dropoffLocation"),
            pickupLat = double("pickupLat"),
            pickupLng = double("pickupLng"),
            dropoffLat = double("dropoffLat"),
            dropoffLng = double("dropoffLng"),
            fare = double("fare"),
            serviceType = string("serviceType"),
            routeDuration = string("routeDuration"),
            routePolyline = string("routePolyline"),
            driverId = string("driverId"),
            driverName = string("driverName"),
            driverPhone = string("driverPhone"),
            vehicleDetails = string("vehicleDetails"),
            status = string("status").ifBlank { "REQUESTED" },
            paymentMethod = string("paymentMethod"),
            paymentStatus = string("paymentStatus").ifBlank { "UNPAID" },
            paymentId = string("paymentId"),
            createdAtTimestamp = long("createdAtTimestamp"),
            cashCollectionStatus = string("cashCollectionStatus"),
            cashCollectedBy = string("cashCollectedBy"),
            cashCollectedByRole = string("cashCollectedByRole"),
            cashCollectedAmountCentavos = long("cashCollectedAmountCentavos"),
            cashPlatformOwedCentavos = long("cashPlatformOwedCentavos"),
            cashCollectorEarningsCentavos = long("cashCollectorEarningsCentavos"),
            cashRemittedCentavos = long("cashRemittedCentavos"),
            cashOutstandingRemittanceCentavos = long("cashOutstandingRemittanceCentavos"),
            cashRemittanceStatus = string("cashRemittanceStatus")
        )
    }.getOrNull()
}
