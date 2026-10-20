package com.wendigo.arolcum

import com.wendigo.arolcum.ar.ArHit
import com.wendigo.arolcum.ar.FakeArEngine
import com.wendigo.arolcum.ar.TrackingState
import com.wendigo.arolcum.core.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeArEngineTest {
    @Test
    fun lifecycleControlsRunningState() {
        val engine = FakeArEngine()

        assertFalse(engine.isRunning)
        assertEquals(TrackingState.PAUSED, engine.trackingState())

        assertTrue(engine.start())
        assertTrue(engine.isRunning)
        assertEquals(TrackingState.TRACKING, engine.trackingState())

        engine.pause()
        assertFalse(engine.isRunning)

        engine.resume()
        assertTrue(engine.isRunning)

        engine.stop()
        assertFalse(engine.isRunning)
    }

    @Test
    fun hitTestReturnsConfiguredPoint() {
        val expected = ArHit(Vec3(1.0, 2.0, 3.0), onPlane = true)
        val engine = FakeArEngine(listOf(expected))

        engine.start()
        val actual = engine.hitTest(10f, 20f)

        assertEquals(expected, actual)
        assertEquals(1, engine.hitCount)
    }

    @Test
    fun emptyHitListReturnsNull() {
        val engine = FakeArEngine()
        engine.start()

        assertNull(engine.hitTest(0f, 0f))
        assertEquals(1, engine.hitCount)
    }
}
