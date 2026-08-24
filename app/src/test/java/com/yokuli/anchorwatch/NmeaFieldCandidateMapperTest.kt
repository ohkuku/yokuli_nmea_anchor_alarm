package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.nmea.*
import com.yokuli.anchorwatch.data.nmea.input.NmeaFieldCandidateMapper
import com.yokuli.anchorwatch.domain.vessel.*
import org.junit.Assert.*
import org.junit.Test

class NmeaFieldCandidateMapperTest{
    @Test fun fieldIdentityIncludesProfileGenerationTalkerSentenceAndField(){
        val field=NmeaFieldObservation(NmeaFieldKey("II","XDR",2,NmeaFieldSemantic.HEEL,"HEEL_SENSOR"),value=8.5,unit="deg",receivedElapsedRealtime=123,rawSentence="x")
        val candidate=NmeaFieldCandidateMapper.map(field,"helm",7)!!
        assertEquals(VesselMetricId.HEEL,candidate.metric);assertEquals(8.5,candidate.value)
        assertEquals("helm",candidate.source.transportProfileId);assertEquals(7L,candidate.source.connectionGeneration)
        assertEquals("II",candidate.source.talkerId);assertEquals("XDR",candidate.source.sentenceType);assertEquals("HEEL_SENSOR",candidate.source.transducerName)
        assertTrue(candidate.source.id.contains("helm:7"));assertTrue(candidate.provenance is VesselProvenance.Nmea)
    }

    @Test fun absentFieldIsNoUpdateAndRawCustomFieldNeverBecomesCanonical(){
        val empty=NmeaFieldObservation(NmeaFieldKey("II","MDA",3,NmeaFieldSemantic.AIR_PRESSURE),receivedElapsedRealtime=1,rawSentence="x")
        val raw=NmeaFieldObservation(NmeaFieldKey("P","ABC",1,NmeaFieldSemantic.RAW),text="value",receivedElapsedRealtime=1,rawSentence="x")
        assertNull(NmeaFieldCandidateMapper.map(empty,"boat",1));assertNull(NmeaFieldCandidateMapper.map(raw,"boat",1))
    }
}
