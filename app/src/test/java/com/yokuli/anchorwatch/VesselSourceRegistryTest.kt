package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.vessel.VesselSourceRegistry
import com.yokuli.anchorwatch.domain.vessel.*
import org.junit.Assert.*
import org.junit.Test

class VesselSourceRegistryTest{
    private fun candidate(profile:String,generation:Long,sentence:String,value:Double,at:Long)=VesselSourceCandidate(
        VesselMetricId.HEADING_TRUE,value,
        VesselSourceIdentity("nmea:$profile:$generation:$sentence",profile,generation,VesselSourceType.NMEA_INPUT,sentenceType=sentence.takeLast(3),fullSentenceId=sentence,displayName=sentence),
        VesselSourceClass.BOAT_NMEA,VesselReference.TrueNorth,at,
    )

    @Test fun registryPreservesEverySourceInsteadOfLastWriterWinning(){
        val registry=VesselSourceRegistry();registry.publishAll(listOf(candidate("boat",4,"IIHDT",83.0,1),candidate("boat",4,"HCHDG",63.5,2),candidate("boat",4,"SDVHW",271.0,3)))
        val values=registry.candidates<Double>(VesselMetricId.HEADING_TRUE)
        assertEquals(3,values.size);assertEquals(setOf("IIHDT","HCHDG","SDVHW"),values.mapNotNull{it.source.fullSentenceId}.toSet())
    }

    @Test fun reconnectGenerationEvictsOnlyOlderInstancesOfThatTransport(){
        val registry=VesselSourceRegistry();registry.publishAll(listOf(candidate("boat",3,"IIHDT",80.0,1),candidate("boat",4,"IIHDT",81.0,2),candidate("backup",2,"HCHDT",82.0,3)))
        registry.clearTransportGeneration("boat",4)
        val ids=registry.candidates<Double>(VesselMetricId.HEADING_TRUE).map{it.source.id}
        assertTrue(ids.any{it.contains("boat:4")});assertFalse(ids.any{it.contains("boat:3")});assertTrue(ids.any{it.contains("backup:2")})
    }

    @Test fun mountLossCanRemoveOnlyVesselFramePhoneSources(){
        val registry=VesselSourceRegistry()
        val phoneHeading=VesselSourceIdentity("phone:vessel-heading",sourceType=VesselSourceType.PHONE_SENSOR,displayName="Phone vessel heading")
        val phoneGps=VesselSourceIdentity("phone:gnss",sourceType=VesselSourceType.PHONE_SENSOR,displayName="Phone GNSS")
        registry.publish(VesselSourceCandidate(VesselMetricId.HEADING_TRUE,20.0,phoneHeading,VesselSourceClass.PHONE_VESSEL_HEADING,receivedElapsedRealtime=1))
        registry.publish(VesselSourceCandidate(VesselMetricId.POSITION,VesselPosition(1.0,2.0),phoneGps,VesselSourceClass.PHONE_GNSS,receivedElapsedRealtime=1))

        registry.removeSources(setOf(phoneHeading.id))

        assertTrue(registry.candidates<Double>(VesselMetricId.HEADING_TRUE).isEmpty())
        assertEquals(1,registry.candidates<VesselPosition>(VesselMetricId.POSITION).size)
    }
}
