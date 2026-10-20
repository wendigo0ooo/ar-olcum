package com.wendigo.arolcum

import com.wendigo.arolcum.ar.MeasureQuality
import com.wendigo.arolcum.ar.MeasureState
import com.wendigo.arolcum.ar.TrackingState
import com.wendigo.arolcum.core.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasureStateTest {
    @Test
    fun publishStoresMeasurement() {
        val state = MeasureState()
        val points = listOf(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0))

        state.publish(
            viewProjection = FloatArray(16),
            width = 1080,
            height = 1920,
            points = points,
            distanceMeters = 1.0,
            quality = MeasureQuality.HIGH,
            tracking = TrackingState.TRACKING,
            planeCount = 2,
            frozen = true
        )

        assertEquals(2, state.snapshot.points.size)
        assertEquals(1.0, state.snapshot.distanceMeters, 0.0001)
        assertEquals(MeasureQuality.HIGH, state.snapshot.quality)
        assertEquals(TrackingState.TRACKING, state.snapshot.tracking)
        assertEquals(2, state.snapshot.planeCount)
        assertTrue(state.snapshot.frozen)
        assertEquals(1080, state.viewportWidth)
        assertEquals(1920, state.viewportHeight)
        assertEquals(1080, state.frame.viewportWidth)
        assertEquals(1920, state.frame.viewportHeight)
        assertTrue(state.snapshot.hasMeasurement)
    }

    @Test
    fun clearKeepsTrackingAndPlanes() {
        val state = MeasureState()
        state.publish(
            viewProjection = FloatArray(16),
            width = 100,
            height = 200,
            points = listOf(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0)),
            distanceMeters = 1.0,
            quality = MeasureQuality.LOW,
            tracking = TrackingState.TRACKING,
            planeCount = 1
        )

        state.clear()

        assertTrue(state.snapshot.points.isEmpty())
        assertEquals(0.0, state.snapshot.distanceMeters, 0.0001)
        assertEquals(MeasureQuality.HIGH, state.snapshot.quality)
        assertEquals(TrackingState.TRACKING, state.snapshot.tracking)
        assertEquals(1, state.snapshot.planeCount)
        assertFalse(state.snapshot.frozen)
        assertEquals(100, state.viewportWidth)
        assertEquals(200, state.viewportHeight)
    }
}
