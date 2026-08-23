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
        assertFalse(GpsSourceSafety.blocksSystemGps(false, MockGpsState.STALE))
    }

    @Test fun everySystemBlockingProxyStateExposesAnExplicitStopAction() {
        MockGpsState.entries.forEach { state ->
            assertTrue(
                GpsSourceSafety.blocksSystemGps(false,state)==
                    GpsSourceSafety.requiresStopAction(false,state),
            )
        }
        assertTrue(GpsSourceSafety.requiresStopAction(true,MockGpsState.INACTIVE))
    }

    @Test fun runningSessionIsLockedButPausedLiveSessionCanRecoverWithoutLifting(){
        assertTrue(GpsSourceSafety.allowsSessionSource(false,false,null,GpsDataSource.NMEA))
        assertTrue(GpsSourceSafety.allowsSessionSource(true,false,GpsDataSource.SYSTEM,GpsDataSource.SYSTEM))
        assertFalse(GpsSourceSafety.allowsSessionSource(true,false,GpsDataSource.SYSTEM,GpsDataSource.NMEA))
        assertTrue(GpsSourceSafety.allowsSessionSource(true,true,GpsDataSource.NMEA,GpsDataSource.SYSTEM))
        assertTrue(GpsSourceSafety.allowsSessionSource(true,true,GpsDataSource.SYSTEM,GpsDataSource.NMEA))
        assertFalse(GpsSourceSafety.allowsSessionSource(true,true,GpsDataSource.DEMO,GpsDataSource.SYSTEM))
        assertFalse(GpsSourceSafety.allowsSessionSource(true,true,GpsDataSource.SYSTEM,GpsDataSource.DEMO))
    }
}
