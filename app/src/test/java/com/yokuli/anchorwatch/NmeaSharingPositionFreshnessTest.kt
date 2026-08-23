package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.PositionProvider
import com.yokuli.anchorwatch.runtime.sharing.NmeaSharingPositionPublisher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NmeaSharingPositionFreshnessTest {
    private fun fix(received:Long)=NavigationFix(-36.8485,174.7633,1_700_000_000_000,received,positionProvider=PositionProvider.NMEA,sourceSentence="RMC",valid=true)

    @Test fun acceptedFixPublishesImmediatelyAndHeartbeatStopsWhenStale(){
        val publisher=NmeaSharingPositionPublisher()
        val value=fix(1_000)
        assertNotNull(publisher.accept(GpsDataSource.NMEA,value,GpsDataSource.NMEA,1_000))
        assertNull(publisher.tick(GpsDataSource.NMEA,1_899))
        assertEquals(value,publisher.tick(GpsDataSource.NMEA,1_900))
        assertEquals(value,publisher.tick(GpsDataSource.NMEA,2_800))
        assertNull(publisher.tick(GpsDataSource.NMEA,3_501))
    }

    @Test fun seedRequiresFreshMatchingSource(){
        val publisher=NmeaSharingPositionPublisher()
        assertNotNull(publisher.seed(GpsDataSource.SYSTEM,fix(10_000),GpsDataSource.SYSTEM,11_000))
        assertNull(publisher.seed(GpsDataSource.NMEA,fix(10_000),GpsDataSource.SYSTEM,11_000))
        assertNull(publisher.seed(GpsDataSource.SYSTEM,fix(10_000),GpsDataSource.SYSTEM,12_501))
    }

    @Test fun sourceMismatchNeverLeaksPosition(){
        val publisher=NmeaSharingPositionPublisher()
        assertNull(publisher.accept(GpsDataSource.NMEA,fix(5_000),GpsDataSource.SYSTEM,5_000))
        assertNull(publisher.tick(GpsDataSource.SYSTEM,6_000))
    }
}
