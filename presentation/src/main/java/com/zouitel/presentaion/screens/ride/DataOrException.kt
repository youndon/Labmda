package com.zouitel.presentaion.screens.ride

data class DataOrException<T, E : Exception?>(
    var data: T? = null,
    var e: E? = null
)