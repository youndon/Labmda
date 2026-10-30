package com.zouitel.presentaion.payments

import org.koin.dsl.module

val paymentModule = module {
    single { PaymentRepository() }
    factory { PaymentScreenModel(get()) }
}
