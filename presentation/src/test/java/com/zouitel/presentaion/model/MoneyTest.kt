package com.zouitel.presentaion.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MoneyTest {
    @Test
    fun decimalParsingUsesMinorUnits() {
        assertEquals(Money(minorUnits = 1234, currencyCode = "USD"), Money.fromDecimal("12.34"))
        assertEquals(Money(minorUnits = 10, currencyCode = "USD"), Money.fromDecimal("0.10"))
        assertEquals(Money(minorUnits = 100, currencyCode = "DZD"), Money.fromDecimal("1.00", "DZD"))
    }

    @Test
    fun additionRejectsDifferentCurrencies() {
        assertThrows(IllegalArgumentException::class.java) {
            Money(100, "USD") + Money(100, "DZD")
        }
    }

    @Test
    fun arithmeticStaysInMinorUnits() {
        assertEquals(Money(275, "USD"), Money(300, "USD") - Money(25, "USD"))
        assertEquals(Money(325, "USD"), Money(300, "USD") + Money(25, "USD"))
    }
}