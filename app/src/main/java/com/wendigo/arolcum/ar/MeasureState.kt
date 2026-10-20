package com.wendigo.arolcum.ar

import com.wendigo.arolcum.core.Vec3

enum class MeasureQuality {
    HIGH,
    LOW,
    UNRELIABLE
}

data class MeasureSnapshot(
    val points: List<Vec3> = emptyList(),
    val distanceMeters: Double = 0.0,
    val quality: MeasureQuality = MeasureQuality.HIGH,
    val tracking: TrackingState = TrackingState.INITIALIZING,
    val planeCount: Int = 0
) {
    val hasMeasurement: Boolean
        get() = points.size >= 2
}

class MeasureState {
    @Volatile
    var snapshot: MeasureSnapshot = MeasureSnapshot()
        private set

    @Volatile
    var viewProjection: FloatArray = FloatArray(16)
        private set

    @Volatile
    var viewportWidth: Int = 0
        private set

    @Volatile
    var viewportHeight: Int = 0
        private set

    fun publish(
        viewProjection: FloatArray,
        width: Int,
        height: Int,
        points: List<Vec3>,
        distanceMeters: Double,
        quality: MeasureQuality,
        tracking: TrackingState,
        planeCount: Int
    ) {
        this.viewProjection = viewProjection
        viewportWidth = width
        viewportHeight = height
        snapshot = MeasureSnapshot(points, distanceMeters, quality, tracking, planeCount)
    }

    fun clear() {
        val current = snapshot
        snapshot = current.copy(
            points = emptyList(),
            distanceMeters = 0.0,
            quality = MeasureQuality.HIGH
        )
    }
}
