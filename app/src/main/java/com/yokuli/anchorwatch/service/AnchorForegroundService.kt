package com.yokuli.anchorwatch.service

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yokuli.anchorwatch.runtime.RuntimeCommandParser
import com.yokuli.anchorwatch.runtime.RuntimeServiceHost
import com.yokuli.anchorwatch.runtime.YokuliRuntimeCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Android-only lifecycle shell. Stateful watch, proxy, sharing and sonar work is owned by
 * [YokuliRuntimeCoordinator], where it is serialized independently from the framework callbacks.
 */
@AndroidEntryPoint
class AnchorForegroundService : Service() {
    @Inject lateinit var runtime: YokuliRuntimeCoordinator

    private val runtimeHost = object : RuntimeServiceHost {
        override fun notificationPermissionGranted(): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(
                    this@AnchorForegroundService,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED

        override fun startForeground(notification: Notification, location: Boolean): Boolean =
            runCatching {
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        if (location) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
                } else {
                    0
                }
                ServiceCompat.startForeground(
                    this@AnchorForegroundService,
                    ONGOING,
                    notification,
                    type,
                )
            }.isSuccess

        override fun stopForegroundAndSelf() {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        runtime.start(runtimeHost)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService request gets a synchronous foreground
        // acknowledgement, including commands racing an idle self-stop. The
        // coordinator replaces this starter notification with live state.
        runtime.ensureCommandForeground()
        runtime.submit(RuntimeCommandParser.parse(intent))
        return START_STICKY
    }

    override fun onDestroy() {
        runtime.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ARM = "com.yokuli.anchorwatch.ARM"
        const val ACK = "com.yokuli.anchorwatch.ACK"
        const val SNOOZE = "com.yokuli.anchorwatch.SNOOZE"
        const val STOP_WATCH = "com.yokuli.anchorwatch.STOP_WATCH"
        const val PAUSE_WATCH = "com.yokuli.anchorwatch.PAUSE_WATCH"
        const val RESUME_WATCH = "com.yokuli.anchorwatch.RESUME_WATCH"
        const val SWITCH_WATCH_GPS_SOURCE = "com.yokuli.anchorwatch.SWITCH_WATCH_GPS_SOURCE"
        const val LIFT_ANCHOR = "com.yokuli.anchorwatch.LIFT_ANCHOR"
        const val UPDATE_RADIUS = "com.yokuli.anchorwatch.UPDATE_RADIUS"
        const val STOP_WATCH_AND_DISCONNECT = "com.yokuli.anchorwatch.STOP_WATCH_AND_DISCONNECT"
        const val STOP_NMEA_DEPENDENCIES_AND_DISCONNECT = "com.yokuli.anchorwatch.STOP_NMEA_DEPENDENCIES_AND_DISCONNECT"
        const val CONTINUE_TRIP_WITH_PHONE_AND_DISCONNECT = "com.yokuli.anchorwatch.CONTINUE_TRIP_WITH_PHONE_AND_DISCONNECT"
        const val ACCEPT_ESTIMATED_CENTER = "com.yokuli.anchorwatch.ACCEPT_ESTIMATED_CENTER"
        const val KEEP_CURRENT_CENTER = "com.yokuli.anchorwatch.KEEP_CURRENT_CENTER"
        const val CONTINUE_ESTIMATING_CENTER = "com.yokuli.anchorwatch.CONTINUE_ESTIMATING_CENTER"
        const val RESET_CENTRE_ANALYSIS = "com.yokuli.anchorwatch.RESET_CENTRE_ANALYSIS"
        const val APPLY_RECALCULATED_CENTRE = "com.yokuli.anchorwatch.APPLY_RECALCULATED_CENTRE"
        const val UPDATE_CONDITION_GUARDS = "com.yokuli.anchorwatch.UPDATE_CONDITION_GUARDS"
        const val RESET_WIND_BASELINE = "com.yokuli.anchorwatch.RESET_WIND_BASELINE"
        const val START_PROXY = "com.yokuli.anchorwatch.START_PROXY"
        const val STOP_PROXY = "com.yokuli.anchorwatch.STOP_PROXY"
        const val TEST_ALARM = "com.yokuli.anchorwatch.TEST_ALARM"
        const val STOP_ALARM_TEST = "com.yokuli.anchorwatch.STOP_ALARM_TEST"
        const val SET_NMEA_SHARING = "com.yokuli.anchorwatch.SET_NMEA_SHARING"
        const val REFRESH_PHONE_SENSOR_OUTPUT = "com.yokuli.anchorwatch.REFRESH_PHONE_SENSOR_OUTPUT"
        const val START_TRIP = "com.yokuli.anchorwatch.START_TRIP"
        const val PAUSE_TRIP = "com.yokuli.anchorwatch.PAUSE_TRIP"
        const val RESUME_TRIP = "com.yokuli.anchorwatch.RESUME_TRIP"
        const val END_TRIP = "com.yokuli.anchorwatch.END_TRIP"
        const val MARK_TRIP_WAYPOINT = "com.yokuli.anchorwatch.MARK_TRIP_WAYPOINT"
        const val START_SONAR_SURVEY = "com.yokuli.anchorwatch.START_SONAR_SURVEY"
        const val STOP_SONAR_SURVEY = "com.yokuli.anchorwatch.STOP_SONAR_SURVEY"
        const val STATUS_CH = "anchor_status"
        const val EVENT_CH = "anchor_events_v2"
        const val ALARM_CH = "anchor_alarm_selectable_v2"
        const val ONGOING = 42
        const val EVENT = 43
    }
}
