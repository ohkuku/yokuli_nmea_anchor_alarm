package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.nmea.NmeaUpdate
import com.yokuli.anchorwatch.data.nmea.input.NmeaCandidateMapper
import com.yokuli.anchorwatch.data.nmea.input.ParsedNmeaEnvelope
import com.yokuli.anchorwatch.domain.vessel.*
import org.junit.Assert.*
import org.junit.Test

class VesselSourceStablePinTest{
    private fun identity(generation:Long)=VesselSourceIdentity(
        id="nmea:boat-primary:$generation:IIHDT",transportProfileId="boat-primary",connectionGeneration=generation,
        sourceType=VesselSourceType.NMEA_INPUT,sentenceType="HDT",fullSentenceId="IIHDT",displayName="IIHDT",
        stableKey="nmea:boat-primary:IIHDT",
    )

    @Test fun reconnectChangesRuntimeIdButNotPhysicalSourceKey(){
        val before=identity(8);val after=identity(9)
        assertNotEquals(before.id,after.id)
        assertEquals(before.persistentKey,after.persistentKey)
        assertTrue(VesselSourcePinPolicy.matches(after,before.persistentKey))
    }

    @Test fun legacyGenerationBearingPinMigratesDuringResolution(){
        val after=identity(9);val candidate=VesselSourceCandidate(VesselMetricId.HEADING_TRUE,42.0,after,VesselSourceClass.BOAT_NMEA,receivedElapsedRealtime=100)
        assertEquals(after.id,VesselSourcePinPolicy.resolve(listOf(candidate),"nmea:boat-primary:8:IIHDT"))
    }

    @Test fun mapperBuildsStableKeyWithoutConnectionGeneration(){
        val envelope=ParsedNmeaEnvelope("\$IIHDT,42.0,T","II","HDT","IIHDT",100,NmeaUpdate(trueHeading=42.0,type="HDT"))
        val first=NmeaCandidateMapper.map(envelope,"boat-primary",1).single().source
        val second=NmeaCandidateMapper.map(envelope,"boat-primary",2).single().source
        assertNotEquals(first.id,second.id)
        assertEquals("nmea:boat-primary:IIHDT",first.persistentKey)
        assertEquals(first.persistentKey,second.persistentKey)
    }
}
