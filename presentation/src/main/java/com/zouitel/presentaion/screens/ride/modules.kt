package com.zouitel.presentaion.screens.ride

import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.module

val rideModule = module {
    factoryOf(::DriverWorkplaceScreenModel)
    factoryOf(::DriverDashboardScreenModel)
    factoryOf(::PassengerScreenModel)
}