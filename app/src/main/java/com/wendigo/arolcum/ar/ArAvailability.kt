package com.wendigo.arolcum.ar

import android.app.Activity
import android.content.Context
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException

object ArAvailability {
    enum class Status {
        CHECKING,
        SUPPORTED,
        NEEDS_INSTALL,
        UNSUPPORTED,
        UNKNOWN
    }

    enum class InstallResult {
        REQUESTED,
        ALREADY_INSTALLED,
        FAILED
    }

    fun check(context: Context): Status {
        return try {
            when (ArCoreApk.getInstance().checkAvailability(context)) {
                ArCoreApk.Availability.SUPPORTED_INSTALLED -> Status.SUPPORTED
                ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
                ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> Status.NEEDS_INSTALL
                ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> Status.UNSUPPORTED
                ArCoreApk.Availability.UNKNOWN_CHECKING -> Status.CHECKING
                else -> Status.UNKNOWN
            }
        } catch (exception: Exception) {
            Status.UNKNOWN
        }
    }

    fun requestInstall(activity: Activity): InstallResult {
        return try {
            when (ArCoreApk.getInstance().requestInstall(activity, true)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> InstallResult.REQUESTED
                ArCoreApk.InstallStatus.INSTALLED -> InstallResult.ALREADY_INSTALLED
                else -> InstallResult.FAILED
            }
        } catch (exception: UnavailableDeviceNotCompatibleException) {
            InstallResult.FAILED
        } catch (exception: UnavailableUserDeclinedInstallationException) {
            InstallResult.FAILED
        } catch (exception: Exception) {
            InstallResult.FAILED
        }
    }
}
