package com.yokuli.anchorwatch.location

import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState

enum class NmeaSourceAvailability {
    AVAILABLE,
    NOT_CONNECTED,
    NO_VALID_FIX,
    STALE_FIX,
}

/** A saved endpoint is not a usable GPS source until its live stream has a fresh position. */
object NmeaSourceSelectionPolicy {
    private val LIVE_TRANSPORT_STATES = setOf(
        NmeaConnectionState.CONNECTED,
        NmeaConnectionState.CONNECTED_NO_DATA,
        NmeaConnectionState.CONNECTED_NO_FIX,
        NmeaConnectionState.STALE,
    )

    /** Connection labels describe transport presentation, not position truth.
     * A parsed current-generation fix can arrive immediately before the state
     * promotion to CONNECTED, or while a watchdog label is being corrected. */
    fun hasLiveTransportState(connectionState: NmeaConnectionState): Boolean =
        connectionState in LIVE_TRANSPORT_STATES

    fun availability(
        connectionState: NmeaConnectionState,
        fix: NavigationFix?,
        connectionStartedElapsedRealtime: Long?,
        nowElapsedRealtime: Long,
        maximumAgeMillis: Long,
    ): NmeaSourceAvailability {
        if (!hasLiveTransportState(connectionState)) return NmeaSourceAvailability.NOT_CONNECTED
        if (fix?.valid != true || connectionStartedElapsedRealtime == null || fix.receivedElapsedRealtime < connectionStartedElapsedRealtime) {
            return NmeaSourceAvailability.NO_VALID_FIX
        }
        val age = nowElapsedRealtime - fix.receivedElapsedRealtime
        return if (age in 0..maximumAgeMillis.coerceAtLeast(1L)) {
            NmeaSourceAvailability.AVAILABLE
        } else {
            NmeaSourceAvailability.STALE_FIX
        }
    }

    /**
     * The single product-level gate for using NMEA as a position source.
     * A socket that merely says CONNECTED is not enough: the position must
     * belong to the current connection, still be fresh, and not carry an
     * explicitly failed fix-quality/HDOP value.
     */
    fun isUsablePosition(
        connectionState: NmeaConnectionState,
        fix: NavigationFix?,
        connectionStartedElapsedRealtime: Long?,
        nowElapsedRealtime: Long,
        maximumAgeMillis: Long,
    ): Boolean = availability(
        connectionState,
        fix,
        connectionStartedElapsedRealtime,
        nowElapsedRealtime,
        maximumAgeMillis,
    ) == NmeaSourceAvailability.AVAILABLE && NmeaFixQualityPolicy.allowsContinuation(fix, nowElapsedRealtime)
}

/** Resolves the position source for a new anchor session without changing the
 * user's saved preference. A live NMEA instrument connection may legitimately
 * contain depth/wind but no position; in that case Phone GNSS is the safe
 * arming source while the NMEA socket remains available to those instruments. */
object NewAnchorPositionSourcePolicy {
    fun resolve(
        configuredSource: GpsDataSource,
        demoMode: Boolean,
        nmeaPositionUsable: Boolean,
    ): GpsDataSource = when {
        demoMode -> GpsDataSource.DEMO
        configuredSource == GpsDataSource.NMEA && nmeaPositionUsable -> GpsDataSource.NMEA
        configuredSource == GpsDataSource.NMEA -> GpsDataSource.SYSTEM
        configuredSource == GpsDataSource.DEMO -> GpsDataSource.SYSTEM
        else -> configuredSource
    }
}
