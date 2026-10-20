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
    val planeCount: Int = 0,
    val frozen: Boolean = false
) {
    val hasMeasurement: Boolean
        get() = points.size >= 2
}

data class MeasureFrame(
    val viewProjection: FloatArray = FloatArray(16),
    val viewportWidth: Int = 0,
    val viewportHeight: Int = 0,
    val snapshot: MeasureSnapshot = MeasureSnapshot()
)

class MeasureState {
    @Volatile
    var frame: MeasureFrame = MeasureFrame()
        private set

    val snapshot: MeasureSnapshot
        get() = frame.snapshot

    val viewProjection: FloatArray
        get() = frame.viewProjection

    val viewportWidth: Int
        get() = frame.viewportWidth

    val viewportHeight: Int
        get() = frame.viewportHeight

    fun publish(
        viewProjection: FloatArray,
        width: Int,
        height: Int,
        points: List<Vec3>,
        distanceMeters: Double,
        quality: MeasureQuality,
        tracking: TrackingState,
        planeCount: Int,
        frozen: Boolean = false
    ) {
        val snapshot = MeasureSnapshot(
            points = points.toList(),
            distanceMeters = distanceMeters,
            quality = quality,
            tracking = tracking,
            planeCount = planeCount,
            frozen = frozen
        )
        frame = MeasureFrame(
            viewProjection = viewProjection.copyOf(),
            viewportWidth = width,
            viewportHeight = height,
            snapshot = snapshot
        )
    }

    fun clear() {
        val current = frame
        val snapshot = current.snapshot.copy(
            points = emptyList(),
            distanceMeters = 0.0,
            quality = MeasureQuality.HIGH,
            frozen = false
        )
        frame = current.copy(snapshot = snapshot)
    }
}
