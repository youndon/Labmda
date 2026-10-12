package com.zouitel.presentaion.screens.courier

import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.module

val courierModule = module {
    factoryOf(::CourierWorkplaceScreenModel)
    factoryOf(::CourierDashboardScreenModel)
}