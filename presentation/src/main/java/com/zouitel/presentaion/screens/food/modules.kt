package com.zouitel.presentaion.screens.food

import org.koin.dsl.module
import org.koin.core.module.dsl.factoryOf

val foodModule = module {
    factoryOf(::RestaurantsScreenModel)
    factoryOf(::MealWorkplaceScreenModel)
    factoryOf(::RestaurantWorkplaceScreenModel)
    factoryOf(::FoodOrdersScreenModel)

    single {
        FoodRepository()
    }
}