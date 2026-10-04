// commonMain
package com.zouitel.presentaion.mapper

import dev.gitlive.firebase.firestore.GeoPoint
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Calculates distance directly on GitLive's GeoPoint class.
 */
fun GeoPoint.distanceToMeters(other: GeoPoint): Double {
    val earthRadiusMeters = 6371000.0

    val dLat = (other.latitude - this.latitude).toRadians()
    val dLon = (other.longitude - this.longitude).toRadians()

    val lat1 = this.latitude.toRadians()
    val lat2 = other.latitude.toRadians()

    val a = sin(dLat / 2) * sin(dLat / 2) +
            sin(dLon / 2) * sin(dLon / 2) * cos(lat1) * cos(lat2)
    val c = 2 * asin(sqrt(a))

    return earthRadiusMeters * c
}

private fun Double.toRadians(): Double = this * PI / 180.0