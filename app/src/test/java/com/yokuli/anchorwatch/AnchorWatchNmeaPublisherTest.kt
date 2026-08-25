package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.sharing.NmeaOutputMux
import com.yokuli.anchorwatch.data.vessel.NmeaDeviceOutputSettings
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.PositionProvider
import com.yokuli.anchorwatch.domain.vessel.*
import com.yokuli.anchorwatch.runtime.output.*
import org.junit.Assert.*
import org.junit.Test

class AnchorWatchNmeaPublisherTest{
    private val mux=NmeaOutputMux()
    private val encoder=AnchorWatchNmeaFeedEncoder(mux)
    private fun heading(value:Double,source:VesselDataSource=VesselDataSource.PHONE_MAGNETOMETER,received:Long=0)=VesselObservation(value,source,receivedElapsedRealtime=received,quality=VesselDataQuality.GOOD,freshness=VesselDataFreshness.HELD,sourceClass=if(source==VesselDataSource.BOAT_NMEA)VesselSourceClass.BOAT_NMEA else VesselSourceClass.PHONE_VESSEL_HEADING)

    @Test fun constantHeadingHasFiveHertzHeartbeatForTenMinutesWithoutBlankSentence(){
        val heartbeat=AnchorWatchNmeaHeartbeat()
        val writes=mutableListOf<Long>()
        for(now in 0L..600_000L step 50L){
            if(AnchorWatchNmeaStream.HEADING in heartbeat.due(now)){
                // The physical sensor heartbeat stays live while the numeric
                // heading remains exactly unchanged.
                val batch=encoder.encode(AnchorWatchNmeaStream.HEADING,VesselDataSnapshot(headingTrueDegrees=heading(123.4,received=now)),NmeaDeviceOutputSettings(),now)
                assertEquals(1,batch.sentences.size);assertTrue(batch.sentences.single().contains("HDT,123.40,T"));assertFalse(batch.sentences.single().contains("HDT,,"));writes+=now
            }
        }
        assertTrue(writes.size in 3_000..3_001)
        assertTrue(writes.zipWithNext().all{(left,right)->right-left<=400L})
    }

    @Test fun boatCandidatesNeverSuppressOrRewriteTheSelectedAnchorWatchHeading(){
        val boatCandidate=VesselSourceCandidate(
            metric=VesselMetricId.HEADING_TRUE,
            value=271.0,
            source=VesselSourceIdentity(
                id="boat-heading",
                sourceType=VesselSourceType.NMEA_INPUT,
                sentenceType="HDT",
                displayName="Boat HDT",
            ),
            sourceClass=VesselSourceClass.BOAT_NMEA,
            receivedElapsedRealtime=0,
        )
        val phoneCandidate=VesselSourceCandidate(metric=VesselMetricId.HEADING_TRUE,value=83.0,source=VesselSourceIdentity("phone-vessel-heading",sourceType=VesselSourceType.PHONE_SENSOR,phoneSensorType="VESSEL_COMPASS",displayName="Phone vessel compass"),sourceClass=VesselSourceClass.PHONE_VESSEL_HEADING,receivedElapsedRealtime=0)
        val snapshot=VesselDataSnapshot(headingTrueDegrees=heading(271.0,VesselDataSource.BOAT_NMEA),candidates=mapOf(VesselMetricId.HEADING_TRUE to listOf(boatCandidate,phoneCandidate)))
        repeat(20){index->
            val output=encoder.encode(AnchorWatchNmeaStream.HEADING,snapshot,NmeaDeviceOutputSettings(),index*200L).sentences.single()
            assertTrue(output.contains("HDT,83.00,T"));assertFalse(output.contains("271.00"))
        }
    }

    @Test fun incompleteMeasurementsSuppressWholeSentences(){
        val empty=VesselDataSnapshot(
            headingTrueDegrees=VesselObservation(null,VesselDataSource.PHONE_MAGNETOMETER,receivedElapsedRealtime=0,freshness=VesselDataFreshness.HELD),
            pressureHpa=VesselObservation(null,VesselDataSource.PHONE_BAROMETER,receivedElapsedRealtime=0,freshness=VesselDataFreshness.HELD),
        )
        AnchorWatchNmeaStream.entries.forEach{assertTrue(encoder.encode(it,empty,NmeaDeviceOutputSettings(),1_000).sentences.isEmpty())}
    }

    @Test fun positionFeedUsesOnlyAcceptedPhoneGnssAndIncludesItsMotion(){
        val phone=NavigationFix(-36.8485,174.7633,1_720_000_000_000,1_000,sogKnots=2.4,cogTrueDegrees=123.4,horizontalAccuracyMeters=4.0,positionProvider=PositionProvider.ANDROID_GNSS,sourceSentence="SYSTEM",valid=true)
        val boatSelected=VesselDataSnapshot(position=VesselObservation(VesselPosition(1.0,2.0),VesselDataSource.BOAT_NMEA,receivedElapsedRealtime=1_000,freshness=VesselDataFreshness.FRESH))
        val output=encoder.encode(AnchorWatchNmeaStream.POSITION,boatSelected,NmeaDeviceOutputSettings(),1_100,phone).sentences
        assertEquals(listOf("RMC","GGA","VTG","ZDA"),output.mapNotNull(mux::sentenceType));assertTrue(output.none{it.contains("0100.00000")})
    }

    @Test fun normalProductFeedNeverPublishesHiddenProprietaryOrRawBoatSentences(){
        val snapshot=VesselDataSnapshot(headingTrueDegrees=heading(45.0))
        val normal=AnchorWatchNmeaStream.entries.flatMap{encoder.encode(it,snapshot,NmeaDeviceOutputSettings(proprietaryStatusEnabled=true),0).sentences}
        assertTrue(normal.isNotEmpty());assertTrue(normal.none{it.contains("PYOK")||it.contains("RAW_BOAT")})
    }

    @Test fun stopInvalidatesEveryOldPublicationGeneration(){
        val gate=NmeaPublicationSessionGate();val first=gate.start();assertTrue(gate.accepts(first))
        gate.stop();assertFalse(gate.accepts(first))
        val second=gate.start();assertFalse(gate.accepts(first));assertTrue(gate.accepts(second));assertNotEquals(first,second)
    }

    @Test fun allOutputsOffEmitsZeroBytesEvenWhenPhoneSensorsStillHaveValues(){
        val gate=NmeaPublicationSessionGate();val generated=encoder.encode(AnchorWatchNmeaStream.HEADING,VesselDataSnapshot(headingTrueDegrees=heading(123.4)),NmeaDeviceOutputSettings(),0).sentences
        assertTrue(generated.isNotEmpty());assertFalse(gate.accepts(gate.current()))
        val socketWrites=generated.filter{gate.accepts(gate.current())};assertTrue(socketWrites.isEmpty())
    }

    @Test fun stopWithQueuedHeadingDropsEveryOldBatchAndRestartNeverReplaysIt(){
        data class Queued(val generation:Long,val value:String)
        val gate=NmeaPublicationSessionGate();val old=gate.start();val queue=LatestPerStreamQueue<Queued>{"HEADING"}
        queue.offer(Queued(old,"old heading"));gate.stop()
        val discarded=queue.poll()!!;assertFalse(gate.accepts(discarded.generation))
        val fresh=gate.start();assertNotEquals(old,fresh);assertNull(queue.poll())
    }
}
