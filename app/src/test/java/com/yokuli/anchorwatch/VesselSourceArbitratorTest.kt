package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.vessel.*
import com.yokuli.anchorwatch.domain.vessel.source.MetricSourcePreference
import com.yokuli.anchorwatch.domain.vessel.source.VesselSourceArbitrator
import com.yokuli.anchorwatch.domain.vessel.source.VesselSourceConflictPolicy
import org.junit.Assert.*
import org.junit.Test

class VesselSourceArbitratorTest{
    private fun candidate(id:String,type:String,value:Double,at:Long,sourceClass:VesselSourceClass=VesselSourceClass.BOAT_NMEA)=VesselSourceCandidate(
        metric=VesselMetricId.HEADING_TRUE,value=value,
        source=VesselSourceIdentity(id=id,sourceType=if(sourceClass==VesselSourceClass.BOAT_NMEA)VesselSourceType.NMEA_INPUT else VesselSourceType.PHONE_SENSOR,sentenceType=type,displayName=id),
        sourceClass=sourceClass,reference=VesselReference.TrueNorth,receivedElapsedRealtime=at,
    )

    @Test fun hdtHdgAndVhwRemainIndependentAndPacketOrderCannotChooseVhw(){
        val arbitrator=VesselSourceArbitrator();val now=1_000L
        val hdt=candidate("IIHDT","HDT",83.0,now);val hdg=candidate("HCHDG","HDG",82.0,now);val vhw=candidate("SDVHW","VHW",271.0,now)
        val first=arbitrator.select(VesselMetricId.HEADING_TRUE,listOf(vhw,hdg,hdt),MetricSourcePreference(),now)
        assertEquals("IIHDT",first.selected?.source?.id);assertEquals(3,first.candidates.size)
        val interleaved=arbitrator.select(VesselMetricId.HEADING_TRUE,listOf(vhw.copy(receivedElapsedRealtime=1_200),hdt,hdg),MetricSourcePreference(),1_200)
        assertEquals("IIHDT",interleaved.selected?.source?.id)
    }

    @Test fun staleHdtFallsBackButStrictPinNeverDoes(){
        val now=10_000L;val staleHdt=candidate("IIHDT","HDT",83.0,0);val vhw=candidate("SDVHW","VHW",271.0,now)
        val automatic=VesselSourceArbitrator().select(VesselMetricId.HEADING_TRUE,listOf(staleHdt,vhw),MetricSourcePreference(),now)
        assertEquals("SDVHW",automatic.selected?.source?.id)
        val pinned=VesselSourceArbitrator().select(VesselMetricId.HEADING_TRUE,listOf(staleHdt,vhw),MetricSourcePreference(pinnedSourceId="IIHDT"),now)
        assertNull(pinned.selected);assertTrue(pinned.pinnedSourceUnavailable);assertEquals("PINNED_SOURCE_UNAVAILABLE",pinned.reason)
    }

    @Test fun phoneVesselHeadingIsFallbackOnlyAfterBoatCandidatesExpire(){
        val phone=candidate("phone:vessel-heading","PHONE",95.0,8_000,VesselSourceClass.PHONE_VESSEL_HEADING)
        val freshBoat=candidate("IIHDT","HDT",83.0,8_000)
        val arbitrator=VesselSourceArbitrator()
        assertEquals("IIHDT",arbitrator.select(VesselMetricId.HEADING_TRUE,listOf(phone,freshBoat),MetricSourcePreference(),8_000).selected?.source?.id)
        assertEquals("phone:vessel-heading",arbitrator.select(VesselMetricId.HEADING_TRUE,listOf(phone.copy(receivedElapsedRealtime=14_000),freshBoat),MetricSourcePreference(),14_000).selected?.source?.id)
    }

    @Test fun conflictOnlyAppearsAfterSustainedDisagreement(){
        val a=candidate("IIHDT","HDT",10.0,0);val b=candidate("HCHDT","HDT",40.0,0);val arbitrator=VesselSourceArbitrator()
        assertFalse(arbitrator.select(VesselMetricId.HEADING_TRUE,listOf(a,b),MetricSourcePreference(),0).conflict.active)
        val conflict=arbitrator.select(VesselMetricId.HEADING_TRUE,listOf(a.copy(receivedElapsedRealtime=3_100),b.copy(receivedElapsedRealtime=3_100)),MetricSourcePreference(),3_100)
        assertTrue(conflict.conflict.active);assertEquals(1,conflict.conflict.conflictingSources.size)
    }

    @Test fun positionConflictThresholdAccountsForBothGnssAccuracies(){
        val selected=VesselPosition(-36.84,174.76,horizontalAccuracyMeters=20.0)
        val other=VesselPosition(-36.83955,174.76,horizontalAccuracyMeters=20.0)
        val base=VesselSourceConflictPolicy.threshold(VesselMetricId.POSITION)!!.difference
        assertTrue(VesselSourceConflictPolicy.difference(VesselMetricId.POSITION,selected,other)!!>base)
        assertTrue(VesselSourceConflictPolicy.effectiveDifferenceThreshold(VesselMetricId.POSITION,selected,other,base)>80.0)
    }
}
