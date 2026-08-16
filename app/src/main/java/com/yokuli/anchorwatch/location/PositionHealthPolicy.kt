package com.yokuli.anchorwatch.location

import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.domain.model.PositionHealth
import com.yokuli.anchorwatch.domain.model.PositionProvider

object PositionHealthPolicy {
    fun evaluate(source: GpsDataSource, fix: NavigationFix?, connection: NmeaConnectionState, nowElapsed: Long, lostAfterMillis: Long): PositionHealth {
        if (fix?.valid != true || nowElapsed - fix.receivedElapsedRealtime !in 0 until lostAfterMillis) return PositionHealth.GPS_LOST
        if (source == GpsDataSource.NMEA && connection != NmeaConnectionState.CONNECTED) return PositionHealth.GPS_LOST
        if (fix.positionProvider == PositionProvider.ANDROID_NETWORK || (fix.horizontalAccuracyMeters ?: 0.0) > 30.0 || (fix.hdop ?: 0.0) > 5.0) return PositionHealth.GPS_DEGRADED
        return PositionHealth.GPS_OK
    }
}
