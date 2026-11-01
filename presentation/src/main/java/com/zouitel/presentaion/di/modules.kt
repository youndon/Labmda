package com.zouitel.presentaion.di

import com.zouitel.presentaion.data.repository.OrdersRepository
import com.zouitel.presentaion.data.repository.TripsRepository
import com.zouitel.presentaion.data.repository.UsersRepository
import com.zouitel.presentaion.screens.account.AccountScreenModel
import com.zouitel.presentaion.screens.account.AccountPreferencesStore
import com.zouitel.presentaion.screens.account.AccountPreferencesScreenModel
import com.zouitel.presentaion.screens.account.LogInScreenModel
import com.zouitel.presentaion.screens.delivery.deliveryModule
import com.zouitel.presentaion.screens.food.foodModule
import com.zouitel.presentaion.payments.paymentModule
import com.zouitel.presentaion.screens.ride.rideModule
import com.zouitel.presentaion.screens.shops.shopsModule
import com.zouitel.presentaion.screens.wallet.walletModule
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.database.database
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.module

val presentationModule = module {
    includes(foodModule, shopsModule, rideModule, deliveryModule, paymentModule, walletModule)

    single { AccountPreferencesStore(androidContext()) }
    factoryOf(::AccountPreferencesScreenModel)
    factory { AccountScreenModel(androidContext(), get(), get()) }
    factoryOf(::LogInScreenModel)

    single { Firebase.auth }
    single { Firebase.database.reference() }

    single { OrdersRepository(get()) }
    single { TripsRepository(get()) }
    single { UsersRepository(get()) }
}