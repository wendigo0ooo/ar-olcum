package com.wendigo.arolcum

import com.wendigo.arolcum.core.UnitSystem
import com.wendigo.arolcum.core.Units
import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {
    @Test
    fun metricUsesCentimetersBelowOneMeter() {
        assertEquals("42,0 cm", Units.formatLength(0.42))
        assertEquals("0,0 cm", Units.formatLength(0.0))
        assertEquals("99,9 cm", Units.formatLength(0.999))
    }

    @Test
    fun metricUsesMetersAtOneMeterAndAbove() {
        assertEquals("1,00 m", Units.formatLength(1.0))
        assertEquals("1,23 m", Units.formatLength(1.234))
        assertEquals("1,2 m", Units.formatLength(1.234, decimals = 1))
    }

    @Test
    fun imperialUsesFeetAndInches() {
        assertEquals("3 ft 3,4 in", Units.formatLength(1.0, UnitSystem.IMPERIAL))
        assertEquals("11,8 in", Units.formatLength(0.3, UnitSystem.IMPERIAL))
    }

    @Test
    fun areaFormatsMetricAndImperial() {
        assertEquals("4,00 m²", Units.formatArea(4.0))
        assertEquals("107,64 ft²", Units.formatArea(10.0, UnitSystem.IMPERIAL))
    }

    @Test
    fun negativeValuesAreMadePositive() {
        assertEquals("1,00 m", Units.formatLength(-1.0))
        assertEquals("4,00 m²", Units.formatArea(-4.0))
    }

    @Test
    fun decimalUsesTurkishSeparator() {
        assertEquals("1,234 m", "${Units.decimal(1.234, 3)} m")
        assertEquals("1,5", Units.decimal(1.5, 1))
    }

    @Test
    fun decimalsAreClamped() {
        assertEquals("2", Units.decimal(2.0, -3))
        assertEquals("2,0000", Units.decimal(2.0, 9))
    }
}
