package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.vessel.*
import org.junit.Assert.assertEquals
import org.junit.Test

class DepthHoldLastValueTest {
    @Test fun omittedDepthRetainsValueAndTimestampWhileFreshnessChanges(){
        val measured=VesselObservation(6.1,VesselDataSource.BOAT_NMEA,receivedElapsedRealtime=1_000,quality=VesselDataQuality.GOOD)
        val afterPositionOnly=VesselFreshnessPolicy.retain(measured,null)
        assertEquals(6.1,afterPositionOnly.value)
        assertEquals(1_000L,afterPositionOnly.receivedElapsedRealtime)
        assertEquals(VesselDataFreshness.HELD,VesselFreshnessPolicy.classify(afterPositionOnly,31_000,3_000,60_000).freshness)
        assertEquals(VesselDataFreshness.STALE,VesselFreshnessPolicy.classify(afterPositionOnly,62_000,3_000,60_000).freshness)
    }

    @Test fun newRealDepthReplacesValueAndReceiveTime(){
        val previous=VesselObservation(6.1,VesselDataSource.BOAT_NMEA,receivedElapsedRealtime=1_000)
        val next=VesselObservation(6.2,VesselDataSource.BOAT_NMEA,receivedElapsedRealtime=31_000)
        assertEquals(next,VesselFreshnessPolicy.retain(previous,next))
    }

    @Test fun theSameNullMeansNoUpdateRuleAppliesToEveryInstrumentField(){
        val previous=VesselObservation(4.7,VesselDataSource.BOAT_NMEA,receivedElapsedRealtime=2_000,provenance="VHW STW")
        val held=VesselFreshnessPolicy.retain(previous,null)
        assertEquals(previous,held)
        assertEquals(VesselDataFreshness.HELD,VesselFreshnessPolicy.classify(held,20_000,5_000,60_000).freshness)
    }
}
