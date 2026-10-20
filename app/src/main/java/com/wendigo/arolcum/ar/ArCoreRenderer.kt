package com.wendigo.arolcum.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
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
    private var lastFrame: Frame? = null

    @Volatile
    var displayRotation: Int = 0

    @Volatile
    var onHitMiss: (() -> Unit)? = null

    @Volatile
    var sessionResumed: Boolean = false

    private var viewportWidth = 0
    private var viewportHeight = 0
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
        lastFrame = frame

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
    }

    fun handleTap(x: Float, y: Float) {
        if (!sessionResumed) return
        val frame = lastFrame ?: return
        val hit = frame.hitTest(x, y).firstOrNull { result ->
            when (val trackable = result.trackable) {
                is Plane -> trackable.trackingState == ArTrackingState.TRACKING &&
                    trackable.isPoseInPolygon(result.hitPose)
                is Point -> trackable.trackingState == ArTrackingState.TRACKING
                is DepthPoint -> trackable.trackingState == ArTrackingState.TRACKING
                else -> false
            }
        }
        if (hit == null) {
            onHitMiss?.invoke()
            return
        }

        val anchor = try {
            hit.createAnchor()
        } catch (exception: Exception) {
            onHitMiss?.invoke()
            return
        }

        if (anchors.size >= 2) {
            anchors.forEach { it.detach() }
            anchors.clear()
        }
        anchors.add(anchor)
    }

    fun reset() {
        anchors.forEach { it.detach() }
        anchors.clear()
        state.clear()
    }

    fun pause() {
        lastFrame = null
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
