package com.yokuli.anchorwatch.runtime.sharing

import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NavigationFix
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Presentation heartbeat for an already accepted position.
 *
 * This never accepts raw GPS and never changes the measurement timestamp. It
 * only keeps downstream NMEA clients supplied while the last integrity-gated
 * fix is still genuinely fresh.
 */
@Singleton
class NmeaSharingPositionPublisher @Inject constructor() {
    private var latestSource: GpsDataSource? = null
    private var latestFix: NavigationFix? = null
    private var lastPublishedElapsed: Long? = null

    @Synchronized
    fun reset() {
        latestSource = null
        latestFix = null
        lastPublishedElapsed = null
    }

    /** A newly accepted fix is published immediately. */
    @Synchronized
    fun accept(
        source: GpsDataSource,
        fix: NavigationFix,
        selectedSource: GpsDataSource,
        nowElapsed: Long,
    ): NavigationFix? {
        latestSource = source
        latestFix = fix
        if (source != selectedSource || !isFresh(fix, nowElapsed)) return null
        lastPublishedElapsed = nowElapsed
        return fix
    }

    /** Seeds sharing when it is enabled between two accepted fixes. */
    @Synchronized
    fun seed(
        source: GpsDataSource,
        fix: NavigationFix?,
        selectedSource: GpsDataSource,
        nowElapsed: Long,
    ): NavigationFix? {
        reset()
        if (fix == null) return null
        latestSource = source
        latestFix = fix
        if (source != selectedSource || !isFresh(fix, nowElapsed)) return null
        lastPublishedElapsed = nowElapsed
        return fix
    }

    /** Re-publishes at about 1 Hz, but stops before an old fix can look live. */
    @Synchronized
    fun tick(selectedSource: GpsDataSource, nowElapsed: Long): NavigationFix? {
        val fix = latestFix ?: return null
        if (latestSource != selectedSource || !isFresh(fix, nowElapsed)) return null
        val last = lastPublishedElapsed
        if (last != null && nowElapsed - last < MIN_PUBLISH_INTERVAL_MILLIS) return null
        lastPublishedElapsed = nowElapsed
        return fix
    }

    private fun isFresh(fix: NavigationFix, nowElapsed: Long) =
        nowElapsed - fix.receivedElapsedRealtime in 0..MAX_ACCEPTED_FIX_AGE_MILLIS

    companion object {
        const val MAX_ACCEPTED_FIX_AGE_MILLIS = 2_500L
        const val MIN_PUBLISH_INTERVAL_MILLIS = 900L
    }
}
