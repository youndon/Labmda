package com.zouitel.presentaion.screens.shops

import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.module

val shopsModule = module {
    factoryOf(::ShopDashboardScreenModel)
    factoryOf(::ShopWorkplaceScreenModel)
    factoryOf(::ShopOrdersScreenModel)
    factoryOf(::ShopActivitiesScreenModel)
    factoryOf(::ShopsScreenModel)
    factoryOf(::StoreDetailsScreenModel)
}