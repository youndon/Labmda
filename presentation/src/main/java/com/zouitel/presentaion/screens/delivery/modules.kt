package com.zouitel.presentaion.screens.delivery

import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.module

val deliveryModule = module {
    factoryOf(::DeliveryWorkplaceScreenModel)
    factoryOf(::DeliveryDashboardScreenModel)
}