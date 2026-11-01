package com.zouitel.presentaion.screens.food

import com.zouitel.presentaion.data.repository.OrdersRepository
import org.koin.dsl.module
import org.koin.core.module.dsl.factoryOf

val foodModule = module {
    factoryOf(::RestaurantsScreenModel)
    factoryOf(::MealWorkplaceScreenModel)
    factoryOf(::RestaurantWorkplaceScreenModel)
    factory { FoodOrdersScreenModel(get()) }

    single { FoodRepository() }
}