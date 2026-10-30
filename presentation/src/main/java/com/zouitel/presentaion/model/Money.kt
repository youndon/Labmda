package com.zouitel.presentaion.model

import kotlinx.serialization.Serializable

/**
 * An amount of Philippine currency stored as integer centavos.
 *
 * One Philippine peso is exactly 100 centavos. Keep money in this type while
 * calculating or transferring amounts; do not use floating-point values.
 */
@Serializable
@JvmInline
value class Money(val centavos: Long) : Comparable<Money> {

    operator fun plus(other: Money): Money =
        Money(Math.addExact(centavos, other.centavos))

    operator fun minus(other: Money): Money =
        Money(Math.subtractExact(centavos, other.centavos))

    operator fun unaryMinus(): Money =
        Money(Math.negateExact(centavos))

    operator fun times(multiplier: Int): Money =
        Money(Math.multiplyExact(centavos, multiplier.toLong()))

    operator fun times(multiplier: Long): Money =
        Money(Math.multiplyExact(centavos, multiplier))

    override fun compareTo(other: Money): Int =
        centavos.compareTo(other.centavos)

    val isZero: Boolean
        get() = centavos == 0L

    /**
     * Formats the amount as a decimal peso value without converting through Double.
     * Example: 12345 centavos becomes "123.45".
     */
    fun toPesoString(): String {
        val absolutePesos = kotlin.math.abs(centavos / CENTAVOS_PER_PESO)
        val absoluteCentavos = kotlin.math.abs(centavos % CENTAVOS_PER_PESO)
        val sign = if (centavos < 0L) "-" else ""
        return sign + absolutePesos.toString() + "." +
            absoluteCentavos.toString().padStart(2, '0')
    }

    override fun toString(): String = "₱" + toPesoString()

    companion object {
        private const val CENTAVOS_PER_PESO = 100L
        private val PESO_AMOUNT_PATTERN =
            Regex("([+-]?)(?:(\\d+)|(\\d{1,3}(?:,\\d{3})+))(?:\\.(\\d{1,2}))?")

        val ZERO: Money = Money(0L)

        /** Creates an amount from a whole number of Philippine pesos. */
        fun fromPesos(pesos: Long): Money =
            Money(Math.multiplyExact(pesos, CENTAVOS_PER_PESO))

        /**
         * Parses a peso amount exactly, accepting up to two decimal places.
         * Optional thousands separators and the Philippine peso symbol are supported.
         * Examples: "125", "125.5", "1,250.75", and "₱1,250.75".
         */
        fun parsePesos(value: String): Money {
            val normalized = value.trim().removePrefix("₱").trim()
            val match = PESO_AMOUNT_PATTERN.matchEntire(normalized)
                ?: throw IllegalArgumentException("Invalid PHP amount: " + value)

            val wholePart = match.groupValues[2]
                .ifEmpty { match.groupValues[3] }
                .replace(",", "")
            val wholePesos = wholePart.toLongOrNull()
                ?: throw ArithmeticException("PHP amount is out of range.")

            val fractionalCentavos = match.groupValues[4]
                .padEnd(2, '0')
                .ifEmpty { "0" }
                .toLong()

            val absoluteCentavos = Math.addExact(
                Math.multiplyExact(wholePesos, CENTAVOS_PER_PESO),
                fractionalCentavos
            )
            val signedCentavos = if (match.groupValues[1] == "-") {
                Math.negateExact(absoluteCentavos)
            } else {
                absoluteCentavos
            }
            return Money(signedCentavos)
        }
    }
}
