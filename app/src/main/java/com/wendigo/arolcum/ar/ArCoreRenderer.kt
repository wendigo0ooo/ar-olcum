package com.wendigo.arolcum.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.Anchor
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
    private val placements = mutableListOf<PlacedPoint>()
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

        if (lastDisplayWidth != viewportWidth ||
            lastDisplayHeight != viewportHeight ||
            lastDisplayRotation != displayRotation
        ) {
            session.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
            lastDisplayWidth = viewportWidth
            lastDisplayHeight = viewportHeight
            lastDisplayRotation = displayRotation
        }

        val frame = try {
            session.update()
        } catch (exception: CameraNotAvailableException) {
            return
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        backgroundRenderer.draw(frame)

        val camera = frame.camera
        camera.getProjectionMatrix(projection, 0, 0.1f, 100.0f)
        camera.getViewMatrix(view, 0)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)

        if (frameCount % 30 == 0) {
            planeCount = session.getAllTrackables(Plane::class.java)
                .count { it.trackingState == ArTrackingState.TRACKING }
        }
        frameCount += 1

        if (pendingTap) {
            pendingTap = false
            if (SystemClock.elapsedRealtime() - pendingTapAt <= TAP_TIMEOUT_MS) {
                processTap(frame, pendingTapX, pendingTapY)
            }
        }

        var anchorUnstable = false
        var anchorRemoved = false
        val points = ArrayList<Vec3>(placements.size)
        val iterator = placements.iterator()
        while (iterator.hasNext()) {
            val placement = iterator.next()
            when (placement.anchor.trackingState) {
                ArTrackingState.TRACKING -> {
                    points.add(anchorPosition(placement.anchor))
                }

                ArTrackingState.STOPPED -> {
                    placement.anchor.detach()
                    iterator.remove()
                    anchorUnstable = true
                    anchorRemoved = true
                }

                else -> {
                    anchorUnstable = true
                    points.add(anchorPosition(placement.anchor))
                }
            }
        }
        if (anchorRemoved) {
            onHitMiss?.invoke(HitFailure.ANCHOR_LOST)
        }

        val distance = if (points.size >= 2) {
            Measurement.distance(points[0], points[1])
        } else {
            0.0
        }
        val quality = if (points.size >= 2) {
            qualityFor(placements, distance, anchorUnstable)
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
            frozen = points.size >= 2
        )
    }

    fun handleTap(x: Float, y: Float, tapAt: Long) {
        pendingTapX = x
        pendingTapY = y
        pendingTapAt = tapAt
        pendingTap = true
    }

    private fun processTap(frame: Frame, x: Float, y: Float) {
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

        if (placements.size >= 2) {
            clearPlacement()
        }

        val first = placements.firstOrNull()
        if (first != null) {
            val firstIsPlane = first.kind == HitKind.PLANE
            val nextIsPlane = candidate.kind == HitKind.PLANE
            if (firstIsPlane != nextIsPlane &&
                abs(hit.distance - first.hitDistance) > MIXED_SURFACE_MAX_GAP_M
            ) {
                onHitMiss?.invoke(HitFailure.SURFACE_MISMATCH)
                return
            }
        }

        val anchor = try {
            hit.createAnchor()
        } catch (exception: Exception) {
            onHitMiss?.invoke(HitFailure.NO_HIT)
            return
        }

        placements.add(
            PlacedPoint(
                anchor = anchor,
                quality = if (candidate.kind == HitKind.PLANE && inPolygon) {
                    MeasureQuality.HIGH
                } else {
                    MeasureQuality.LOW
                },
                kind = candidate.kind,
                planeId = candidate.planeId,
                hitDistance = hit.distance
            )
        )
        val position = anchorPosition(anchor)
        Log.d(
            TAG,
            "placed=${placements.size} point=${position.x},${position.y},${position.z}"
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

    private fun qualityFor(
        placements: List<PlacedPoint>,
        distance: Double,
        anchorUnstable: Boolean
    ): MeasureQuality {
        if (!distance.isFinite() ||
            distance < MIN_MEASUREMENT_M ||
            distance > MAX_MEASUREMENT_M
        ) {
            return MeasureQuality.UNRELIABLE
        }
        if (anchorUnstable) return MeasureQuality.LOW

        val differentPlanes =
            placements.size >= 2 &&
                placements[0].kind == HitKind.PLANE &&
                placements[1].kind == HitKind.PLANE &&
                placements[0].planeId != placements[1].planeId
        return if (placements.any { it.quality == MeasureQuality.LOW } || differentPlanes) {
            MeasureQuality.LOW
        } else {
            MeasureQuality.HIGH
        }
    }

    private fun clearPlacement() {
        placements.forEach { it.anchor.detach() }
        placements.clear()
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

    private fun anchorPosition(anchor: Anchor): Vec3 {
        val translation = anchor.pose.translation
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
        lastDisplayWidth = -1
        lastDisplayHeight = -1
        lastDisplayRotation = -1
    }

    fun release() {
        clearPlacement()
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
        val anchor: Anchor,
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
