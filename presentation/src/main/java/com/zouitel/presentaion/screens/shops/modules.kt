package com.zouitel.presentaion.screens.shops

import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.module

val shopsModule = module {
    factoryOf(::ShopDashboardScreenModel)
    factoryOf(::ShopWorkplaceScreenModel)
    factory { ShopOrdersScreenModel(get()) }
    factory { ShopActivitiesScreenModel(get()) }
    factoryOf(::ShopsScreenModel)
    factoryOf(::StoreDetailsScreenModel)
}