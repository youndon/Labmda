package com.zouitel.presentaion.screens.payment

import org.koin.dsl.module

val paymentModule = module {
    single { PaymentRepository() }
}
