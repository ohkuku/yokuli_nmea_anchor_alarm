package com.yokuli.anchorwatch.domain.safety

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.yokuli.anchorwatch.data.preferences.AppSettings
import com.yokuli.anchorwatch.data.sonar.SonarRecorderStatus
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.service.AnchorForegroundService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class SafetyCheckStatus { OK, WARNING, BLOCKER }

data class SafetyCheck(
    val id: String,
    val status: SafetyCheckStatus,
    val title: String,
    val detail: String,
    val risk: String? = null,
)

data class WatchSafetyReport(val checks: List<SafetyCheck> = emptyList(), val evaluatedAt: Long = 0L) {
    val ready: Boolean get() = checks.isNotEmpty() && checks.all { it.status == SafetyCheckStatus.OK }
    val canContinue: Boolean get() = checks.isNotEmpty() && checks.none { it.status == SafetyCheckStatus.BLOCKER }
    val blockers: Int get() = checks.count { it.status == SafetyCheckStatus.BLOCKER }
    val warnings: Int get() = checks.count { it.status == SafetyCheckStatus.WARNING }
}

data class DeviceSafetySnapshot(
    val notificationPermission: Boolean,
    val batteryOptimizationExempt: Boolean,
    val batteryPercent: Int,
    val alarmVolume: Int,
    val alarmVolumeMax: Int,
    val networkConnected: Boolean,
    val wifiConnected: Boolean,
    val backgroundServiceDeclared: Boolean,
    val freeStorageBytes: Long,
)

data class WatchSafetyInput(
    val nowElapsed: Long,
    val nowWall: Long,
    val settings: AppSettings,
    val selectedFix: NavigationFix?,
    val nmeaConnection: NmeaConnectionState,
    val device: DeviceSafetySnapshot,
    val sonar: SonarRecorderStatus,
)

object WatchPreflightEvaluator {
    private const val FRESH_FIX_MILLIS = 10_000L
    private const val ALARM_CONFIRMATION_MAX_AGE = 30L * 24L * 60L * 60L * 1_000L
    private const val STORAGE_BLOCKER = 10L * 1024L * 1024L
    private const val STORAGE_WARNING = 250L * 1024L * 1024L

    fun evaluate(input: WatchSafetyInput): WatchSafetyReport {
        val checks = mutableListOf<SafetyCheck>()
        val fix = input.selectedFix
        val age = fix?.let { input.nowElapsed - it.receivedElapsedRealtime }
        checks += when {
            fix?.valid != true -> blocker("gps_fresh", "GPS fix", "No valid fix", "An anchor alarm cannot measure vessel movement without a position.")
            age == null || age !in 0..FRESH_FIX_MILLIS -> blocker("gps_fresh", "GPS fix", "Stale by ${age?.div(1_000) ?: "?"} s", "Arming from an old position can put the boundary in the wrong place.")
            else -> ok("gps_fresh", "GPS fix", "Fresh · ${age / 1_000} s")
        }
        val accuracy = fix?.horizontalAccuracyMeters
        checks += when {
            fix?.valid != true -> blocker("gps_accuracy", "GPS accuracy", "Unavailable", "Position quality is unknown.")
            accuracy != null && accuracy > 100.0 -> blocker("gps_accuracy", "GPS accuracy", "±${accuracy.toInt()} m", "Accuracy is wider than a useful anchor boundary.")
            accuracy != null && accuracy > 25.0 -> warning("gps_accuracy", "GPS accuracy", "±${accuracy.toInt()} m", "Use a wider alarm radius or wait for better accuracy.")
            accuracy != null -> ok("gps_accuracy", "GPS accuracy", "±${accuracy.toInt()} m")
            fix.hdop != null && fix.hdop > 5.0 -> warning("gps_accuracy", "GPS accuracy", "HDOP ${"%.1f".format(fix.hdop)}", "Satellite geometry is weak; allow more margin.")
            fix.hdop != null -> ok("gps_accuracy", "GPS accuracy", "HDOP ${"%.1f".format(fix.hdop)}")
            else -> warning("gps_accuracy", "GPS accuracy", "Not reported", "The source did not report accuracy; choose a conservative range.")
        }
        checks += if (input.settings.gpsDataSource != GpsDataSource.NMEA) {
            ok("nmea", "NMEA source", "Not required for ${input.settings.gpsDataSource.name} GPS")
        } else when (input.nmeaConnection) {
            NmeaConnectionState.CONNECTED -> ok("nmea", "NMEA source", "Connected and delivering data")
            NmeaConnectionState.RECONNECTING, NmeaConnectionState.CONNECTING -> blocker("nmea", "NMEA source", input.nmeaConnection.name, "Wait for a stable live NMEA position before arming.")
            else -> blocker("nmea", "NMEA source", input.nmeaConnection.name, "The selected GPS source is unavailable.")
        }
        checks += if (input.device.notificationPermission) ok("notifications", "Alarm notifications", "Allowed")
        else blocker("notifications", "Alarm notifications", "Permission denied", "Android may hide critical anchor alarm notifications.")
        checks += when {
            input.device.alarmVolume <= 0 -> blocker("alarm_sound", "Alarm sound", "Android alarm volume is muted", "The alarm cannot be heard.")
            input.settings.alarmAudibleConfirmedAt == null -> warning("alarm_sound", "Alarm sound", "Not confirmed on this device", "Run the alarm test and confirm that you heard it.")
            input.nowWall - input.settings.alarmAudibleConfirmedAt > ALARM_CONFIRMATION_MAX_AGE -> warning("alarm_sound", "Alarm sound", "Last confirmed over 30 days ago", "Retest after Android, audio or vessel setup changes.")
            else -> ok("alarm_sound", "Alarm sound", "Audible test confirmed")
        }
        checks += if (input.device.backgroundServiceDeclared) ok("background", "Background monitor", "Foreground service available")
        else blocker("background", "Background monitor", "Service unavailable", "The watch cannot continue reliably when the screen is off.")
        checks += if (input.device.batteryOptimizationExempt) ok("battery_optimization", "Battery optimization", "Unrestricted")
        else warning("battery_optimization", "Battery optimization", "Android may restrict background work", "Allow unrestricted battery use for an overnight watch.")
        checks += when {
            input.device.batteryPercent !in 0..100 -> warning("battery", "Battery", "Not reported", "Confirm reliable external power before an overnight watch.")
            input.device.batteryPercent in 0..10 -> blocker("battery", "Battery", "${input.device.batteryPercent}%", "Connect reliable power before starting the watch.")
            input.device.batteryPercent in 11..25 -> warning("battery", "Battery", "${input.device.batteryPercent}%", "Keep the device on reliable power.")
            else -> ok("battery", "Battery", "${input.device.batteryPercent}%")
        }
        checks += if (input.settings.gpsDataSource != GpsDataSource.NMEA) ok("network", "Wi-Fi / network", "Not required for selected GPS")
        else when {
            !input.device.networkConnected -> blocker("network", "Wi-Fi / network", "No active network", "The NMEA endpoint cannot be kept reachable.")
            input.device.wifiConnected -> ok("network", "Wi-Fi / network", "Wi-Fi connected")
            else -> warning("network", "Wi-Fi / network", "Connected without Wi-Fi", "Confirm the NMEA server is reachable over this transport.")
        }
        checks += when {
            input.device.freeStorageBytes < STORAGE_BLOCKER -> blocker("storage", "Storage", "Less than 10 MB free", "Safety events and track points may not be saved.")
            input.device.freeStorageBytes < STORAGE_WARNING -> warning("storage", "Storage", "${input.device.freeStorageBytes / 1024 / 1024} MB free", "Free storage before a long watch or sonar survey.")
            else -> ok("storage", "Storage", "${input.device.freeStorageBytes / 1024 / 1024} MB free")
        }
        checks += if (input.sonar.activeSurvey == null) ok("sonar", "Sonar", "Not required for anchor watch")
        else when {
            input.sonar.hasFreshDepth(input.nowElapsed) && (input.sonar.lastDepthIsDemo || input.sonar.hasFreshNmeaPosition(input.nowElapsed)) -> ok("sonar", "Sonar", "Live depth and matching position")
            else -> warning("sonar", "Sonar", "Survey active but data is stale", "Anchor watch can continue; sonar mapping will wait for same-stream data.")
        }
        return WatchSafetyReport(checks, input.nowWall)
    }

    private fun ok(id: String, title: String, detail: String) = SafetyCheck(id, SafetyCheckStatus.OK, title, detail)
    private fun warning(id: String, title: String, detail: String, risk: String) = SafetyCheck(id, SafetyCheckStatus.WARNING, title, detail, risk)
    private fun blocker(id: String, title: String, detail: String, risk: String) = SafetyCheck(id, SafetyCheckStatus.BLOCKER, title, detail, risk)
}

@Singleton
class DeviceSafetyProbe @Inject constructor(@ApplicationContext private val context: Context) {
    fun snapshot(): DeviceSafetySnapshot {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val audio = context.getSystemService(AudioManager::class.java)
        return DeviceSafetySnapshot(
            notificationPermission = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            batteryOptimizationExempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            batteryPercent = context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            alarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM),
            alarmVolumeMax = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            // Boat Wi-Fi commonly has no Internet/VALIDATED capability but is still
            // the correct local path to an NMEA server.
            networkConnected = capabilities != null,
            wifiConnected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            backgroundServiceDeclared = runCatching { context.packageManager.getServiceInfo(ComponentName(context, AnchorForegroundService::class.java), 0).enabled }.getOrDefault(false),
            freeStorageBytes = context.filesDir.usableSpace,
        )
    }
}
