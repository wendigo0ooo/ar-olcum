package com.wendigo.arolcum.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.Camera
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState as ArTrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.wendigo.arolcum.core.Measurement
import com.wendigo.arolcum.core.Vec3
import kotlin.math.abs
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArCoreRenderer(
    private val session: Session,
    private val state: MeasureState
) : GLSurfaceView.Renderer {
    private val backgroundRenderer = CameraBackgroundRenderer()
    private val placedPoints = mutableListOf<PlacedPoint>()
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProjection = FloatArray(16)

    @Volatile
    var displayRotation: Int = 0

    @Volatile
    var onHitMiss: ((HitFailure) -> Unit)? = null

    @Volatile
    var sessionResumed: Boolean = false

    @Volatile
    var viewportWidth = 0
        private set

    @Volatile
    var viewportHeight = 0
        private set

    private var pendingTapX = 0f
    private var pendingTapY = 0f
    private var pendingTapAt = 0L
    private var pendingTap = false

    private var frozenFrame: Frame? = null
    private var measuredDistance = 0.0
    private var measuredQuality = MeasureQuality.HIGH

    private var lastDisplayWidth = -1
    private var lastDisplayHeight = -1
    private var lastDisplayRotation = -1
    private var frameCount = 0
    private var planeCount = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        backgroundRenderer.createOnGlThread()
        session.setCameraTextureName(backgroundRenderer.textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!sessionResumed || viewportWidth <= 0 || viewportHeight <= 0) {
            pendingTap = false
            return
        }

        val frozen = frozenFrame
        val frame = if (frozen != null) {
            frozen
        } else {
            if (lastDisplayWidth != viewportWidth ||
                lastDisplayHeight != viewportHeight ||
                lastDisplayRotation != displayRotation
            ) {
                session.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
                lastDisplayWidth = viewportWidth
                lastDisplayHeight = viewportHeight
                lastDisplayRotation = displayRotation
            }

            try {
                session.update()
            } catch (exception: CameraNotAvailableException) {
                return
            }
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        backgroundRenderer.draw(frame)

        val camera = frame.camera
        camera.getProjectionMatrix(projection, 0, 0.1f, 100.0f)
        camera.getViewMatrix(view, 0)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)

        if (frozen == null) {
            if (frameCount % 30 == 0) {
                planeCount = session.getAllTrackables(Plane::class.java)
                    .count { it.trackingState == ArTrackingState.TRACKING }
            }
            frameCount += 1
        }

        val points = placedPoints.map { it.position }
        val distance = if (placedPoints.size >= 2) measuredDistance else 0.0
        val quality = if (placedPoints.size >= 2) {
            measuredQuality
        } else {
            MeasureQuality.HIGH
        }

        state.publish(
            viewProjection = viewProjection.copyOf(),
            width = viewportWidth,
            height = viewportHeight,
            points = points,
            distanceMeters = distance,
            quality = quality,
            tracking = mapTracking(camera),
            planeCount = planeCount,
            frozen = frozenFrame != null
        )

        if (pendingTap) {
            pendingTap = false
            if (frozenFrame != null ||
                SystemClock.elapsedRealtime() - pendingTapAt <= TAP_TIMEOUT_MS
            ) {
                processTap(frame, pendingTapX, pendingTapY)
            }
        }
    }

    fun handleTap(x: Float, y: Float, tapAt: Long) {
        pendingTapX = x
        pendingTapY = y
        pendingTapAt = tapAt
        pendingTap = true
    }

    private fun processTap(frame: Frame, x: Float, y: Float) {
        if (frozenFrame != null && placedPoints.size >= 2) {
            clearPlacement()
            return
        }
        if (frame.camera.trackingState != ArTrackingState.TRACKING) {
            onHitMiss?.invoke(HitFailure.NOT_TRACKING)
            return
        }

        val hits = try {
            frame.hitTest(x, y)
        } catch (exception: Exception) {
            onHitMiss?.invoke(HitFailure.NO_HIT)
            return
        }
        logHits(hits)
        val candidate = selectCandidate(hits)
        if (candidate == null) {
            val trackingPlanes = session.getAllTrackables(Plane::class.java)
                .count { it.trackingState == ArTrackingState.TRACKING }
            onHitMiss?.invoke(failureFor(hits, trackingPlanes))
            return
        }

        val hit = hits[candidate.index]
        val trackable = hit.trackable
        val inPolygon = trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)
        Log.d(
            TAG,
            "selected=${candidate.kind} distance=${hit.distance} polygon=$inPolygon"
        )

        val position = hitPoint(hit)
        val point = PlacedPoint(
            position = position,
            quality = if (candidate.kind == HitKind.PLANE && inPolygon) {
                MeasureQuality.HIGH
            } else {
                MeasureQuality.LOW
            },
            kind = candidate.kind,
            planeId = candidate.planeId,
            hitDistance = hit.distance
        )

        if (placedPoints.size >= 2) {
            clearPlacement()
        }
        if (!acceptPoint(point)) return
        if (frozenFrame == null && placedPoints.size == 1 && point.kind != HitKind.PLANE) {
            frozenFrame = frame
            Log.d(TAG, "frame frozen for stable second point")
        }
        Log.d(
            TAG,
            "placed=${placedPoints.size} point=${position.x},${position.y},${position.z}"
        )
    }

    private fun selectCandidate(hits: List<HitResult>): HitCandidate? {
        val candidates = ArrayList<HitCandidate>(hits.size)
        hits.forEachIndexed { index, hit ->
            val trackable = hit.trackable
            if (trackable.trackingState != ArTrackingState.TRACKING) {
                return@forEachIndexed
            }
            if (!isDistanceUsable(hit)) {
                return@forEachIndexed
            }
            val kind = when (trackable) {
                is Plane -> HitKind.PLANE
                is DepthPoint -> HitKind.DEPTH
                is Point -> HitKind.POINT
                else -> return@forEachIndexed
            }
            if (kind == HitKind.PLANE &&
                trackable is Plane &&
                !trackable.isPoseInPolygon(hit.hitPose)
            ) {
                return@forEachIndexed
            }
            candidates.add(
                HitCandidate(
                    index = index,
                    kind = kind,
                    distance = hit.distance,
                    planeId = if (kind == HitKind.PLANE) System.identityHashCode(trackable) else 0
                )
            )
        }
        return HitSelector.choose(candidates)
    }

    private fun acceptPoint(point: PlacedPoint): Boolean {
        val first = placedPoints.firstOrNull()
        if (first != null) {
            val firstIsPlane = first.kind == HitKind.PLANE
            val nextIsPlane = point.kind == HitKind.PLANE
            if (firstIsPlane != nextIsPlane &&
                abs(point.hitDistance - first.hitDistance) > MIXED_SURFACE_MAX_GAP_M
            ) {
                onHitMiss?.invoke(HitFailure.SURFACE_MISMATCH)
                return false
            }
        }

        placedPoints.add(point)
        if (placedPoints.size >= 2) {
            freezeMeasurement()
        }
        return true
    }

    private fun freezeMeasurement() {
        if (placedPoints.size < 2) return

        val first = placedPoints[0]
        val second = placedPoints[1]
        val distance = Measurement.distance(first.position, second.position)
        val differentPlanes =
            first.kind == HitKind.PLANE &&
                second.kind == HitKind.PLANE &&
                first.planeId != second.planeId

        measuredDistance = distance
        measuredQuality = when {
            !distance.isFinite() ||
                distance < MIN_MEASUREMENT_M ||
                distance > MAX_MEASUREMENT_M -> MeasureQuality.UNRELIABLE

            first.quality == MeasureQuality.LOW ||
                second.quality == MeasureQuality.LOW ||
                differentPlanes -> MeasureQuality.LOW

            else -> MeasureQuality.HIGH
        }
        Log.d(
            TAG,
            "measurement=${measuredDistance}m quality=$measuredQuality " +
                "hits=${first.hitDistance}/${second.hitDistance}"
        )
    }

    private fun clearPlacement() {
        placedPoints.clear()
        measuredDistance = 0.0
        measuredQuality = MeasureQuality.HIGH
        frozenFrame = null
        state.clear()
    }

    private fun failureFor(hits: List<HitResult>, trackingPlanes: Int): HitFailure {
        if (hits.isEmpty() && trackingPlanes == 0) return HitFailure.NO_PLANE

        val outsidePlane = hits.any { result ->
            val trackable = result.trackable
            trackable is Plane &&
                trackable.trackingState == ArTrackingState.TRACKING &&
                trackable.isPoseInExtents(result.hitPose) &&
                !trackable.isPoseInPolygon(result.hitPose)
        }
        if (outsidePlane) return HitFailure.OUTSIDE_PLANE

        if (hits.isNotEmpty() && hits.none { isSurfaceCandidate(it) }) {
            return HitFailure.OUT_OF_RANGE
        }

        return HitFailure.NO_HIT
    }

    private fun isSurfaceCandidate(result: HitResult): Boolean {
        val trackable = result.trackable
        if (trackable.trackingState != ArTrackingState.TRACKING) return false
        if (!isDistanceUsable(result)) return false
        return when (trackable) {
            is Plane -> trackable.isPoseInPolygon(result.hitPose)
            is DepthPoint, is Point -> true
            else -> false
        }
    }

    private fun isDistanceUsable(result: HitResult): Boolean {
        val distance = result.distance
        if (!distance.isFinite()) return false
        return if (result.trackable is DepthPoint) {
            distance in MIN_DEPTH_DISTANCE_M..MAX_DEPTH_DISTANCE_M
        } else {
            distance in MIN_HIT_DISTANCE_M..MAX_HIT_DISTANCE_M
        }
    }

    private fun hitPoint(hit: HitResult): Vec3 {
        val translation = hit.hitPose.translation
        return Vec3(
            translation[0].toDouble(),
            translation[1].toDouble(),
            translation[2].toDouble()
        )
    }

    private fun logHits(hits: List<HitResult>) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        val summary = hits.map { hit ->
            val trackable = hit.trackable
            val kind = when (trackable) {
                is Plane -> "plane"
                is DepthPoint -> "depth"
                is Point -> "point"
                else -> trackable.javaClass.simpleName
            }
            val polygon = trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)
            "$kind@${"%.3f".format(hit.distance)}m(polygon=$polygon)"
        }
        Log.d(TAG, "hits=$summary")
    }

    fun reset() {
        clearPlacement()
    }

    fun pause() {
        pendingTap = false
        frozenFrame = null
        lastDisplayWidth = -1
        lastDisplayHeight = -1
        lastDisplayRotation = -1
    }

    fun release() {
        backgroundRenderer.release()
    }

    private fun mapTracking(camera: Camera): TrackingState {
        return when (camera.trackingState) {
            ArTrackingState.TRACKING -> TrackingState.TRACKING
            ArTrackingState.PAUSED -> when (camera.trackingFailureReason) {
                TrackingFailureReason.INSUFFICIENT_LIGHT -> TrackingState.INSUFFICIENT_LIGHT
                TrackingFailureReason.INSUFFICIENT_FEATURES -> TrackingState.INSUFFICIENT_FEATURES
                TrackingFailureReason.EXCESSIVE_MOTION -> TrackingState.EXCESSIVE_MOTION
                TrackingFailureReason.CAMERA_UNAVAILABLE -> TrackingState.PAUSED
                else -> TrackingState.RELOCALIZING
            }
            else -> TrackingState.PAUSED
        }
    }

    private data class PlacedPoint(
        val position: Vec3,
        val quality: MeasureQuality,
        val kind: HitKind,
        val planeId: Int,
        val hitDistance: Float
    )

    private companion object {
        const val TAG = "ArMeasure"
        const val MIN_HIT_DISTANCE_M = 0.1f
        const val MAX_HIT_DISTANCE_M = 12.0f
        const val MIN_DEPTH_DISTANCE_M = 0.1f
        const val MAX_DEPTH_DISTANCE_M = 8.0f
        const val MIN_MEASUREMENT_M = 0.005
        const val MAX_MEASUREMENT_M = 12.0
        const val MIXED_SURFACE_MAX_GAP_M = 0.30f
        const val TAP_TIMEOUT_MS = 500L
    }
}
