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
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArCoreRenderer(
    private val session: Session,
    private val state: MeasureState
) : GLSurfaceView.Renderer {
    private val backgroundRenderer = CameraBackgroundRenderer()
    private val anchors = mutableListOf<Anchor>()
    private val pointQualities = mutableListOf<MeasureQuality>()
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

    @Volatile
    private var pendingTap = false

    private var frozenPoints: List<Vec3>? = null
    private var frozenDistance = 0.0
    private var frozenQuality = MeasureQuality.HIGH

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

        val points: List<Vec3>
        val distance: Double
        val quality: MeasureQuality

        val frozen = frozenPoints
        if (frozen != null) {
            points = frozen
            distance = frozenDistance
            quality = frozenQuality
        } else {
            val activePoints = ArrayList<Vec3>(anchors.size)
            var index = 0
            while (index < anchors.size) {
                val anchor = anchors[index]
                if (anchor.trackingState == ArTrackingState.STOPPED) {
                    anchor.detach()
                    anchors.removeAt(index)
                    if (index < pointQualities.size) pointQualities.removeAt(index)
                    continue
                }
                activePoints.add(anchorPoint(anchor))
                index += 1
            }
            points = activePoints
            distance = 0.0
            quality = MeasureQuality.HIGH
        }

        state.publish(
            viewProjection = viewProjection.copyOf(),
            width = viewportWidth,
            height = viewportHeight,
            points = points,
            distanceMeters = distance,
            quality = quality,
            tracking = mapTracking(camera),
            planeCount = planeCount
        )

        if (pendingTap) {
            pendingTap = false
            if (SystemClock.elapsedRealtime() - pendingTapAt <= TAP_TIMEOUT_MS) {
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
        val hit = selectHit(hits)
        if (hit == null) {
            val trackingPlanes = session.getAllTrackables(Plane::class.java)
                .count { it.trackingState == ArTrackingState.TRACKING }
            onHitMiss?.invoke(failureFor(hits, trackingPlanes))
            return
        }

        val trackable = hit.trackable
        val inPolygon = trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)
        Log.d(TAG, "hit=${trackable.javaClass.simpleName} distance=${hit.distance} polygon=$inPolygon")

        val anchor = try {
            hit.createAnchor()
        } catch (exception: Exception) {
            onHitMiss?.invoke(HitFailure.NO_HIT)
            return
        }

        clearCompletedMeasurement()
        anchors.add(anchor)
        pointQualities.add(hitQuality(hit))
        if (anchors.size >= 2) {
            freezeMeasurement()
        }
    }

    private fun selectHit(hits: List<HitResult>): HitResult? {
        val planeHit = hits.firstOrNull { result ->
            val trackable = result.trackable
            trackable is Plane &&
                trackable.trackingState == ArTrackingState.TRACKING &&
                isDistanceUsable(result) &&
                trackable.isPoseInPolygon(result.hitPose)
        }
        if (planeHit != null) return planeHit

        val hasPlaneHit = hits.any { result ->
            val trackable = result.trackable
            trackable is Plane && trackable.trackingState == ArTrackingState.TRACKING
        }
        if (hasPlaneHit) return null

        val depthHit = hits.firstOrNull { result ->
            val trackable = result.trackable
            trackable is DepthPoint &&
                trackable.trackingState == ArTrackingState.TRACKING &&
                isDistanceUsable(result)
        }
        if (depthHit != null) return depthHit

        return hits.firstOrNull { result ->
            val trackable = result.trackable
            trackable is Point &&
                trackable.trackingState == ArTrackingState.TRACKING &&
                isDistanceUsable(result)
        }
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

        if (hits.isNotEmpty() && hits.none { isDistanceUsable(it) }) {
            return HitFailure.OUT_OF_RANGE
        }

        return HitFailure.NO_HIT
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

    private fun hitQuality(result: HitResult): MeasureQuality {
        return if (result.trackable is Plane) MeasureQuality.HIGH else MeasureQuality.LOW
    }

    private fun freezeMeasurement() {
        if (anchors.size < 2) return

        val first = anchorPoint(anchors[0])
        val second = anchorPoint(anchors[1])
        val distance = Measurement.distance(first, second)
        frozenPoints = listOf(first, second)
        frozenDistance = distance
        frozenQuality = when {
            !distance.isFinite() ||
                distance < MIN_MEASUREMENT_M ||
                distance > MAX_MEASUREMENT_M -> MeasureQuality.UNRELIABLE
            pointQualities.size < 2 || pointQualities.any { it == MeasureQuality.LOW } ->
                MeasureQuality.LOW
            else -> MeasureQuality.HIGH
        }
        Log.d(TAG, "measurement=${frozenDistance}m quality=$frozenQuality")

        anchors.forEach { it.detach() }
        anchors.clear()
        pointQualities.clear()
    }

    private fun clearCompletedMeasurement() {
        if (frozenPoints == null && anchors.size < 2) return
        anchors.forEach { it.detach() }
        anchors.clear()
        pointQualities.clear()
        frozenPoints = null
        frozenDistance = 0.0
        frozenQuality = MeasureQuality.HIGH
        state.clear()
    }

    private fun anchorPoint(anchor: Anchor): Vec3 {
        val translation = anchor.pose.translation
        return Vec3(
            translation[0].toDouble(),
            translation[1].toDouble(),
            translation[2].toDouble()
        )
    }

    fun reset() {
        anchors.forEach { it.detach() }
        anchors.clear()
        pointQualities.clear()
        frozenPoints = null
        frozenDistance = 0.0
        frozenQuality = MeasureQuality.HIGH
        state.clear()
    }

    fun pause() {
        pendingTap = false
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

    private companion object {
        const val TAG = "ArMeasure"
        const val MIN_HIT_DISTANCE_M = 0.1f
        const val MAX_HIT_DISTANCE_M = 12.0f
        const val MIN_DEPTH_DISTANCE_M = 0.3f
        const val MAX_DEPTH_DISTANCE_M = 5.0f
        const val MIN_MEASUREMENT_M = 0.005
        const val MAX_MEASUREMENT_M = 12.0
        const val TAP_TIMEOUT_MS = 500L
    }
}
