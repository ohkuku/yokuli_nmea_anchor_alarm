package com.yokuli.anchorwatch.domain.anchor

import com.yokuli.anchorwatch.domain.model.NmeaConnectionState

enum class AnchorDepthSource { NMEA, MANUAL }

/**
 * Shared by estimated-anchor setup, centre-drop advanced setup and the runtime
 * arm gate. A fresh value alone is not enough: it must still belong to a live
 * NMEA transport. CONNECTED_NO_FIX and STALE refer to position availability;
 * a separately arriving fresh DPT/DBT value remains valid for depth geometry.
 */
object AnchorSetupDepthPolicy {
    private val liveTransportStates = setOf(
        NmeaConnectionState.CONNECTED,
        NmeaConnectionState.CONNECTED_NO_FIX,
        NmeaConnectionState.STALE,
    )

    fun nmeaAvailable(
        connection: NmeaConnectionState,
        depthMeters: Double?,
        receivedElapsedRealtime: Long?,
        nowElapsedRealtime: Long,
        maxAgeMillis: Long = 3_000L,
    ): Boolean =
        connection in liveTransportStates &&
            depthMeters != null && depthMeters.isFinite() && depthMeters >= 0.0 &&
            receivedElapsedRealtime?.let { nowElapsedRealtime - it in 0..maxAgeMillis } == true

    fun selectedDepth(
        source: AnchorDepthSource,
        manualDepthMeters: Double?,
        connection: NmeaConnectionState,
        nmeaDepthMeters: Double?,
        nmeaReceivedElapsedRealtime: Long?,
        nowElapsedRealtime: Long,
    ): Double? = when (source) {
        AnchorDepthSource.MANUAL -> manualDepthMeters?.takeIf { it.isFinite() && it >= 0.0 }
        AnchorDepthSource.NMEA -> nmeaDepthMeters?.takeIf {
            nmeaAvailable(connection, it, nmeaReceivedElapsedRealtime, nowElapsedRealtime)
        }
    }
}
