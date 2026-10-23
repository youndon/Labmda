package com.zouitel.presentaion.screens.payment

import com.zouitel.presentaion.screens.wallet.CourierWalletScreenModel
import com.zouitel.presentaion.screens.wallet.MerchantEarningsScreenModel
import com.zouitel.presentaion.screens.wallet.PayoutAccountScreenModel
import com.zouitel.presentaion.admin.AdminPaymentsScreenModel
import com.zouitel.presentaion.admin.AdminCommissionScreenModel
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.module

val paymentModule = module {
    factoryOf(::PaymentRepository)
    factoryOf(::PaymentScreenModel)
    factoryOf(::StripePaymentLauncher)
    factoryOf(::PayPalPaymentLauncher)
    factoryOf(::PayPalReturnHandler)
    factoryOf(::MerchantEarningsScreenModel)
    factoryOf(::CourierWalletScreenModel)
    factoryOf(::PayoutAccountScreenModel)
    factoryOf(::AdminPaymentsScreenModel)
    factoryOf(::AdminCommissionScreenModel)
}