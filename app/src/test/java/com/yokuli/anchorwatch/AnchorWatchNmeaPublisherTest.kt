package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.sharing.NmeaOutputMux
import com.yokuli.anchorwatch.data.nmea.output.NmeaOutputEndpointPolicy
import com.yokuli.anchorwatch.data.vessel.NmeaDeviceOutputSettings
import com.yokuli.anchorwatch.data.vessel.NmeaOutputTransportMode
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.PositionProvider
import com.yokuli.anchorwatch.domain.vessel.*
import com.yokuli.anchorwatch.runtime.output.*
import com.yokuli.anchorwatch.location.vessel.PhoneVesselMountState
import com.yokuli.anchorwatch.location.vessel.VesselMountCalibration
import org.junit.Assert.*
import org.junit.Test

class AnchorWatchNmeaPublisherTest{
    private val mux=NmeaOutputMux()
    private val encoder=AnchorWatchNmeaFeedEncoder(mux)
    private val dedicatedDestination=NmeaDeviceOutputSettings(phonePositionEnabled=true,phoneHeadingEnabled=true,phonePressureEnabled=true,includePressure=true,derivedWindPolicy=PublicationPolicy.ALWAYS,includeDerivedWind=true,transportMode=NmeaOutputTransportMode.DEDICATED_TCP)
    private val aligned=VesselMountCalibration(version=3,calibratedAt=1,mountState=PhoneVesselMountState.VESSEL_MOUNTED,mountConfirmedVersion=3,headingAlignmentCompletedAt=2,headingAlignmentVersion=3)
    private fun heading(value:Double,source:VesselDataSource=VesselDataSource.PHONE_MAGNETOMETER,received:Long=0):VesselObservation<Double>{
        val phone=source!=VesselDataSource.BOAT_NMEA
        val identity=if(phone)VesselSourceIdentity("phone:vessel-heading",sourceType=VesselSourceType.PHONE_SENSOR,phoneSensorType="VESSEL_COMPASS",displayName="Phone vessel heading")else null
        return VesselObservation(value,source,receivedElapsedRealtime=received,quality=VesselDataQuality.GOOD,freshness=VesselDataFreshness.FRESH,sourceIdentity=identity,sourceClass=if(phone)VesselSourceClass.PHONE_VESSEL_HEADING else VesselSourceClass.BOAT_NMEA,provenanceDetail=if(phone)VesselProvenance.PhoneSensor("Mounted phone heading",3)else null)
    }
    private fun encodeHeading(snapshot:VesselDataSnapshot,now:Long,settings:NmeaDeviceOutputSettings=dedicatedDestination,inputGeneration:Long?=null,inputProfile:String?=null)=encoder.encode(AnchorWatchNmeaStream.HEADING,snapshot,settings,now,inputTransportGeneration=inputGeneration,inputProfileId=inputProfile,mountCalibration=aligned,runtimeMountState=PhoneVesselMountState.VESSEL_MOUNTED)

    @Test fun everyStreamUsesOneHertzAndConstantHeadingNeverBecomesBlank(){
        assertTrue(AnchorWatchNmeaStream.entries.all{it.periodMillis==1_000L})
        val heartbeat=AnchorWatchNmeaHeartbeat()
        val writes=mutableListOf<Long>()
        for(now in 0L..600_000L step 50L){
            if(AnchorWatchNmeaStream.HEADING in heartbeat.due(now)){
                // The physical sensor heartbeat stays live while the numeric
                // heading remains exactly unchanged.
                val batch=encodeHeading(VesselDataSnapshot(headingTrueDegrees=heading(123.4,received=now)),now)
                assertEquals(1,batch.sentences.size);assertTrue(batch.sentences.single().contains("HDT,123.40,T"));assertFalse(batch.sentences.single().contains("HDT,,"));writes+=now
            }
        }
        assertTrue(writes.size in 600..601)
        assertTrue(writes.zipWithNext().all{(left,right)->right-left<=1_200L})
    }

    @Test fun slowGatewayGetsNoCatchUpBurstAfterAWriteReturns(){
        assertEquals(11_000L,NmeaWireAttemptCadence.nextAllowed(startedAt=10_000,completedAt=10_020))
        assertEquals(13_500L,NmeaWireAttemptCadence.nextAllowed(startedAt=10_000,completedAt=12_500))
    }

    @Test fun boatHeadingIsBlockedOnEveryTransportAndPhoneCandidateWins(){
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
        val phoneCandidate=VesselSourceCandidate(metric=VesselMetricId.HEADING_TRUE,value=83.0,source=VesselSourceIdentity("phone-vessel-heading",sourceType=VesselSourceType.PHONE_SENSOR,phoneSensorType="VESSEL_COMPASS",displayName="Phone vessel compass"),sourceClass=VesselSourceClass.PHONE_VESSEL_HEADING,receivedElapsedRealtime=0,provenance=VesselProvenance.PhoneSensor("Mounted phone heading",3))
        val snapshot=VesselDataSnapshot(
            headingTrueDegrees=heading(271.0,VesselDataSource.BOAT_NMEA).copy(
                sourceIdentity=boatCandidate.source,
                sourceClass=VesselSourceClass.BOAT_NMEA,
            ),
            candidates=mapOf(VesselMetricId.HEADING_TRUE to listOf(boatCandidate,phoneCandidate)),
        )
        val injected=encodeHeading(snapshot,0,inputGeneration=1,inputProfile="boat")
        assertEquals(1,injected.sentences.size);assertTrue(injected.sentences.single().contains("HDT,83.00,T"));assertTrue(injected.sourceConflict)
        encoder.reset()
        val output=encodeHeading(snapshot,0).sentences.single()
        assertTrue(output.contains("HDT,83.00,T"));assertTrue(output.startsWith("\$IIHDT"))
    }

    @Test fun selectedBoatValueFromOldInputGenerationCannotCrossReconnect(){
        fun snapshot(generation:Long,value:Double):VesselDataSnapshot{
            val source=VesselSourceIdentity(id="nmea:boat:$generation:IIHDT",transportProfileId="boat",connectionGeneration=generation,sourceType=VesselSourceType.NMEA_INPUT,sentenceType="HDT",displayName="Boat HDT",stableKey="nmea:boat:IIHDT")
            val candidate=VesselSourceCandidate(VesselMetricId.HEADING_TRUE,value,source,VesselSourceClass.BOAT_NMEA,receivedElapsedRealtime=1_000)
            return VesselDataSnapshot(headingTrueDegrees=VesselObservation(value,VesselDataSource.BOAT_NMEA,receivedElapsedRealtime=1_000,quality=VesselDataQuality.GOOD,freshness=VesselDataFreshness.FRESH,sourceIdentity=source,sourceClass=VesselSourceClass.BOAT_NMEA),candidates=mapOf(VesselMetricId.HEADING_TRUE to listOf(candidate)))
        }
        assertTrue(encoder.encode(AnchorWatchNmeaStream.HEADING,snapshot(1,83.0),NmeaDeviceOutputSettings(),1_000,inputTransportGeneration=1).sentences.isEmpty())
        assertTrue("A stale VesselDataHub snapshot must not leak onto transport 2",encoder.encode(AnchorWatchNmeaStream.HEADING,snapshot(1,83.0),NmeaDeviceOutputSettings(),1_200,inputTransportGeneration=2).sentences.isEmpty())
        assertTrue(encoder.encode(AnchorWatchNmeaStream.HEADING,snapshot(2,95.0),NmeaDeviceOutputSettings(),1_400,inputTransportGeneration=2).sentences.isEmpty())
    }

    @Test fun numericHeading_thenBlankHeartbeats_stopsInsteadOfPublishingHeldHdt(){
        val source=VesselSourceIdentity("phone-heading",sourceType=VesselSourceType.PHONE_SENSOR,phoneSensorType="VESSEL_COMPASS",displayName="Phone heading")
        repeat(2){index->
            val now=index*200L
            val held=VesselObservation(
                value=123.4,
                source=VesselDataSource.PHONE_MAGNETOMETER,
                receivedElapsedRealtime=0L,
                sourceHeartbeatElapsedRealtime=now,
                quality=VesselDataQuality.GOOD,
                freshness=if(now==0L)VesselDataFreshness.FRESH else VesselDataFreshness.HELD,
                sourceIdentity=source,
                sourceClass=VesselSourceClass.PHONE_VESSEL_HEADING,
                provenanceDetail=VesselProvenance.PhoneSensor("Mounted phone heading",3),
            )
            val candidate=VesselSourceCandidate(
                metric=VesselMetricId.HEADING_TRUE,
                value=123.4,
                source=source,
                sourceClass=VesselSourceClass.PHONE_VESSEL_HEADING,
                receivedElapsedRealtime=0L,
                sourceHeartbeatElapsedRealtime=now,
                provenance=VesselProvenance.PhoneSensor("Mounted phone heading",3),
            )
            val batch=encodeHeading(VesselDataSnapshot(headingTrueDegrees=held,candidates=mapOf(VesselMetricId.HEADING_TRUE to listOf(candidate))),now)
            if(index==0)assertTrue(batch.sentences.single().contains("HDT,123.40,T")) else assertTrue(batch.sentences.isEmpty())
        }
    }

    @Test fun headingExplicitInvalid_stopsImmediatelyAndClearsThePublisherLease(){
        val source=VesselSourceIdentity("phone-heading",sourceType=VesselSourceType.PHONE_SENSOR,phoneSensorType="VESSEL_COMPASS",displayName="Phone heading")
        val validCandidate=VesselSourceCandidate(VesselMetricId.HEADING_TRUE,123.4,source,VesselSourceClass.PHONE_VESSEL_HEADING,receivedElapsedRealtime=1_000,provenance=VesselProvenance.PhoneSensor("Mounted phone heading",3))
        val valid=VesselDataSnapshot(
            headingTrueDegrees=VesselObservation(123.4,VesselDataSource.PHONE_MAGNETOMETER,receivedElapsedRealtime=1_000,quality=VesselDataQuality.GOOD,freshness=VesselDataFreshness.FRESH,sourceIdentity=source,sourceClass=VesselSourceClass.PHONE_VESSEL_HEADING,provenanceDetail=VesselProvenance.PhoneSensor("Mounted phone heading",3)),
            candidates=mapOf(VesselMetricId.HEADING_TRUE to listOf(validCandidate)),
        )
        assertTrue(encodeHeading(valid,1_000).sentences.isNotEmpty())
        val invalid=VesselDataSnapshot(
            candidates=mapOf(VesselMetricId.HEADING_TRUE to listOf(validCandidate.copy(validity=CandidateValidity.INVALID,sourceHeartbeatElapsedRealtime=1_200))),
        )
        assertTrue(encodeHeading(invalid,1_200).sentences.isEmpty())
    }

    @Test fun readyHeadingSourceSwitchIsAtomicAndCreatesNoEmptyTick(){
        fun snapshot(id:String,value:Double,now:Long):VesselDataSnapshot{
            val source=VesselSourceIdentity(id,sourceType=VesselSourceType.PHONE_SENSOR,phoneSensorType="VESSEL_COMPASS",displayName=id,stableKey=id)
            val candidate=VesselSourceCandidate(VesselMetricId.HEADING_TRUE,value,source,VesselSourceClass.PHONE_VESSEL_HEADING,receivedElapsedRealtime=now,provenance=VesselProvenance.PhoneSensor("Mounted phone heading",3))
            return VesselDataSnapshot(
                headingTrueDegrees=VesselObservation(value,VesselDataSource.PHONE_MAGNETOMETER,receivedElapsedRealtime=now,quality=VesselDataQuality.GOOD,freshness=VesselDataFreshness.FRESH,sourceIdentity=source,sourceClass=VesselSourceClass.PHONE_VESSEL_HEADING,provenanceDetail=VesselProvenance.PhoneSensor("Mounted phone heading",3)),
                candidates=mapOf(VesselMetricId.HEADING_TRUE to listOf(candidate)),
            )
        }
        val old=encodeHeading(snapshot("phone:vessel-heading:a",83.0,1_000),1_000)
        val switched=encodeHeading(snapshot("phone:vessel-heading:b",95.0,1_200),1_200)
        assertEquals(1,old.sentences.size);assertEquals(1,switched.sentences.size)
        assertTrue(old.sentences.single().contains("83.00"));assertTrue(switched.sentences.single().contains("95.00"))
        assertNotEquals(old.sourceStableKey,switched.sourceStableKey)
    }

    @Test fun headingNeverBridgesAQuietSource(){
        val complete=VesselDataSnapshot(headingTrueDegrees=heading(123.4,received=1_000).copy(freshness=VesselDataFreshness.FRESH))
        assertTrue(encodeHeading(complete,1_000).sentences.isNotEmpty())
        assertTrue(encodeHeading(VesselDataSnapshot(),1_200).sentences.isEmpty())
    }

    @Test fun incompleteMeasurementsSuppressWholeSentences(){
        val empty=VesselDataSnapshot(
            headingTrueDegrees=VesselObservation(null,VesselDataSource.PHONE_MAGNETOMETER,receivedElapsedRealtime=0,freshness=VesselDataFreshness.HELD),
            pressureHpa=VesselObservation(null,VesselDataSource.PHONE_BAROMETER,receivedElapsedRealtime=0,freshness=VesselDataFreshness.HELD),
        )
        AnchorWatchNmeaStream.entries.forEach{assertTrue(encoder.encode(it,empty,NmeaDeviceOutputSettings(),1_000).sentences.isEmpty())}
    }

    @Test fun boatDepthAndStwAreNotPublisherStreamsOnAnyTransport(){
        val depthSource=VesselSourceIdentity("boat-depth",sourceType=VesselSourceType.NMEA_INPUT,sentenceType="DBT",displayName="Boat DBT")
        val stwSource=VesselSourceIdentity("boat-stw",sourceType=VesselSourceType.NMEA_INPUT,sentenceType="VHW",displayName="Boat VHW")
        val snapshot=VesselDataSnapshot(
            depthMeters=VesselObservation(8.2,VesselDataSource.BOAT_NMEA,receivedElapsedRealtime=1_000,sourceIdentity=depthSource,sourceClass=VesselSourceClass.BOAT_NMEA),
            speedThroughWaterKnots=VesselObservation(4.1,VesselDataSource.BOAT_NMEA,receivedElapsedRealtime=1_000,sourceIdentity=stwSource,sourceClass=VesselSourceClass.BOAT_NMEA),
        )
        assertEquals(setOf("POSITION","HEADING","RATE_OF_TURN","ATTITUDE","PRESSURE","DERIVED_WIND"),AnchorWatchNmeaStream.entries.map{it.name}.toSet())
        val output=AnchorWatchNmeaStream.entries.flatMap{encoder.encode(it,snapshot,dedicatedDestination,1_000).sentences}
        assertTrue(output.none{mux.sentenceType(it) in setOf("DBT","DPT","VHW")})
    }

    @Test fun transportChoiceNeverTurnsBoatInputIntoOutput(){
        val boatHeading=VesselSourceIdentity("boat-heading",sourceType=VesselSourceType.NMEA_INPUT,sentenceType="HDT",displayName="Boat HDT")
        val boatPressure=VesselSourceIdentity("boat-pressure",sourceType=VesselSourceType.NMEA_INPUT,sentenceType="MDA",displayName="Boat MDA")
        val boatWind=VesselSourceIdentity("boat-wind",sourceType=VesselSourceType.NMEA_INPUT,sentenceType="MWD",displayName="Boat MWD")
        fun boat(value:Double,source:VesselSourceIdentity)=VesselObservation(value,VesselDataSource.BOAT_NMEA,receivedElapsedRealtime=1_000,freshness=VesselDataFreshness.FRESH,sourceIdentity=source,sourceClass=VesselSourceClass.BOAT_NMEA,provenanceDetail=VesselProvenance.Nmea(source))
        val snapshot=VesselDataSnapshot(
            headingTrueDegrees=boat(271.0,boatHeading),
            pressureHpa=boat(1008.0,boatPressure),
            trueWind=VesselWindObservation(boat(14.0,boatWind),boat(220.0,boatWind),boat(35.0,boatWind)),
        )
        NmeaOutputTransportMode.entries.forEach{mode->
            encoder.reset()
            val output=AnchorWatchNmeaStream.entries.flatMap{stream->
                encoder.encode(stream,snapshot,NmeaDeviceOutputSettings(transportMode=mode),1_000,phoneFix=null).sentences
            }
            assertTrue("Boat input leaked through $mode: $output",output.isEmpty())
        }
    }

    @Test fun selectedBoatRotIsNeverRepublishedWithoutPhoneAttitude(){
        val source=VesselSourceIdentity("boat-rot",sourceType=VesselSourceType.NMEA_INPUT,sentenceType="ROT",displayName="Boat ROT")
        val snapshot=VesselDataSnapshot(
            rateOfTurnDegreesPerMinute=VesselObservation(18.5,VesselDataSource.BOAT_NMEA,receivedElapsedRealtime=1_000,sourceHeartbeatElapsedRealtime=1_000,quality=VesselDataQuality.GOOD,freshness=VesselDataFreshness.FRESH,sourceIdentity=source,sourceClass=VesselSourceClass.BOAT_NMEA),
            candidates=mapOf(VesselMetricId.RATE_OF_TURN to listOf(VesselSourceCandidate(VesselMetricId.RATE_OF_TURN,18.5,source,VesselSourceClass.BOAT_NMEA,receivedElapsedRealtime=1_000))),
        )
        assertTrue(encoder.encode(AnchorWatchNmeaStream.RATE_OF_TURN,snapshot,NmeaDeviceOutputSettings(phoneRateOfTurnEnabled=true),1_000).sentences.isEmpty())
        assertTrue(encoder.encode(AnchorWatchNmeaStream.ATTITUDE,snapshot,dedicatedDestination.copy(phoneAttitudeEnabled=true),1_000).sentences.isEmpty())
    }

    @Test fun independentPositionFeedUsesOnlyTheAtomicPhoneFix(){
        val phone=NavigationFix(-36.8485,174.7633,1_720_000_000_000,1_000,sogKnots=2.4,cogTrueDegrees=123.4,horizontalAccuracyMeters=4.0,positionProvider=PositionProvider.ANDROID_GNSS,sourceSentence="SYSTEM",valid=true)
        val boatSelected=VesselDataSnapshot(
            position=VesselObservation(VesselPosition(-36.9,174.8,horizontalAccuracyMeters=4.0),VesselDataSource.BOAT_NMEA,receivedElapsedRealtime=1_000,freshness=VesselDataFreshness.FRESH),
            sogKnots=VesselObservation(3.2,VesselDataSource.BOAT_NMEA,receivedElapsedRealtime=1_000,freshness=VesselDataFreshness.FRESH),
            cogTrueDegrees=VesselObservation(210.0,VesselDataSource.BOAT_NMEA,receivedElapsedRealtime=1_000,freshness=VesselDataFreshness.FRESH),
        )
        val output=encoder.encode(AnchorWatchNmeaStream.POSITION,boatSelected,dedicatedDestination,1_100,phone).sentences
        assertEquals(listOf("RMC","GGA","VTG","ZDA"),output.mapNotNull(mux::sentenceType))
        assertTrue(output.any{it.contains("3650.91000,S")&&it.contains("17445.79800,E")})
        assertTrue(output.any{it.contains("123.40,T")&&it.contains("2.40,N")})
        assertTrue(output.none{it.contains("3654.00000,S")||it.contains("210.00,T")||it.contains("3.20,N")})
    }

    @Test fun validPhoneFixIsNotEncodedUntilPhonePositionPublicationIsExplicitlyEnabled(){
        val phone=NavigationFix(-36.8485,174.7633,1_720_000_000_000,1_000,horizontalAccuracyMeters=4.0,positionProvider=PositionProvider.ANDROID_GNSS,sourceSentence="SYSTEM",valid=true)
        val disabled=encoder.encode(AnchorWatchNmeaStream.POSITION,VesselDataSnapshot(),NmeaDeviceOutputSettings(),1_100,phone)
        assertTrue(disabled.sentences.isEmpty())
        assertEquals("USER_DISABLED",disabled.suppressionReason)
        val enabled=encoder.encode(AnchorWatchNmeaStream.POSITION,VesselDataSnapshot(),NmeaDeviceOutputSettings(phonePositionEnabled=true),1_100,phone)
        assertEquals(listOf("RMC","GGA","ZDA"),enabled.sentences.mapNotNull(mux::sentenceType))
    }

    @Test fun mockOrNetworkPositionCanNeverEnterPhoneOutput(){
        val base=NavigationFix(-36.8485,174.7633,1_720_000_000_000,1_000,horizontalAccuracyMeters=4.0,positionProvider=PositionProvider.ANDROID_GNSS,sourceSentence="SYSTEM",valid=true)
        val enabled=NmeaDeviceOutputSettings(phonePositionEnabled=true)
        assertTrue(encoder.encode(AnchorWatchNmeaStream.POSITION,VesselDataSnapshot(),enabled,1_100,base.copy(isMockLocation=true)).sentences.isEmpty())
        assertTrue(encoder.encode(AnchorWatchNmeaStream.POSITION,VesselDataSnapshot(),enabled,1_100,base.copy(positionProvider=PositionProvider.ANDROID_NETWORK)).sentences.isEmpty())
    }

    @Test fun sameInputRmcUsesOneAtomicPhoneFixAndNeverBoatSogCog(){
        val phone=NavigationFix(-36.8485,174.7633,1_720_000_000_000,1_000,sogKnots=2.4,cogTrueDegrees=123.4,horizontalAccuracyMeters=4.0,positionProvider=PositionProvider.ANDROID_GNSS,sourceSentence="SYSTEM",valid=true)
        val boat=VesselDataSnapshot(
            position=VesselObservation(VesselPosition(-36.9,174.8),VesselDataSource.BOAT_NMEA,receivedElapsedRealtime=1_000,freshness=VesselDataFreshness.FRESH,sourceClass=VesselSourceClass.BOAT_NMEA),
            sogKnots=VesselObservation(19.8,VesselDataSource.BOAT_NMEA,receivedElapsedRealtime=1_000,freshness=VesselDataFreshness.FRESH,sourceClass=VesselSourceClass.BOAT_NMEA),
            cogTrueDegrees=VesselObservation(271.0,VesselDataSource.BOAT_NMEA,receivedElapsedRealtime=1_000,freshness=VesselDataFreshness.FRESH,sourceClass=VesselSourceClass.BOAT_NMEA),
        )
        val output=encoder.encode(AnchorWatchNmeaStream.POSITION,boat,NmeaDeviceOutputSettings(phonePositionEnabled=true),1_100,phone,inputTransportGeneration=7,inputProfileId="boat-primary").sentences
        val rmc=output.single{mux.sentenceType(it)=="RMC"};val vtg=output.single{mux.sentenceType(it)=="VTG"}
        assertTrue(rmc.contains("3650.91000,S")&&rmc.contains("17445.79800,E"))
        assertTrue(rmc.contains(",2.40,123.40,"));assertFalse(rmc.contains(",19.80,271.00,"))
        assertTrue(vtg.contains("123.40,T")&&vtg.contains("2.40,N"));assertFalse(vtg.contains("271.00,T"))
    }

    @Test fun raymarineLikeExternalHeadingDoesNotSuppressPhoneHeadingOnSameSocket(){
        val boatSource=VesselSourceIdentity("nmea:boat:4:IIHDT","boat-primary",4,VesselSourceType.NMEA_INPUT,sentenceType="HDT",displayName="Raymarine HDT")
        val phoneSource=VesselSourceIdentity("phone:vessel-heading",sourceType=VesselSourceType.PHONE_SENSOR,phoneSensorType="VESSEL_COMPASS",displayName="Phone vessel heading")
        val candidates=listOf(
            VesselSourceCandidate(VesselMetricId.HEADING_TRUE,271.0,boatSource,VesselSourceClass.BOAT_NMEA,receivedElapsedRealtime=1_000),
            VesselSourceCandidate(VesselMetricId.HEADING_TRUE,83.0,phoneSource,VesselSourceClass.PHONE_VESSEL_HEADING,receivedElapsedRealtime=1_000,provenance=VesselProvenance.PhoneSensor("Mounted phone heading",3)),
        )
        val snapshot=VesselDataSnapshot(
            headingTrueDegrees=VesselObservation(271.0,VesselDataSource.BOAT_NMEA,receivedElapsedRealtime=1_000,freshness=VesselDataFreshness.FRESH,sourceIdentity=boatSource,sourceClass=VesselSourceClass.BOAT_NMEA,provenanceDetail=VesselProvenance.Nmea(boatSource)),
            candidates=mapOf(VesselMetricId.HEADING_TRUE to candidates),
        )
        repeat(25){tick->
            val batch=encodeHeading(snapshot,1_000+tick*200L,inputGeneration=4,inputProfile="boat-primary")
            if(tick==0){assertEquals(1,batch.sentences.size);assertTrue(batch.sentences.single().contains("HDT,83.00,T"));assertTrue(batch.sourceConflict)}else if(tick*200L>2_000L)assertTrue(batch.sentences.isEmpty())
        }
    }

    @Test fun externalPressureDoesNotSuppressFreshPhonePressureOnSameSocket(){
        val boatSource=VesselSourceIdentity("nmea:boat:5:MDA","boat-primary",5,VesselSourceType.NMEA_INPUT,sentenceType="MDA",displayName="Boat pressure")
        val phoneSource=VesselSourceIdentity("phone:barometer",sourceType=VesselSourceType.PHONE_SENSOR,phoneSensorType="PRESSURE",displayName="Phone barometer")
        val snapshot=VesselDataSnapshot(
            pressureHpa=VesselObservation(1008.0,VesselDataSource.BOAT_NMEA,receivedElapsedRealtime=1_000,freshness=VesselDataFreshness.FRESH,sourceIdentity=boatSource,sourceClass=VesselSourceClass.BOAT_NMEA,provenanceDetail=VesselProvenance.Nmea(boatSource)),
            candidates=mapOf(VesselMetricId.PRESSURE to listOf(
                VesselSourceCandidate(VesselMetricId.PRESSURE,1008.0,boatSource,VesselSourceClass.BOAT_NMEA,receivedElapsedRealtime=1_000),
                VesselSourceCandidate(VesselMetricId.PRESSURE,1013.2,phoneSource,VesselSourceClass.PHONE_BAROMETER,receivedElapsedRealtime=1_000,provenance=VesselProvenance.PhoneSensor("Android pressure sensor")),
            )),
        )
        val batch=encoder.encode(AnchorWatchNmeaStream.PRESSURE,snapshot,NmeaDeviceOutputSettings(phonePressureEnabled=true,includePressure=true),1_000,inputTransportGeneration=5,inputProfileId="boat-primary")
        assertEquals(1,batch.sentences.size);assertTrue(batch.sentences.single().contains("1.01320,B,PHONE_BARO"));assertTrue(batch.sourceConflict)
    }

    @Test fun appDerivedWindWithBoatAncestorsIsBlockedOnEveryTransport(){
        val headingSource=VesselSourceIdentity("nmea:boat:9:HDT","boat-primary",9,VesselSourceType.NMEA_INPUT,sentenceType="HDT",displayName="Boat HDT")
        val windSource=VesselSourceIdentity("nmea:boat:9:MWV","boat-primary",9,VesselSourceType.NMEA_INPUT,sentenceType="MWV",displayName="Boat MWV")
        fun derived(value:Double)=VesselObservation(value,VesselDataSource.DERIVED,receivedElapsedRealtime=1_000,freshness=VesselDataFreshness.FRESH,sourceIdentity=VesselSourceIdentity("derived:true-wind",sourceType=VesselSourceType.APP_DERIVED,displayName="True wind"),sourceClass=VesselSourceClass.DERIVED_WATER,provenanceDetail=VesselProvenance.Derived("true wind",listOf(headingSource,windSource)))
        val snapshot=VesselDataSnapshot(trueWind=VesselWindObservation(derived(12.0),derived(220.0),derived(35.0)))
        val enabled=NmeaDeviceOutputSettings(derivedWindPolicy=PublicationPolicy.ALWAYS,includeDerivedWind=true)
        assertTrue(encoder.encode(AnchorWatchNmeaStream.DERIVED_WIND,snapshot,enabled,1_000,inputTransportGeneration=9,inputProfileId="boat-primary").sentences.isEmpty())
        encoder.reset();assertTrue(encoder.encode(AnchorWatchNmeaStream.DERIVED_WIND,snapshot,dedicatedDestination,1_000).sentences.isEmpty())
    }

    @Test fun directBoatWindIsBlockedWhileProvenLocalDerivedWindIsAllowed(){
        val boatSource=VesselSourceIdentity("nmea:boat:3:MWD","boat-primary",3,VesselSourceType.NMEA_INPUT,sentenceType="MWD",displayName="Boat MWD")
        fun boat(value:Double)=VesselObservation(value,VesselDataSource.BOAT_NMEA,receivedElapsedRealtime=1_000,freshness=VesselDataFreshness.FRESH,sourceIdentity=boatSource,sourceClass=VesselSourceClass.BOAT_NMEA,provenanceDetail=VesselProvenance.Nmea(boatSource))
        val windEnabled=NmeaDeviceOutputSettings(derivedWindPolicy=PublicationPolicy.ALWAYS,includeDerivedWind=true)
        assertTrue(encoder.encode(AnchorWatchNmeaStream.DERIVED_WIND,VesselDataSnapshot(trueWind=VesselWindObservation(boat(9.0),boat(180.0),boat(20.0))),windEnabled,1_000,inputTransportGeneration=3,inputProfileId="boat-primary").sentences.isEmpty())

        val phoneGnss=VesselSourceIdentity("phone:gnss",sourceType=VesselSourceType.PHONE_SENSOR,phoneSensorType="GNSS",displayName="Phone GNSS")
        val phoneHeading=VesselSourceIdentity("phone:vessel-heading",sourceType=VesselSourceType.PHONE_SENSOR,phoneSensorType="VESSEL_COMPASS",displayName="Phone heading")
        fun local(value:Double)=VesselObservation(value,VesselDataSource.DERIVED,receivedElapsedRealtime=1_000,freshness=VesselDataFreshness.FRESH,sourceIdentity=VesselSourceIdentity("derived:local-wind",sourceType=VesselSourceType.APP_DERIVED,displayName="Local wind"),sourceClass=VesselSourceClass.DERIVED_GROUND,provenanceDetail=VesselProvenance.Derived("local-only wind",listOf(phoneGnss,phoneHeading)))
        val phoneCandidates=listOf(
            VesselSourceCandidate(VesselMetricId.TRUE_WIND_SPEED,1.0,phoneGnss,VesselSourceClass.PHONE_GNSS,receivedElapsedRealtime=1_000,provenance=VesselProvenance.PhoneSensor("GNSS")),
            VesselSourceCandidate(VesselMetricId.HEADING_TRUE,1.0,phoneHeading,VesselSourceClass.PHONE_VESSEL_HEADING,receivedElapsedRealtime=1_000,provenance=VesselProvenance.PhoneSensor("heading",3)),
        )
        val localSnapshot=VesselDataSnapshot(trueWind=VesselWindObservation(local(9.0),local(180.0),local(20.0)),candidates=mapOf(VesselMetricId.TRUE_WIND_SPEED to phoneCandidates))
        encoder.reset();assertEquals(3,encoder.encode(AnchorWatchNmeaStream.DERIVED_WIND,localSnapshot,windEnabled,1_000,inputTransportGeneration=3,inputProfileId="boat-primary").sentences.size)
    }

    @Test fun phoneTxEchoIdentityIsAlwaysDeniedBySameSocketFirewall(){
        val echo=VesselSourceIdentity("echo:IIHDT",sourceType=VesselSourceType.PHONE_TX_ECHO,sentenceType="HDT",displayName="Echoed App TX")
        val decision=SameSocketProvenanceFirewall.evaluate(echo,VesselSourceClass.PHONE_VESSEL_HEADING,VesselProvenance.PhoneSensor("spoof"),"boat-primary",12)
        assertFalse(decision.allowed);assertEquals(SameSocketProvenanceReason.PHONE_TX_ECHO,decision.reason)

        fun derivedFromEcho(value:Double)=VesselObservation(value,VesselDataSource.DERIVED,receivedElapsedRealtime=1_000,freshness=VesselDataFreshness.FRESH,sourceIdentity=VesselSourceIdentity("derived:echo-wind",sourceType=VesselSourceType.APP_DERIVED,displayName="Echo-derived wind"),sourceClass=VesselSourceClass.DERIVED_GROUND,provenanceDetail=VesselProvenance.Derived("echo-derived",listOf(echo)))
        val wind=VesselDataSnapshot(trueWind=VesselWindObservation(derivedFromEcho(8.0),derivedFromEcho(180.0),derivedFromEcho(20.0)))
        assertTrue(encoder.encode(AnchorWatchNmeaStream.DERIVED_WIND,wind,dedicatedDestination,1_000).sentences.isEmpty())
    }

    @Test fun normalProductFeedNeverPublishesHiddenProprietaryOrRawBoatSentences(){
        val snapshot=VesselDataSnapshot(headingTrueDegrees=heading(45.0))
        val normal=encodeHeading(snapshot,0,dedicatedDestination.copy(proprietaryStatusEnabled=true)).sentences
        assertTrue(normal.isNotEmpty());assertTrue(normal.none{it.contains("PYOK")||it.contains("RAW_BOAT")})
    }

    @Test fun legacySharingDisabled_hasNoRawSentenceConsumer(){
        val rawBoat="\$IIHDT,271.00,T*00\r\n"
        val snapshot=VesselDataSnapshot(headingTrueDegrees=heading(45.0))
        val productFeed=AnchorWatchNmeaStream.entries.flatMap{encoder.encode(it,snapshot,NmeaDeviceOutputSettings(),0).sentences}
        assertFalse(productFeed.contains(rawBoat))
        assertTrue(productFeed.all{it.startsWith("$")&&it.endsWith("\r\n")})
    }

    @Test fun phoneServiceAndBoatClient_canEncodeTheSameOwnedFeedWithoutSharingLifecycle(){
        val snapshot=VesselDataSnapshot(headingTrueDegrees=heading(83.0))
        val tcpClient=NmeaPublisherConfig(transportMode=com.yokuli.anchorwatch.data.vessel.NmeaOutputTransportMode.DEDICATED_TCP,phoneHeadingEnabled=true,transportConfigured=true,publicationEnabled=true).asOutputSettings()
        val tcpServer=NmeaPublisherConfig(transportMode=com.yokuli.anchorwatch.data.vessel.NmeaOutputTransportMode.TCP_SERVER,phoneHeadingEnabled=true,transportConfigured=true,publicationEnabled=true).asOutputSettings()
        encoder.reset();val clientFeed=encodeHeading(snapshot,1_000,tcpClient).sentences
        encoder.reset();val serverFeed=encodeHeading(snapshot,1_000,tcpServer).sentences
        assertEquals(clientFeed,serverFeed);assertEquals(1,clientFeed.size)
        assertFalse(NmeaOutputEndpointPolicy.isValid(tcpServer,com.yokuli.anchorwatch.data.nmea.ConnectionProfile()))
    }

    @Test fun stopInvalidatesEveryOldPublicationGeneration(){
        val gate=NmeaPublicationSessionGate();val first=gate.start();assertTrue(gate.accepts(first))
        gate.stop();assertFalse(gate.accepts(first))
        val second=gate.start();assertFalse(gate.accepts(first));assertTrue(gate.accepts(second));assertNotEquals(first,second)
    }

    @Test fun allOutputsOffEmitsZeroBytesEvenWhenPhoneSensorsStillHaveValues(){
        val gate=NmeaPublicationSessionGate();val generated=encoder.encode(AnchorWatchNmeaStream.HEADING,VesselDataSnapshot(headingTrueDegrees=heading(123.4)),NmeaDeviceOutputSettings(),0).sentences
        assertTrue(generated.isEmpty());assertFalse(gate.accepts(gate.current()))
        val socketWrites=generated.filter{gate.accepts(gate.current())};assertTrue(socketWrites.isEmpty())
    }

    @Test fun stopWithQueuedHeadingDropsEveryOldBatchAndRestartNeverReplaysIt(){
        data class Queued(val generation:Long,val value:String)
        val gate=NmeaPublicationSessionGate();val old=gate.start();val queue=LatestPerStreamQueue<Queued>{"HEADING"}
        queue.offer(Queued(old,"old heading"));gate.stop()
        val discarded=queue.poll()!!;assertFalse(gate.accepts(discarded.generation))
        val fresh=gate.start();assertNotEquals(old,fresh);assertNull(queue.poll())
    }

    @Test fun staleSettingsSnapshot_cannotRestartStoppedPublisherGeneration(){
        val gate=NmeaPublicationSessionGate();val staleOnGeneration=gate.start()
        val stoppedGeneration=gate.stop()
        assertFalse(gate.accepts(staleOnGeneration))
        assertFalse(gate.accepts(stoppedGeneration))
        assertFalse(gate.accepts(gate.current()))
    }
}
