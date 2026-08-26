package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.condition.LiveWindRepository
import com.yokuli.anchorwatch.data.nmea.*
import com.yokuli.anchorwatch.data.vessel.VesselSourceRegistry
import com.yokuli.anchorwatch.domain.vessel.*
import org.junit.Assert.*
import org.junit.Test

class NmeaSourceInvalidationTest {
    @Test fun invalidStatusMarksOnlyMatchingTransportGenerationAndMetricsInvalid(){
        val registry=VesselSourceRegistry()
        fun source(id:String,generation:Long,full:String)=VesselSourceIdentity(id,"boat",generation,VesselSourceType.NMEA_INPUT,fullSentenceId=full,displayName=full)
        val rmc=source("nmea:boat:4:GPRMC",4,"GPRMC")
        val sameSentenceField=source("nmea:boat:4:field:GP:RMC:7:SOG:",4,"GPRMC")
        val older=source("nmea:boat:3:GPRMC",3,"GPRMC")
        val vtg=source("nmea:boat:4:GPVTG",4,"GPVTG")
        registry.publishAll(listOf(
            VesselSourceCandidate(VesselMetricId.SOG,4.0,rmc,VesselSourceClass.BOAT_NMEA,receivedElapsedRealtime=100),
            VesselSourceCandidate(VesselMetricId.SOG,4.1,sameSentenceField,VesselSourceClass.BOAT_NMEA,receivedElapsedRealtime=100),
            VesselSourceCandidate(VesselMetricId.SOG,3.0,older,VesselSourceClass.BOAT_NMEA,receivedElapsedRealtime=100),
            VesselSourceCandidate(VesselMetricId.SOG,5.0,vtg,VesselSourceClass.BOAT_NMEA,receivedElapsedRealtime=100),
        ))
        registry.invalidate(NmeaSourceInvalidation(rmc.id,setOf(VesselMetricId.SOG),NmeaInvalidationReason.EXPLICIT_INVALID_STATUS,200,"boat",4,"GPRMC"))
        val candidates=registry.candidates<Double>(VesselMetricId.SOG).associateBy{it.source.id}
        assertEquals(CandidateValidity.INVALID,candidates[rmc.id]?.validity)
        assertEquals(CandidateValidity.INVALID,candidates[sameSentenceField.id]?.validity)
        assertEquals(CandidateValidity.ELIGIBLE,candidates[older.id]?.validity)
        assertEquals(CandidateValidity.ELIGIBLE,candidates[vtg.id]?.validity)
    }

    @Test fun invalidMwvImmediatelyClearsLegacyLiveWind(){
        val wind=LiveWindRepository();wind.accept(NmeaUpdate(apparentWindAngle=20.0,apparentWindSpeedKnots=12.0,type="MWV"),100)
        val event=NmeaSourceInvalidation("nmea:boat:1:IIMWV",NmeaInvalidationPolicy.affectedMetrics("MWV"),NmeaInvalidationReason.EXPLICIT_INVALID_STATUS,200,"boat",1,"IIMWV")
        wind.invalidate(event)
        assertNull(wind.state.value.apparentAngle);assertNull(wind.state.value.apparentSpeed)
    }

    @Test fun invalidityPolicyCoversNavigationWindAndGenericStatusSentences(){
        assertEquals(setOf(VesselMetricId.POSITION,VesselMetricId.SOG,VesselMetricId.COG),NmeaInvalidationPolicy.affectedMetrics("RMC"))
        assertEquals(setOf(VesselMetricId.POSITION),NmeaInvalidationPolicy.affectedMetrics("GNS"))
        assertTrue(VesselMetricId.RATE_OF_TURN in NmeaInvalidationPolicy.affectedMetrics("ROT"))
        assertTrue(VesselMetricId.XTE in NmeaInvalidationPolicy.affectedMetrics("APB"))
    }
}
