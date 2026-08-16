package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.location.GpsSourceSafety
import com.yokuli.anchorwatch.location.MockGpsState
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsSourceSafetyTest {
    @Test fun activeOrPersistedProxyBlocksSystemGps() {
        assertTrue(GpsSourceSafety.blocksSystemGps(false, MockGpsState.ACTIVE))
        assertTrue(GpsSourceSafety.blocksSystemGps(true, MockGpsState.INACTIVE))
        assertTrue(GpsSourceSafety.blocksSystemGps(false, MockGpsState.STARTING))
    }

    @Test fun fullyStoppedProxyAllowsSystemGps() {
        assertFalse(GpsSourceSafety.blocksSystemGps(false, MockGpsState.INACTIVE))
        assertFalse(GpsSourceSafety.blocksSystemGps(false, MockGpsState.FAILED))
    }

    @Test fun openSessionSourceIsLockedEvenWhilePaused(){
        assertTrue(GpsSourceSafety.allowsSessionSource(false,null,GpsDataSource.NMEA))
        assertTrue(GpsSourceSafety.allowsSessionSource(true,GpsDataSource.SYSTEM,GpsDataSource.SYSTEM))
        assertFalse(GpsSourceSafety.allowsSessionSource(true,GpsDataSource.SYSTEM,GpsDataSource.NMEA))
        assertFalse(GpsSourceSafety.allowsSessionSource(true,GpsDataSource.NMEA,GpsDataSource.SYSTEM))
    }
}
