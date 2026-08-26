package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.model.*
import com.yokuli.anchorwatch.location.PositionHealthPolicy
import com.yokuli.anchorwatch.location.NmeaFixQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class PositionHealthPolicyTest {
    @Test fun distinguishesHealthyDegradedAndLostWithoutSwitchingSource(){
        val fix=NavigationFix(1.0,2.0,receivedElapsedRealtime=1_000,horizontalAccuracyMeters=4.0,positionProvider=PositionProvider.ANDROID_GNSS,sourceSentence="x",valid=true)
        assertEquals(PositionHealth.GPS_OK,PositionHealthPolicy.evaluate(GpsDataSource.SYSTEM,fix,NmeaConnectionState.DISCONNECTED,2_000,15_000))
        assertEquals(PositionHealth.GPS_DEGRADED,PositionHealthPolicy.evaluate(GpsDataSource.SYSTEM,fix.copy(positionProvider=PositionProvider.ANDROID_NETWORK),NmeaConnectionState.DISCONNECTED,2_000,15_000))
        assertEquals(PositionHealth.GPS_LOST,PositionHealthPolicy.evaluate(GpsDataSource.SYSTEM,fix,NmeaConnectionState.DISCONNECTED,20_000,15_000))
    }


    @Test fun unknownNmeaQualityIsDegradedButMayContinue(){
        val unknown=NavigationFix(1.0,2.0,receivedElapsedRealtime=1_000,positionProvider=PositionProvider.NMEA,sourceSentence="RMC",valid=true)
        assertEquals(PositionHealth.GPS_DEGRADED,PositionHealthPolicy.evaluate(GpsDataSource.NMEA,unknown,NmeaConnectionState.CONNECTED,2_000,15_000))
        assertEquals(true,NmeaFixQualityPolicy.allowsContinuation(unknown))
        assertEquals(false,NmeaFixQualityPolicy.allowsContinuation(unknown.copy(hdop=7.0)))
        assertEquals(false,NmeaFixQualityPolicy.allowsContinuation(unknown.copy(fixQuality=0)))
    }

    @Test fun anOldBadGgaQualityDoesNotPoisonANewerValidRmcPosition(){
        val rmc=NavigationFix(
            1.0,2.0,
            receivedElapsedRealtime=10_000,
            hdop=7.0,
            fixQuality=1,
            hdopReceivedElapsedRealtime=1_000,
            fixQualityReceivedElapsedRealtime=1_000,
            positionProvider=PositionProvider.NMEA,
            sourceSentence="RMC",
            valid=true,
        )
        assertEquals(true,NmeaFixQualityPolicy.allowsContinuation(rmc,10_000))
        assertEquals(false,NmeaFixQualityPolicy.allowsContinuation(rmc.copy(hdopReceivedElapsedRealtime=9_500),10_000))
    }
}
