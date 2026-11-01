package com.zouitel.presentaion.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Converts legacy peso-valued UI fields to the app's integer-centavo money type
 * before formatting. PHP display is always locale-independent and has two decimals.
 */
fun formatPhpPesos(amount: Double): String {
    require(amount.isFinite()) { "PHP amount must be finite." }
    val decimalPesos = BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP)
    return Money.parsePesos(decimalPesos.toPlainString()).toPesoString()
}

/** Formats a persisted integer-centavo amount without a floating-point conversion. */
fun formatPhpCentavos(centavos: Long): String =
    Money(centavos).toPesoString()
