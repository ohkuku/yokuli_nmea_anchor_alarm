package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.location.PositionFaultEpisodeGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionFaultEpisodeGateTest {
    @Test fun repeatedBadFixesCreateOneDurableIncidentPerEpisode(){
        val gate=PositionFaultEpisodeGate()
        assertTrue(gate.shouldRecord("REJECTED","BAD_HDOP"))
        assertFalse(gate.shouldRecord("REJECTED","BAD_HDOP"))
        assertFalse(gate.shouldRecord("ACCEPTED",null))
        assertTrue(gate.shouldRecord("REJECTED","BAD_HDOP"))
    }

    @Test fun aChangedFailureReasonIsUsefulNewEvidence(){
        val gate=PositionFaultEpisodeGate()
        assertTrue(gate.shouldRecord("QUARANTINED","POSITION_SPIKE"))
        assertTrue(gate.shouldRecord("REJECTED","NO_FIX_QUALITY"))
        assertFalse(gate.shouldRecord("REJECTED","NO_FIX_QUALITY"))
    }
}
