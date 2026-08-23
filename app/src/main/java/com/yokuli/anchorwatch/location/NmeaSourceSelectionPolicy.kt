package com.yokuli.anchorwatch.location

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
    fun availability(
        connectionState: NmeaConnectionState,
        fix: NavigationFix?,
        connectionStartedElapsedRealtime: Long?,
        nowElapsedRealtime: Long,
        maximumAgeMillis: Long,
    ): NmeaSourceAvailability {
        if (connectionState != NmeaConnectionState.CONNECTED) return NmeaSourceAvailability.NOT_CONNECTED
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
    ) == NmeaSourceAvailability.AVAILABLE && NmeaFixQualityPolicy.allowsContinuation(fix)
}
