package com.zouitel.presentaion.mapper

import com.google.android.gms.maps.model.LatLng
import dev.gitlive.firebase.firestore.GeoPoint

fun GeoPoint.toLatLng(): LatLng = LatLng(latitude, longitude)
fun LatLng.toGitLiveGeoPoint(): GeoPoint = GeoPoint(latitude, longitude)