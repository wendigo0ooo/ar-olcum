package com.wendigo.arolcum.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
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

    @Volatile
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
        if (!sessionResumed || viewportWidth <= 0 || viewportHeight <= 0) return

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

        val points = ArrayList<Vec3>(anchors.size)
        val iterator = anchors.iterator()
        while (iterator.hasNext()) {
            val anchor = iterator.next()
            if (anchor.trackingState == ArTrackingState.STOPPED) {
                anchor.detach()
                iterator.remove()
                continue
            }
            val translation = anchor.pose.translation
            points.add(
                Vec3(
                    translation[0].toDouble(),
                    translation[1].toDouble(),
                    translation[2].toDouble()
                )
            )
        }

        val distance = if (points.size >= 2) {
            Measurement.distance(points[0], points[1])
        } else {
            0.0
        }

        state.publish(
            viewProjection = viewProjection.copyOf(),
            width = viewportWidth,
            height = viewportHeight,
            points = points,
            distanceMeters = distance,
            tracking = mapTracking(camera),
            planeCount = planeCount
        )

        if (pendingTap) {
            pendingTap = false
            processTap(frame, pendingTapX, pendingTapY)
        }
    }

    fun handleTap(x: Float, y: Float) {
        pendingTapX = x
        pendingTapY = y
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
        val hit = hits.firstOrNull { result -> isPreferredHit(result) }
            ?: hits.firstOrNull { result -> isTrackingHit(result) }
        if (hit == null) {
            val trackingPlanes = session.getAllTrackables(Plane::class.java)
                .count { it.trackingState == ArTrackingState.TRACKING }
            onHitMiss?.invoke(
                if (hits.isEmpty() && trackingPlanes == 0) HitFailure.NO_PLANE else HitFailure.NO_HIT
            )
            return
        }

        val anchor = try {
            hit.createAnchor()
        } catch (exception: Exception) {
            onHitMiss?.invoke(HitFailure.NO_HIT)
            return
        }

        if (anchors.size >= 2) {
            anchors.forEach { it.detach() }
            anchors.clear()
        }
        anchors.add(anchor)
    }

    private fun isPreferredHit(result: HitResult): Boolean {
        return when (val trackable = result.trackable) {
            is Plane -> trackable.trackingState == ArTrackingState.TRACKING &&
                trackable.isPoseInExtents(result.hitPose)
            is Point -> trackable.trackingState == ArTrackingState.TRACKING
            is DepthPoint -> trackable.trackingState == ArTrackingState.TRACKING
            else -> false
        }
    }

    private fun isTrackingHit(result: HitResult): Boolean {
        return when (result.trackable) {
            is Plane, is Point, is DepthPoint ->
                result.trackable.trackingState == ArTrackingState.TRACKING
            else -> false
        }
    }

    fun reset() {
        anchors.forEach { it.detach() }
        anchors.clear()
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
}
