package com.yokuli.anchorwatch.domain.safety

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.app.NotificationManager
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
import com.yokuli.anchorwatch.domain.model.AnchorOriginMode
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.domain.sonar.SonarDepthHoldState
import com.yokuli.anchorwatch.location.NmeaSourceSelectionPolicy
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
    val fullScreenAlarmAllowed:Boolean=true,
)

data class WatchSafetyInput(
    val nowElapsed: Long,
    val nowWall: Long,
    val settings: AppSettings,
    val selectedFix: NavigationFix?,
    val nmeaConnection: NmeaConnectionState,
    val device: DeviceSafetySnapshot,
    val sonar: SonarRecorderStatus,
    val nmeaConnectionStartedElapsedRealtime:Long?=null,
)

data class AnchorSetupReadinessInput(
    val originMode:AnchorOriginMode?,
    val acceptedPositionReady:Boolean,
    val coordinateValid:Boolean,
    val notificationPermission:Boolean,
    val sourceCompatible:Boolean,
    val geometryValid:Boolean=true,
    val rangeValid:Boolean=true,
    val depthGuardReady:Boolean=true,
    val windGuardReady:Boolean=true,
    val windShiftReady:Boolean=true,
)
data class AnchorSetupReadiness(val canStart:Boolean,val blockers:List<String>,val willWaitForGps:Boolean)

/** One source of truth for setup/preflight blockers. A Manual or Map origin is
 * an explicit coordinate and may persist a WAITING_FOR_GPS session; Current
 * and Backdown origins may never be created without accepted-position proof. */
object AnchorSetupReadinessEvaluator{
    fun evaluate(input:AnchorSetupReadinessInput):AnchorSetupReadiness{
        val acceptedRequired=input.originMode in setOf(AnchorOriginMode.CURRENT_ACCEPTED_POSITION,AnchorOriginMode.BACKDOWN_FROM_ACCEPTED_POSITION)
        val blockers=buildList{
            if(!input.sourceCompatible)add("POSITION_SOURCE_INCOMPATIBLE")
            if(!input.notificationPermission)add("NOTIFICATION_PERMISSION_REQUIRED")
            if(!input.coordinateValid)add("ANCHOR_COORDINATE_REQUIRED")
            if(acceptedRequired&&!input.acceptedPositionReady)add("ACCEPTED_POSITION_REQUIRED")
            if(!input.geometryValid)add("ANCHOR_GEOMETRY_INVALID")
            if(!input.rangeValid)add("ALARM_RANGE_INVALID")
            if(!input.depthGuardReady)add("DEPTH_GUARD_NOT_READY")
            if(!input.windGuardReady)add("WIND_GUARD_NOT_READY")
            if(!input.windShiftReady)add("WIND_SHIFT_NOT_READY")
        }
        return AnchorSetupReadiness(blockers.isEmpty(),blockers,!acceptedRequired&&!input.acceptedPositionReady&&input.coordinateValid)
    }
}

object WatchPreflightEvaluator {
    private const val FRESH_FIX_MILLIS = 10_000L
    private const val ALARM_CONFIRMATION_MAX_AGE = 30L * 24L * 60L * 60L * 1_000L
    private const val STORAGE_BLOCKER = 10L * 1024L * 1024L
    private const val STORAGE_WARNING = 250L * 1024L * 1024L

    fun evaluate(input: WatchSafetyInput): WatchSafetyReport {
        val checks = mutableListOf<SafetyCheck>()
        val fix = input.selectedFix
        val age = fix?.let { input.nowElapsed - it.receivedElapsedRealtime }
        val sharedReadiness=AnchorSetupReadinessEvaluator.evaluate(AnchorSetupReadinessInput(null,fix?.valid==true,coordinateValid=true,notificationPermission=input.device.notificationPermission,sourceCompatible=true))
        checks += when {
            fix?.valid != true -> warning("gps_fresh", "GPS fix", "No valid fix", "The session can start, but movement checks and track recording wait for GPS and the active watch will notify you.")
            age == null || age !in 0..FRESH_FIX_MILLIS -> warning("gps_fresh", "GPS fix", "Stale by ${age?.div(1_000) ?: "?"} s", "The session can start from the confirmed anchor coordinate; stale fixes are ignored until live GPS recovers.")
            else -> ok("gps_fresh", "GPS fix", "Fresh · ${age / 1_000} s")
        }
        val accuracy = fix?.horizontalAccuracyMeters
        checks += when {
            fix?.valid != true -> warning("gps_accuracy", "GPS accuracy", "Unavailable", "Position quality is unknown; unsafe fixes remain excluded after the session starts.")
            accuracy != null && accuracy > 100.0 -> warning("gps_accuracy", "GPS accuracy", "±${accuracy.toInt()} m", "Accuracy is wider than a useful anchor boundary; the watch will report degraded GPS until it improves.")
            accuracy != null && accuracy > 25.0 -> warning("gps_accuracy", "GPS accuracy", "±${accuracy.toInt()} m", "Use a wider alarm radius or wait for better accuracy.")
            accuracy != null -> ok("gps_accuracy", "GPS accuracy", "±${accuracy.toInt()} m")
            fix.hdop != null && fix.hdop > 5.0 -> warning("gps_accuracy", "GPS accuracy", "HDOP ${"%.1f".format(fix.hdop)}", "Satellite geometry is weak; allow more margin.")
            fix.hdop != null -> ok("gps_accuracy", "GPS accuracy", "HDOP ${"%.1f".format(fix.hdop)}")
            else -> warning("gps_accuracy", "GPS accuracy", "Not reported", "The source did not report accuracy; choose a conservative range.")
        }
        checks += if (input.settings.gpsDataSource != GpsDataSource.NMEA) {
            ok("nmea", "NMEA source", "Not required for ${input.settings.gpsDataSource.name} GPS")
        } else when {
            NmeaSourceSelectionPolicy.isUsablePosition(input.nmeaConnection,fix,input.nmeaConnectionStartedElapsedRealtime,input.nowElapsed,FRESH_FIX_MILLIS)->ok("nmea", "NMEA source", "Connected with a fresh acceptable position")
            input.nmeaConnection in setOf(NmeaConnectionState.RECONNECTING,NmeaConnectionState.CONNECTING)->warning("nmea", "NMEA source", input.nmeaConnection.name, "The session can start now; reconnect and GPS-loss monitoring stays active until a current acceptable position returns.")
            input.nmeaConnection==NmeaConnectionState.CONNECTED->warning("nmea", "NMEA source", "Position unavailable, stale or poor quality", "The session can start now; current-generation quality rules still exclude unsafe fixes from movement calculations.")
            else->warning("nmea", "NMEA source", input.nmeaConnection.name, "The session can start now and will notify you that the selected GPS source is unavailable.")
        }
        checks += if (!sharedReadiness.blockers.contains("NOTIFICATION_PERMISSION_REQUIRED")) ok("notifications", "Alarm notifications", "Allowed")
        else blocker("notifications", "Alarm notifications", "Permission denied", "Android may hide critical anchor alarm notifications.")
        checks += if(input.device.fullScreenAlarmAllowed)ok("full_screen_alarm","Full-screen alarm","Allowed")
        else warning("full_screen_alarm","Full-screen alarm","Needs action","Android may show only a lock-screen heads-up alert instead of opening the alarm screen.")
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
            !input.device.networkConnected -> warning("network", "Wi-Fi / network", "No active network", "The session can start, but NMEA GPS remains degraded until the endpoint is reachable.")
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
            input.sonar.lastDepthIsDemo && input.sonar.hasFreshDepth(input.nowElapsed) -> ok("sonar", "Sonar", "Live depth and matching position")
            input.sonar.depthHoldState in setOf(SonarDepthHoldState.LIVE,SonarDepthHoldState.HELD) && input.sonar.hasFreshNmeaPosition(input.nowElapsed) -> ok("sonar", "Sonar", if(input.sonar.depthHoldState==SonarDepthHoldState.LIVE)"Live depth and matching position" else "Held real depth with live same-stream position")
            input.sonar.depthHoldState==SonarDepthHoldState.WARNING && input.sonar.hasFreshNmeaPosition(input.nowElapsed) -> warning("sonar", "Sonar", "Held depth is approaching its safety limit", "A new real DPT/DBT is required before 5 minutes or 500 m of travel.")
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
            fullScreenAlarmAllowed = Build.VERSION.SDK_INT<34||context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent(),
        )
    }
}
