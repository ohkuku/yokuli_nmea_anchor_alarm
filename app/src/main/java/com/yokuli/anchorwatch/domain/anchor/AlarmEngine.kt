package com.yokuli.anchorwatch.domain.anchor

import com.yokuli.anchorwatch.domain.model.AlarmSnapshot
import com.yokuli.anchorwatch.domain.model.AlarmState
import com.yokuli.anchorwatch.domain.model.AlarmType
import com.yokuli.anchorwatch.domain.model.AnchorConfig
import com.yokuli.anchorwatch.domain.model.NavigationFix
import kotlin.math.max
import com.yokuli.anchorwatch.runtime.MonotonicClock
import com.yokuli.anchorwatch.runtime.SystemMonotonicClock

/** Safety state machine with persistence on both alarm entry and recovery. */
class AlarmEngine(
    private val persistenceMillis: Long = 8_000L,
    private val requiredFixes: Int = 3,
    private val gpsLossMillis: Long = 15_000L,
    private val clearPersistenceMillis: Long = 12_000L,
    private val clearRequiredFixes: Int = 5,
    private val clock:MonotonicClock = SystemMonotonicClock,
) {
    private var config: AnchorConfig? = null
    private var learning = false
    private var outsideSince: Long? = null
    private var outsideCount = 0
    private var clearSince: Long? = null
    private var clearCount = 0
    private var warningCount = 0
    private var warningClearCount = 0
    private var warningLatched = false
    private var alarmLatched = false
    private var lastFix: Long? = null
    private var sum = 0.0
    private var count = 0
    private var snap = AlarmSnapshot()

    /** Back-down learning still protects the user-defined temporary boundary. */
    fun learn(c: AnchorConfig, now: Long = clock.elapsedRealtime()): AlarmSnapshot =
        initialise(c, now, isLearning = true)

    fun arm(c: AnchorConfig, now: Long = clock.elapsedRealtime()): AlarmSnapshot =
        initialise(c, now, isLearning = false)

    private fun initialise(c: AnchorConfig, now: Long, isLearning: Boolean): AlarmSnapshot {
        config = c
        learning = isLearning
        outsideSince = null
        outsideCount = 0
        clearSince = null
        clearCount = 0
        warningCount = 0
        warningClearCount = 0
        warningLatched = false
        alarmLatched = false
        lastFix = now
        sum = 0.0
        count = 0
        snap = AlarmSnapshot(if (isLearning) AlarmState.LEARNING else AlarmState.ARMED)
        return snap
    }

    fun stop(): AlarmSnapshot = AlarmSnapshot(AlarmState.STOPPED).also {
        snap = it
        config = null
        learning = false
        alarmLatched = false
        warningLatched = false
        outsideSince = null
        outsideCount = 0
        clearSince = null
        clearCount = 0
    }

    /**
     * Change only the watch geometry without introducing an artificial safe
     * window. A radius change that still leaves the vessel outside preserves
     * the existing radius-alarm latch; a change that makes it safe resets the
     * latch and lets subsequent fixes build warning/alarm persistence anew.
     */
    fun updateConfig(c:AnchorConfig,preservedAlarmType:AlarmType?):AlarmSnapshot{
        config=c
        outsideSince=null;outsideCount=0;clearSince=null;clearCount=0
        warningCount=0;warningClearCount=0;warningLatched=false
        alarmLatched=preservedAlarmType==AlarmType.ANCHOR_RADIUS_EXCEEDED
        snap=snap.copy(
            state=when{preservedAlarmType!=null->AlarmState.ALARM;learning->AlarmState.LEARNING;else->AlarmState.ARMED},
            type=preservedAlarmType,
            acknowledged=false,
        )
        return snap
    }

    fun acknowledge(): AlarmSnapshot = snap.copy(
        state = if (snap.type != null) AlarmState.ACKNOWLEDGED else snap.state,
        acknowledged = snap.type != null,
    ).also { snap = it }

    fun onFix(f: NavigationFix, now: Long = f.receivedElapsedRealtime): AlarmSnapshot {
        val c = config ?: return snap
        if (!f.valid) return tick(now)
        lastFix = now
        val distance = AnchorGeometry.distanceMeters(c.latitude, c.longitude, f.latitude, f.longitude)
        sum += distance
        count++

        val outsideAlarm = distance > c.alarmRadiusMeters
        if (outsideAlarm) {
            if (outsideSince == null) outsideSince = now
            outsideCount++
        } else {
            outsideSince = null
            outsideCount = 0
        }
        if (!alarmLatched && outsideAlarm &&
            (outsideCount >= requiredFixes || now - (outsideSince ?: now) >= persistenceMillis)
        ) alarmLatched = true

        if (alarmLatched) {
            val clearMargin = max(5.0, c.alarmRadiusMeters * .10)
            val safelyInside = distance < c.alarmRadiusMeters - clearMargin
            if (safelyInside) {
                if (clearSince == null) clearSince = now
                clearCount++
            } else {
                clearSince = null
                clearCount = 0
            }
            if (safelyInside && clearCount >= clearRequiredFixes &&
                now - (clearSince ?: now) >= clearPersistenceMillis
            ) {
                alarmLatched = false
                clearSince = null
                clearCount = 0
            }
        }

        val outsideWarning = distance > c.warningRadiusMeters
        if (outsideWarning) {
            warningCount++
            warningClearCount = 0
            if (warningCount >= 2) warningLatched = true
        } else if (distance < c.warningRadiusMeters - max(3.0, c.warningRadiusMeters * .05)) {
            warningClearCount++
            warningCount = 0
            if (warningClearCount >= 3) warningLatched = false
        }

        val state = when {
            alarmLatched -> AlarmState.ALARM
            warningLatched -> AlarmState.WARNING
            learning -> AlarmState.LEARNING
            else -> AlarmState.ARMED
        }
        snap = AlarmSnapshot(
            state = state,
            type = if (alarmLatched) AlarmType.ANCHOR_RADIUS_EXCEEDED else null,
            distanceMeters = distance,
            maxDistanceMeters = maxOf(snap.maxDistanceMeters, distance),
            minDistanceMeters = snap.minDistanceMeters?.let { minOf(it, distance) } ?: distance,
            averageDistanceMeters = sum / count,
            acknowledged = snap.acknowledged && alarmLatched,
        )
        return snap
    }

    fun tick(now: Long): AlarmSnapshot {
        val last = lastFix
        if (config != null && (last == null || now - last >= gpsLossMillis)) {
            snap = snap.copy(state = AlarmState.ALARM, type = AlarmType.GPS_DATA_LOST)
        }
        return snap
    }
}
