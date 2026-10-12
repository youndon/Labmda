package com.zouitel.presentaion.screens.freight

import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.module

val freightModule = module {
    factoryOf(::FreightWorkplaceScreenModel)
    factoryOf(::FreightScreenModel)
}