package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.anchor.AnchorDepthSource
import com.yokuli.anchorwatch.domain.anchor.AnchorSetupDepthPolicy
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorSetupDepthPolicyTest {
    @Test fun freshDepthStillRequiresALiveNmeaTransport() {
        assertFalse(AnchorSetupDepthPolicy.nmeaAvailable(NmeaConnectionState.DISCONNECTED, 6.4, 9_000, 10_000))
        assertTrue(AnchorSetupDepthPolicy.nmeaAvailable(NmeaConnectionState.CONNECTED, 6.4, 9_000, 10_000))
    }

    @Test fun depthOnlyNmeaCanServeSystemGpsAnchoringWithoutAnNmeaPositionFix() {
        assertTrue(AnchorSetupDepthPolicy.nmeaAvailable(NmeaConnectionState.CONNECTED_NO_FIX, 7.2, 9_500, 10_000))
        assertTrue(AnchorSetupDepthPolicy.nmeaAvailable(NmeaConnectionState.STALE, 7.2, 9_500, 10_000))
    }

    @Test fun staleNmeaDepthCannotBeSelectedButManualDepthStillWorks() {
        assertNull(AnchorSetupDepthPolicy.selectedDepth(AnchorDepthSource.NMEA, 8.0, NmeaConnectionState.CONNECTED, 6.0, 5_000, 10_000))
        assertEquals(8.0, AnchorSetupDepthPolicy.selectedDepth(AnchorDepthSource.MANUAL, 8.0, NmeaConnectionState.DISCONNECTED, null, null, 10_000) ?: Double.NaN, 0.001)
    }
}
