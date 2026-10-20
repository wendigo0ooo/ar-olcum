package com.wendigo.arolcum

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.wendigo.arolcum.ar.ArAvailability

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var startButton: MaterialButton
    private val handler = Handler(Looper.getMainLooper())
    private val recheckRunnable = Runnable { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.btnStart)
        startButton.setOnClickListener { onStartClicked() }

        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "?" }

        findViewById<TextView>(R.id.aboutText).text =
            getString(R.string.about_text, version)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onPause() {
        handler.removeCallbacks(recheckRunnable)
        super.onPause()
    }

    private fun refreshStatus() {
        handler.removeCallbacks(recheckRunnable)
        val status = ArAvailability.check(this)

        statusText.setText(
            when (status) {
                ArAvailability.Status.CHECKING -> R.string.status_checking
                ArAvailability.Status.SUPPORTED -> R.string.status_supported
                ArAvailability.Status.NEEDS_INSTALL -> R.string.status_needs_install
                ArAvailability.Status.UNSUPPORTED -> R.string.status_unsupported
                ArAvailability.Status.UNKNOWN -> R.string.status_unknown
            }
        )

        statusText.setTextColor(
            ContextCompat.getColor(
                this,
                when (status) {
                    ArAvailability.Status.SUPPORTED -> R.color.accent
                    ArAvailability.Status.NEEDS_INSTALL -> R.color.warning
                    ArAvailability.Status.UNSUPPORTED -> R.color.danger
                    else -> R.color.text_dim
                }
            )
        )

        startButton.isEnabled = status != ArAvailability.Status.CHECKING

        if (status == ArAvailability.Status.CHECKING) {
            handler.postDelayed(recheckRunnable, 600L)
        }
    }

    private fun onStartClicked() {
        when (ArAvailability.check(this)) {
            ArAvailability.Status.SUPPORTED -> {
                Toast.makeText(this, R.string.toast_measure_soon, Toast.LENGTH_LONG).show()
            }

            ArAvailability.Status.NEEDS_INSTALL -> {
                showInstallDialog()
            }

            ArAvailability.Status.UNSUPPORTED -> {
                showSimpleDialog(
                    R.string.dialog_unsupported_title,
                    R.string.dialog_unsupported_message
                )
            }

            ArAvailability.Status.CHECKING,
            ArAvailability.Status.UNKNOWN -> {
                showSimpleDialog(
                    R.string.dialog_unknown_title,
                    R.string.dialog_unknown_message
                )
            }
        }
    }

    private fun showInstallDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_install_title)
            .setMessage(R.string.dialog_install_message)
            .setPositiveButton(R.string.dialog_install_positive) { _, _ ->
                val result = ArAvailability.requestInstall(this)
                if (result == ArAvailability.InstallResult.FAILED) {
                    Toast.makeText(this, R.string.install_failed, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.dialog_install_negative, null)
            .show()
    }

    private fun showSimpleDialog(@StringRes title: Int, @StringRes message: Int) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.dialog_close, null)
            .show()
    }
}
