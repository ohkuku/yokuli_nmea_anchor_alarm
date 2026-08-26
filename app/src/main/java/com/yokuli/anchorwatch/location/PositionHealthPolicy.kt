package com.yokuli.anchorwatch.location

import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.domain.model.PositionHealth
import com.yokuli.anchorwatch.domain.model.PositionProvider

object PositionHealthPolicy {
    fun evaluate(source: GpsDataSource, fix: NavigationFix?, connection: NmeaConnectionState, nowElapsed: Long, lostAfterMillis: Long): PositionHealth {
        if (fix?.valid != true || nowElapsed - fix.receivedElapsedRealtime !in 0 until lostAfterMillis) return PositionHealth.GPS_LOST
        if (source == GpsDataSource.NMEA && !NmeaSourceSelectionPolicy.hasLiveTransportState(connection)) return PositionHealth.GPS_LOST
        val currentHdop=fix.hdop.takeIf{fix.hdopReceivedElapsedRealtime?.let{received->nowElapsed-received in 0..NmeaFixQualityPolicy.QUALITY_FRESH_MILLIS}?:true}
        if (source == GpsDataSource.NMEA && fix.horizontalAccuracyMeters == null && currentHdop == null) return PositionHealth.GPS_DEGRADED
        if (fix.positionProvider == PositionProvider.ANDROID_NETWORK || (fix.horizontalAccuracyMeters ?: 0.0) > 30.0 || (currentHdop ?: 0.0) > 5.0) return PositionHealth.GPS_DEGRADED
        return PositionHealth.GPS_OK
    }
}

/** Missing NMEA quality is allowed with a warning; explicitly bad quality is not. */
object NmeaFixQualityPolicy{
    const val QUALITY_FRESH_MILLIS=5_000L
    fun allowsContinuation(fix:NavigationFix?,nowElapsedRealtime:Long?=null):Boolean{
        if(fix==null)return false
        fun current(received:Long?)=nowElapsedRealtime==null||received==null||nowElapsedRealtime-received in 0..QUALITY_FRESH_MILLIS
        val quality=fix.fixQuality.takeIf{current(fix.fixQualityReceivedElapsedRealtime)}
        val hdop=fix.hdop.takeIf{current(fix.hdopReceivedElapsedRealtime)}
        return (quality==null||quality>0)&&(hdop==null||hdop<=5.0)
    }
}
