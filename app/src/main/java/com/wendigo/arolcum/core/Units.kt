package com.wendigo.arolcum.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

enum class UnitSystem {
    METRIC,
    IMPERIAL
}

object Units {
    const val CM_PER_METER = 100.0
    const val INCHES_PER_METER = 39.37007874015748
    const val FEET_PER_METER = 3.280839895013123
    const val SQ_FEET_PER_SQ_METER = 10.763910416709722

    fun formatLength(
        meters: Double,
        system: UnitSystem = UnitSystem.METRIC,
        decimals: Int = 2
    ): String {
        val value = if (meters.isFinite()) abs(meters) else 0.0
        return when (system) {
            UnitSystem.METRIC -> {
                if (value < 1.0) {
                    "${decimal(value * CM_PER_METER, 1)} cm"
                } else {
                    "${decimal(value, decimals)} m"
                }
            }

            UnitSystem.IMPERIAL -> {
                val totalInches = value * INCHES_PER_METER
                val feet = floor(totalInches / 12.0).toInt()
                val inches = totalInches - feet * 12.0
                if (feet <= 0) {
                    "${decimal(totalInches, 1)} in"
                } else {
                    "$feet ft ${decimal(inches, 1)} in"
                }
            }
        }
    }

    fun formatArea(
        squareMeters: Double,
        system: UnitSystem = UnitSystem.METRIC,
        decimals: Int = 2
    ): String {
        val value = if (squareMeters.isFinite()) abs(squareMeters) else 0.0
        return when (system) {
            UnitSystem.METRIC -> "${decimal(value, decimals)} m²"
            UnitSystem.IMPERIAL -> "${decimal(value * SQ_FEET_PER_SQ_METER, decimals)} ft²"
        }
    }

    fun decimal(value: Double, decimals: Int): String {
        val safe = value.coerceAtLeast(0.0)
        val digits = decimals.coerceIn(0, 4)
        return String.format(Locale.US, "%.${digits}f", safe).replace('.', ',')
    }
}
