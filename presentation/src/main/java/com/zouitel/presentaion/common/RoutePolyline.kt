package com.zouitel.presentaion.common

import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.PolyUtil

/**
 * Decodes a Google encoded polyline into map coordinates.
 * Invalid or empty route data is treated as an unavailable route.
 */
fun decodeRoutePolyline(encoded: String): List<LatLng> {
    if (encoded.isBlank()) return emptyList()
    return runCatching { PolyUtil.decode(encoded) }.getOrDefault(emptyList())
}
