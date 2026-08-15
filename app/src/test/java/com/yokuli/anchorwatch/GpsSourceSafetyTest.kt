package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.location.GpsSourceSafety
import com.yokuli.anchorwatch.location.MockGpsState
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
}
