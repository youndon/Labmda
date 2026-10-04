package com.zouitel.presentaion.di

import com.zouitel.presentaion.screens.account.AccountScreenModel
import com.zouitel.presentaion.screens.account.LogInScreenModel
import com.zouitel.presentaion.screens.food.foodModule
import com.zouitel.presentaion.screens.ride.driver.DriverScreenModel
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.module

val presentationModule = module {
    includes(foodModule)

    factory {
        AccountScreenModel(androidContext(), get(), get())
    }
    factoryOf(::LogInScreenModel)
    factoryOf(::DriverScreenModel)

    single {
        Firebase.auth
    }

    single {
        Firebase.database.reference()
    }
}