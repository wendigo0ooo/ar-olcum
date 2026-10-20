package com.wendigo.arolcum

import com.wendigo.arolcum.core.Measurement
import com.wendigo.arolcum.core.Vec3
import org.junit.Assert.assertEquals
import org.junit.Test

class MeasurementTest {
    @Test
    fun distanceUsesThreeDimensions() {
        val result = Measurement.distance(
            Vec3(0.0, 0.0, 0.0),
            Vec3(3.0, 4.0, 0.0)
        )
        assertEquals(5.0, result, 1e-9)
    }

    @Test
    fun distanceIncludesHeight() {
        val result = Measurement.distance(
            Vec3(1.0, 1.0, 1.0),
            Vec3(2.0, 3.0, 3.0)
        )
        assertEquals(3.0, result, 1e-9)
    }

    @Test
    fun chainLengthSumsSegments() {
        val points = listOf(
            Vec3(0.0, 0.0, 0.0),
            Vec3(3.0, 0.0, 0.0),
            Vec3(3.0, 4.0, 0.0),
            Vec3(3.0, 4.0, 12.0)
        )
        assertEquals(19.0, Measurement.chainLength(points), 1e-9)
    }

    @Test
    fun chainNeedsAtLeastTwoPoints() {
        assertEquals(0.0, Measurement.chainLength(emptyList()), 0.0)
        assertEquals(0.0, Measurement.chainLength(listOf(Vec3(1.0, 2.0, 3.0))), 0.0)
    }

    @Test
    fun squareArea() {
        val points = listOf(
            Vec3(0.0, 0.0, 0.0),
            Vec3(2.0, 0.0, 0.0),
            Vec3(2.0, 0.0, 2.0),
            Vec3(0.0, 0.0, 2.0)
        )
        assertEquals(4.0, Measurement.polygonArea(points), 1e-9)
    }

    @Test
    fun triangleArea() {
        val points = listOf(
            Vec3(0.0, 0.0, 0.0),
            Vec3(4.0, 0.0, 0.0),
            Vec3(0.0, 0.0, 3.0)
        )
        assertEquals(6.0, Measurement.polygonArea(points), 1e-9)
    }

    @Test
    fun areaNeedsThreePoints() {
        val points = listOf(
            Vec3(0.0, 0.0, 0.0),
            Vec3(1.0, 0.0, 0.0)
        )
        assertEquals(0.0, Measurement.polygonArea(points), 0.0)
    }

    @Test
    fun heightIsVerticalDelta() {
        assertEquals(
            1.75,
            Measurement.height(Vec3(0.0, 0.2, 0.0), Vec3(0.0, 1.95, 0.0)),
            1e-9
        )
    }

    @Test
    fun nonFiniteValuesAreRejected() {
        assertEquals(
            0.0,
            Measurement.distance(Vec3(Double.NaN, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
            0.0
        )
        assertEquals(
            0.0,
            Measurement.polygonArea(
                listOf(
                    Vec3(Double.POSITIVE_INFINITY, 0.0, 0.0),
                    Vec3(1.0, 0.0, 0.0),
                    Vec3(1.0, 0.0, 1.0)
                )
            ),
            0.0
        )
    }
}
