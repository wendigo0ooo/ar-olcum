package com.wendigo.arolcum

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.wendigo.arolcum.ar.ArCoreRenderer
import com.wendigo.arolcum.ar.MeasureState
import com.wendigo.arolcum.ar.TrackingState
import com.wendigo.arolcum.core.UnitSystem
import com.wendigo.arolcum.core.Units
import com.wendigo.arolcum.ui.OverlayView

class MeasureActivity : AppCompatActivity() {
    private lateinit var surface: GLSurfaceView
    private lateinit var overlay: OverlayView
    private lateinit var trackingText: TextView
    private lateinit var measureHint: TextView
    private lateinit var distanceText: TextView
    private lateinit var unitButton: MaterialButton
    private lateinit var resetButton: MaterialButton
    private lateinit var copyButton: MaterialButton
    private lateinit var shareButton: MaterialButton

    private val state = MeasureState()
    private val handler = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var renderer: ArCoreRenderer? = null
    private var sessionResumed = false
    private var activityResumed = false
    private var arFailed = false
    private var permissionRequestInFlight = false
    private var permissionDialogVisible = false
    private var unitSystem = UnitSystem.METRIC
    private var tapDownX = 0f
    private var tapDownY = 0f

    private val uiTicker = object : Runnable {
        override fun run() {
            renderUi()
            handler.postDelayed(this, 200L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContentView(R.layout.activity_measure)

        surface = findViewById(R.id.surface)
        overlay = findViewById(R.id.overlay)
        trackingText = findViewById(R.id.trackingText)
        measureHint = findViewById(R.id.measureHint)
        distanceText = findViewById(R.id.distanceText)
        unitButton = findViewById(R.id.btnUnit)
        resetButton = findViewById(R.id.btnReset)
        copyButton = findViewById(R.id.btnCopy)
        shareButton = findViewById(R.id.btnShare)

        unitSystem = if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_IMPERIAL, false)) {
            UnitSystem.IMPERIAL
        } else {
            UnitSystem.METRIC
        }

        overlay.state = state
        overlay.unitSystem = unitSystem
        overlay.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tapDownX = event.x
                    tapDownY = event.y
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
                    val dx = event.x - tapDownX
                    val dy = event.y - tapDownY
                    if (dx * dx + dy * dy <= slop * slop) {
                        requestHit(event.x, event.y)
                        view.performClick()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }

        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }
        unitButton.setOnClickListener { toggleUnit() }
        resetButton.setOnClickListener { resetMeasurement() }
        copyButton.setOnClickListener { copyMeasurement() }
        shareButton.setOnClickListener { shareMeasurement() }

        if (hasCameraPermission()) {
            setupAr()
        } else {
            requestCameraPermission()
        }
        renderUi()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (hasCameraPermission()) {
            if (session == null && !arFailed) {
                setupAr()
            }
        } else if (!permissionRequestInFlight) {
            showPermissionDialog()
        }
        resumeAr()
        handler.removeCallbacks(uiTicker)
        handler.post(uiTicker)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        renderer?.displayRotation = displayRotation()
    }

    override fun onPause() {
        handler.removeCallbacks(uiTicker)
        activityResumed = false
        if (sessionResumed) {
            renderer?.sessionResumed = false
            surface.onPause()
            renderer?.pause()
            session?.pause()
            sessionResumed = false
        }
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(uiTicker)
        renderer?.sessionResumed = false
        if (::surface.isInitialized) {
            surface.onPause()
        }
        renderer?.pause()
        if (sessionResumed) {
            session?.pause()
            sessionResumed = false
        }
        session?.close()
        session = null
        renderer = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        permissionRequestInFlight = false
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setupAr()
            if (activityResumed) resumeAr()
        } else {
            showPermissionDialog()
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        permissionRequestInFlight = true
        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
    }

    private fun setupAr() {
        if (session != null || arFailed || !hasCameraPermission()) return

        val newSession = try {
            Session(this)
        } catch (exception: Exception) {
            arFailed = true
            showSessionError(exception)
            return
        }

        try {
            newSession.configure(
                Config(newSession).apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.AUTO
                    lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                }
            )
        } catch (exception: Exception) {
            newSession.close()
            arFailed = true
            showSessionError(exception)
            return
        }

        session = newSession
        val newRenderer = ArCoreRenderer(newSession, state)
        newRenderer.displayRotation = displayRotation()
        newRenderer.onHitMiss = {
            runOnUiThread {
                Toast.makeText(this, R.string.measure_no_hit, Toast.LENGTH_SHORT).show()
            }
        }
        renderer = newRenderer

        surface.setPreserveEGLContextOnPause(true)
        surface.setEGLContextClientVersion(2)
        surface.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surface.setRenderer(newRenderer)
        surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        renderUi()
    }

    private fun resumeAr() {
        val currentSession = session ?: return
        if (!activityResumed || sessionResumed) return
        renderer?.displayRotation = displayRotation()
        try {
            currentSession.resume()
        } catch (exception: CameraNotAvailableException) {
            showSessionError(exception)
            return
        } catch (exception: Exception) {
            showSessionError(exception)
            return
        }
        renderer?.sessionResumed = true
        surface.onResume()
        sessionResumed = true
    }

    private fun renderUi() {
        if (!::trackingText.isInitialized) return

        val snapshot = state.snapshot
        trackingText.setText(trackingStatusText(snapshot.tracking, snapshot.planeCount))

        if (snapshot.hasMeasurement) {
            distanceText.text = Units.formatLength(snapshot.distanceMeters, unitSystem)
        } else {
            distanceText.setText(R.string.measure_dash)
        }

        measureHint.setText(
            when (snapshot.points.size) {
                0 -> R.string.measure_hint_start
                1 -> R.string.measure_hint_second
                else -> R.string.measure_hint_done
            }
        )

        resetButton.isEnabled = snapshot.points.isNotEmpty()
        copyButton.isEnabled = snapshot.hasMeasurement
        shareButton.isEnabled = snapshot.hasMeasurement
        unitButton.setText(
            if (unitSystem == UnitSystem.METRIC) {
                R.string.measure_unit_metric
            } else {
                R.string.measure_unit_imperial
            }
        )
        overlay.unitSystem = unitSystem
    }

    private fun trackingStatusText(tracking: TrackingState, planeCount: Int): Int {
        return when (tracking) {
            TrackingState.TRACKING -> {
                if (planeCount > 0) {
                    R.string.measure_tracking_ok
                } else {
                    R.string.measure_tracking_plane_search
                }
            }

            TrackingState.INITIALIZING -> R.string.measure_tracking_searching
            TrackingState.INSUFFICIENT_LIGHT -> R.string.measure_tracking_light
            TrackingState.INSUFFICIENT_FEATURES -> R.string.measure_tracking_features
            TrackingState.EXCESSIVE_MOTION -> R.string.measure_tracking_motion
            TrackingState.RELOCALIZING -> R.string.measure_tracking_relocalizing
            TrackingState.PAUSED -> R.string.measure_tracking_paused
        }
    }

    private fun requestHit(x: Float, y: Float) {
        val currentRenderer = renderer ?: return
        surface.queueEvent { currentRenderer.handleTap(x, y) }
    }

    private fun toggleUnit() {
        unitSystem = if (unitSystem == UnitSystem.METRIC) {
            UnitSystem.IMPERIAL
        } else {
            UnitSystem.METRIC
        }
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IMPERIAL, unitSystem == UnitSystem.IMPERIAL)
            .apply()
        overlay.unitSystem = unitSystem
        renderUi()
    }

    private fun resetMeasurement() {
        state.clear()
        renderer?.let { currentRenderer ->
            surface.queueEvent { currentRenderer.reset() }
        }
        renderUi()
    }

    private fun copyMeasurement() {
        val text = measurementText() ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.measure_copy_label), text)
        )
        Toast.makeText(this, R.string.measure_copy_done, Toast.LENGTH_SHORT).show()
    }

    private fun shareMeasurement() {
        val text = measurementText() ?: return
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, getString(R.string.measure_share_text, text))
        }
        startActivity(
            Intent.createChooser(shareIntent, getString(R.string.measure_share_chooser))
        )
    }

    private fun measurementText(): String? {
        val snapshot = state.snapshot
        if (!snapshot.hasMeasurement) return null
        return Units.formatLength(snapshot.distanceMeters, unitSystem)
    }

    private fun showPermissionDialog() {
        if (permissionDialogVisible || isFinishing) return
        permissionDialogVisible = true
        val canAskAgain = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        AlertDialog.Builder(this)
            .setTitle(R.string.measure_permission_title)
            .setMessage(R.string.measure_permission_message)
            .setPositiveButton(
                if (canAskAgain) {
                    R.string.measure_permission_positive
                } else {
                    R.string.measure_permission_settings
                }
            ) { _, _ ->
                if (canAskAgain) {
                    requestCameraPermission()
                } else {
                    openAppSettings()
                }
            }
            .setNegativeButton(R.string.measure_permission_negative) { _, _ -> finish() }
            .setOnCancelListener { _ -> finish() }
            .setOnDismissListener { permissionDialogVisible = false }
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        val launched = runCatching { startActivity(intent) }.isSuccess
        if (!launched) {
            finish()
        }
    }

    private fun showSessionError(exception: Exception) {
        val detail = exception.localizedMessage
            ?: exception.javaClass.simpleName
        AlertDialog.Builder(this)
            .setTitle(R.string.measure_session_error_title)
            .setMessage(getString(R.string.measure_session_error, detail))
            .setPositiveButton(R.string.dialog_close) { _, _ -> finish() }
            .setOnCancelListener { _ -> finish() }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = windowManager.defaultDisplay.rotation

    companion object {
        private const val REQ_CAMERA = 2001
        private const val PREFS = "ar_olcum"
        private const val KEY_IMPERIAL = "unit_imperial"
    }
}
