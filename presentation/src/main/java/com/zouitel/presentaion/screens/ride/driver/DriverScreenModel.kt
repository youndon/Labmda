package com.zouitel.presentaion.screens.ride.driver

import cafe.adriel.voyager.core.model.ScreenModel
import com.google.android.gms.maps.model.LatLng
import com.zouitel.presentaion.model.DriverOffer
import com.zouitel.presentaion.model.Trip
import com.zouitel.presentaion.model.TripStatus
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.database.database
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class DriverScreenModel : ScreenModel {

    private val database = Firebase.database
    private val tripsRef = database.reference("trips")
    private val usersRef = database.reference("users")

    // --- 1. Passenger: Create a New Ride Request ---
    suspend fun createTrip(trip: Trip): Result<String> = runCatching {
        val newTripRef = tripsRef.push()
        val tripId = newTripRef.key ?: throw IllegalStateException("Failed to generate database key")
        
        newTripRef.setValue(trip)
        newTripRef.child("id").setValue(tripId)
        tripId
    }

    // --- 2. Driver: Listen for Incoming "SEARCHING" Trips in Real Time ---
    fun observeSearchingTrips(): Flow<List<Trip>> {
        return tripsRef
            .orderByChild("status")
            .equalTo(TripStatus.SEARCHING.name)
            .valueEvents
            .map { snapshot ->
                snapshot.children.mapNotNull { doc ->
                    if (doc.exists) doc.value<Trip>() else null
                }
            }
    }

    // --- 3. Driver: Accept an Incoming Ride Request ---
    suspend fun acceptTrip(tripId: String, driverId: String): Result<Unit> = runCatching {
        tripsRef.child(tripId).updateChildren(
            mapOf(
                "driverId" to driverId,
                "status" to TripStatus.ACCEPTED.name
            )
        )
    }

    // --- 4. Driver & Passenger: Advance Trip Lifecycle Status ---
    suspend fun updateTripStatus(tripId: String, newStatus: TripStatus): Result<Unit> = runCatching {
        tripsRef.child(tripId).updateChildren(mapOf("status" to newStatus.name))
    }

    // --- 5. Driver: Broadcast Live GPS Coordinates ---
    suspend fun updateDriverLocation(driverId: String, latitude: Double, longitude: Double): Result<Unit> = runCatching {
        usersRef.child(driverId).child("currentLocation").setValue(
            mapOf(
                "latitude" to latitude,
                "longitude" to longitude
            )
        )
    }

    // --- 6. Passenger & Driver: Observe Specific Trip State in Real Time ---
    fun observeTrip(tripId: String): Flow<Trip?> {
        return tripsRef.child(tripId).valueEvents.map { snapshot ->
            if (snapshot.exists) snapshot.value<Trip>() else null
        }
    }

    // --- 7. Passenger: Submit Rating, Tip & Feedback Post-Trip ---
    suspend fun submitTripRating(
        tripId: String,
        driverId: String,
        rating: Int,
        tipAmount: Double,
        feedback: String
    ): Result<Unit> = runCatching {
        // Record rating & tip details on the trip document
        tripsRef.child(tripId).updateChildren(
            mapOf(
                "rating" to rating,
                "tipAmount" to tipAmount,
                "feedback" to feedback
            )
        )

        // Increment driver earnings if a tip was included
        if (tipAmount > 0.0) {
            val earningsRef = usersRef.child(driverId).child("totalEarnings")
            val currentEarnings = earningsRef.valueEvents.first().value<Double?>() ?: 0.0
            earningsRef.setValue(currentEarnings + tipAmount)
        }
    }

    // --- 8. Handle Trip Cancellation ---
    suspend fun cancelTrip(tripId: String, reason: String): Result<Unit> = runCatching {
        tripsRef.child(tripId).updateChildren(
            mapOf(
                "status" to TripStatus.CANCELLED.name,
                "cancellationReason" to reason
            )
        )
    }

    // --- 9. Passenger: Observe Trip History ---
    fun observePassengerTripHistory(passengerId: String): Flow<List<Trip>> {
        return tripsRef
            .orderByChild("passengerId")
            .equalTo(passengerId)
            .valueEvents
            .map { snapshot ->
                snapshot.children
                    .mapNotNull { doc -> if (doc.exists) doc.value<Trip>() else null }
                    .sortedByDescending { it.createdAt }
            }
    }
    // --- 10. Passenger: Observe Driver's Live Location ---
    fun observeDriverLocation(driverId: String): Flow<LatLng?> {
        return usersRef.child(driverId).child("currentLocation").valueEvents.map { snapshot ->
            if (snapshot.exists) {
                val lat = snapshot.child("latitude").value<Double>()
                val lng = snapshot.child("longitude").value<Double>()
                LatLng(lat, lng)
            } else null
        }
    }

    // Driver: Submit a counter-offer for a searching trip
    suspend fun submitFareOffer(tripId: String, offer: DriverOffer): Result<Unit> = runCatching {
        val offerRef = database.reference("trips").child(tripId).child("offers").push()
        val offerWithId = offer.copy(id = offerRef.key ?: "")
        offerRef.setValue(offerWithId)
    }

    // Passenger: Real-time listener for driver offers on their trip
    fun observeTripOffers(tripId: String): Flow<List<DriverOffer>> {
        return database.reference("trips").child(tripId).child("offers")
            .valueEvents
            .map { snapshot ->
                snapshot.children.mapNotNull { doc ->
                    if (doc.exists) doc.value<DriverOffer>() else null
                }
            }
    }

    // Passenger: Accept a specific driver offer and finalize trip assignment
    suspend fun acceptDriverOffer(tripId: String, selectedOffer: DriverOffer): Result<Unit> = runCatching {
        database.reference("trips").child(tripId).updateChildren(
            mapOf(
                "driverId" to selectedOffer.driverId,
                "fareAmount" to selectedOffer.offeredFare,
                "status" to TripStatus.ACCEPTED.name
            )
        )
        // Clear pending offers node after acceptance
        database.reference("trips").child(tripId).child("offers").removeValue()
    }
}