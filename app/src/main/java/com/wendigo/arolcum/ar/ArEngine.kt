package com.wendigo.arolcum.ar

import com.wendigo.arolcum.core.Vec3

enum class TrackingState {
    INITIALIZING,
    TRACKING,
    INSUFFICIENT_LIGHT,
    INSUFFICIENT_FEATURES,
    EXCESSIVE_MOTION,
    RELOCALIZING,
    PAUSED
}

data class ArHit(val position: Vec3, val onPlane: Boolean)

interface ArEngine {
    val isRunning: Boolean

    fun start(): Boolean

    fun pause()

    fun resume()

    fun stop()

    fun hitTest(screenX: Float, screenY: Float): ArHit?

    fun trackingState(): TrackingState
}
