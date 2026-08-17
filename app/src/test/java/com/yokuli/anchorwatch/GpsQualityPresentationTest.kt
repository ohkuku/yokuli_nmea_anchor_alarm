package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NavigationFix
import org.junit.Assert.assertEquals
import org.junit.Test

class GpsQualityPresentationTest{
    private val fix=NavigationFix(latitude=-36.8,longitude=174.7,valid=true,receivedElapsedRealtime=1,hdop=.8,horizontalAccuracyMeters=6.2,sourceSentence="TEST")

    @Test fun systemGpsShowsAndroidAccuracyNotMeaninglessHdop(){
        assertEquals("GPS" to "±6 m",gpsQualityMetric(GpsDataSource.SYSTEM,fix))
    }

    @Test fun nmeaGpsShowsNativeHdop(){
        assertEquals("HDOP" to "0.8",gpsQualityMetric(GpsDataSource.NMEA,fix))
    }
}
