package com.zouitel.presentaion.screens.food

import org.koin.dsl.module
import org.koin.core.module.dsl.factoryOf

val foodModule = module {
    factoryOf(::MainFoodScreenModel)
    factoryOf(::MealWorkplaceScreenModel)
    factory { (restaurantId: String) -> RestaurantDetailScreenModel(restaurantId) }
    factoryOf(::RestaurantWorkplaceScreenModel)
    factoryOf(::DashboardScreenModel)

    single {
        FoodRepository()
    }
}