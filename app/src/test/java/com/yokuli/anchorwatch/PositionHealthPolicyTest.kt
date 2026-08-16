package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.model.*
import com.yokuli.anchorwatch.location.PositionHealthPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class PositionHealthPolicyTest {
    @Test fun distinguishesHealthyDegradedAndLostWithoutSwitchingSource(){
        val fix=NavigationFix(1.0,2.0,receivedElapsedRealtime=1_000,horizontalAccuracyMeters=4.0,positionProvider=PositionProvider.ANDROID_GNSS,sourceSentence="x",valid=true)
        assertEquals(PositionHealth.GPS_OK,PositionHealthPolicy.evaluate(GpsDataSource.SYSTEM,fix,NmeaConnectionState.DISCONNECTED,2_000,15_000))
        assertEquals(PositionHealth.GPS_DEGRADED,PositionHealthPolicy.evaluate(GpsDataSource.SYSTEM,fix.copy(positionProvider=PositionProvider.ANDROID_NETWORK),NmeaConnectionState.DISCONNECTED,2_000,15_000))
        assertEquals(PositionHealth.GPS_LOST,PositionHealthPolicy.evaluate(GpsDataSource.SYSTEM,fix,NmeaConnectionState.DISCONNECTED,20_000,15_000))
    }
}
