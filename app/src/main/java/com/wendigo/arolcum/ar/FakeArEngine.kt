package com.wendigo.arolcum.ar

class FakeArEngine(
    private val hits: List<ArHit> = emptyList()
) : ArEngine {
    private var started = false
    private var paused = false

    var hitCount = 0
        private set

    override val isRunning: Boolean
        get() = started && !paused

    override fun start(): Boolean {
        started = true
        paused = false
        return true
    }

    override fun pause() {
        paused = true
    }

    override fun resume() {
        if (started) paused = false
    }

    override fun stop() {
        started = false
        paused = false
    }

    override fun hitTest(screenX: Float, screenY: Float): ArHit? {
        hitCount += 1
        return hits.firstOrNull()
    }

    override fun trackingState(): TrackingState {
        return if (isRunning) TrackingState.TRACKING else TrackingState.PAUSED
    }
}
