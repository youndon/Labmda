package com.zouitel.presentaion.model

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * A monetary amount represented in the currency's minor unit (for USD, cents).
 * Payment totals returned from the trusted backend must be used for settlement.
 */
@Serializable
data class Money(
    val minorUnits: Long = 0L,
    val currencyCode: String = "USD"
) {
    fun format(locale: Locale = Locale.getDefault()): String {
        val formatter = NumberFormat.getCurrencyInstance(locale)
        formatter.currency = Currency.getInstance(currencyCode)
        return formatter.format(BigDecimal.valueOf(minorUnits, 2))
    }

    operator fun plus(other: Money): Money {
        require(currencyCode == other.currencyCode) { "Cannot add different currencies." }
        return copy(minorUnits = Math.addExact(minorUnits, other.minorUnits))
    }

    operator fun minus(other: Money): Money {
        require(currencyCode == other.currencyCode) { "Cannot subtract different currencies." }
        return copy(minorUnits = Math.subtractExact(minorUnits, other.minorUnits))
    }

    companion object {
        fun fromDecimal(value: String, currencyCode: String = "USD"): Money {
            val minor = BigDecimal(value).movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
            return Money(minorUnits = minor, currencyCode = currencyCode)
        }
    }
}