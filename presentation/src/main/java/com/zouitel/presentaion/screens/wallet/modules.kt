package com.zouitel.presentaion.screens.wallet

import org.koin.dsl.module

val walletModule=module {
    single { WalletRepository() }
    factory { WalletScreenModel(get()) }
    factory { PayoutAccountScreenModel(get()) }
    factory { AdminPaymentsScreenModel(get()) }
}
