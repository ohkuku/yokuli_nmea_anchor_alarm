package com.yokuli.anchorwatch

import com.google.gson.Gson
import com.yokuli.anchorwatch.data.nmea.NmeaSafetyRetryPolicy
import com.yokuli.anchorwatch.data.vessel.NmeaDeviceOutputSettings
import com.yokuli.anchorwatch.data.vessel.NmeaOutputPreset
import com.yokuli.anchorwatch.data.vessel.withPreset
import com.yokuli.anchorwatch.data.vessel.anyStreamSelected
import com.yokuli.anchorwatch.domain.model.*
import com.yokuli.anchorwatch.domain.anchor.AnchorSetupDepthPolicy
import com.yokuli.anchorwatch.domain.safety.AnchorSetupReadinessEvaluator
import com.yokuli.anchorwatch.domain.safety.AnchorSetupReadinessInput
import com.yokuli.anchorwatch.domain.vessel.*
import com.yokuli.anchorwatch.location.AcceptedAnchorPositionPolicy
import com.yokuli.anchorwatch.location.AnchorRawPositionPrimingPolicy
import com.yokuli.anchorwatch.location.AcceptedPositionState
import com.yokuli.anchorwatch.location.NewAnchorPositionSourcePolicy
import com.yokuli.anchorwatch.location.vessel.PhoneVesselMountState
import com.yokuli.anchorwatch.location.vessel.VesselMountCalibration
import com.yokuli.anchorwatch.runtime.output.PhoneOwnedPublicationProvenancePolicy
import com.yokuli.anchorwatch.runtime.output.PhoneVesselHeadingPublicationPolicy
import org.junit.Assert.*
import org.junit.Test

class P0RemediationPolicyTest {
    private fun nmeaFix(received:Long=9_000)=NavigationFix(
        -36.8485,174.7633,receivedElapsedRealtime=received,hdop=1.2,fixQuality=1,
        hdopReceivedElapsedRealtime=received,fixQualityReceivedElapsedRealtime=received,
        positionProvider=PositionProvider.NMEA,sourceSentence="GNRMC",valid=true,
    )
    private fun acceptedNmea(fix:NavigationFix=nmeaFix(),generation:Long=7)=AcceptedPositionState(
        selectedSource=GpsDataSource.NMEA,acceptedFix=fix,trust=FixTrust.TRUSTED,
        disposition="ACCEPTED",acceptedConnectionGeneration=generation,
    )
    private fun readiness(state:AcceptedPositionState,now:Long=10_000,generation:Long=7)=AcceptedAnchorPositionPolicy.evaluate(
        state,GpsDataSource.NMEA,now,5_000,NmeaConnectionState.CONNECTED,8_000,generation,
    )

    @Test fun staleNmeaFixCannotSeedCurrentPositionAnchor(){assertEquals("ACCEPTED_POSITION_STALE",readiness(acceptedNmea(nmeaFix(1_000))).reason)}
    @Test fun negativeAcceptedAgeIsReportedAsClockAheadNotStale(){
        val fix=nmeaFix(10_001)
        val staleEntrySnapshot=readiness(acceptedNmea(fix),now=10_000)
        assertEquals("ACCEPTED_POSITION_CLOCK_AHEAD",staleEntrySnapshot.reason)
        assertTrue("ARM must compare after its synchronous prime",readiness(acceptedNmea(fix),now=10_002).ready)
    }
    @Test fun providerFixArrivingDuringArmIsReadyAtThePostPrimeDecisionClock(){
        val armStartedAt=100_000L
        val fix=nmeaFix(100_080L)
        val decisionNow=100_120L
        assertTrue(fix.receivedElapsedRealtime>armStartedAt)
        val result=readiness(acceptedNmea(fix),now=decisionNow)
        assertTrue(result.ready)
        assertTrue(result.evidence.contains("age 40 ms"))
    }
    @Test fun conditionFreshnessUsesItsPostSnapshotDecisionClock(){
        val armStartedAt=100_000L
        val depthReceivedAt=100_080L
        val conditionDecisionNow=100_120L
        assertTrue(depthReceivedAt>armStartedAt)
        assertTrue(AnchorSetupDepthPolicy.nmeaAvailable(
            NmeaConnectionState.CONNECTED,
            depthMeters=8.4,
            receivedElapsedRealtime=depthReceivedAt,
            nowElapsedRealtime=conditionDecisionNow,
        ))
    }
    @Test fun rejectedPositionCannotSeedAnchorCentre(){assertFalse(readiness(acceptedNmea().copy(disposition="REJECTED",trust=FixTrust.REJECTED)).ready)}
    @Test fun quarantinedPositionCannotSeedAnchorCentre(){assertFalse(readiness(acceptedNmea().copy(disposition="QUARANTINED",trust=FixTrust.QUARANTINED)).ready)}
    @Test fun currentPositionUsesAcceptedRepositoryFixOnly(){assertTrue(readiness(acceptedNmea()).ready)}
    @Test fun oldConnectionGenerationCannotSeedAnchorCentre(){assertEquals("NMEA_CONNECTION_GENERATION_MISMATCH",readiness(acceptedNmea(generation=6)).reason)}

    @Test fun armCurrentPositionPrimesRawFixThroughIntegrityGate(){
        val raw=nmeaFix(9_000)
        val candidate=AnchorRawPositionPrimingPolicy.select(GpsDataSource.NMEA,null,raw,8_000,7)
        assertNotNull(candidate);assertEquals(raw,candidate?.fix);assertEquals(7L,candidate?.connectionGeneration)
    }
    @Test fun oldNmeaGenerationCannotPrimeAnchorOrigin(){
        assertNull(AnchorRawPositionPrimingPolicy.select(GpsDataSource.NMEA,null,nmeaFix(7_999),8_000,7))
    }
    @Test fun armNeverUsesRawFixThatIntegrityRejects(){
        val network=nmeaFix().copy(positionProvider=PositionProvider.ANDROID_NETWORK)
        assertNull(AnchorRawPositionPrimingPolicy.select(GpsDataSource.SYSTEM,network,null,null,0))
        assertNull(AnchorRawPositionPrimingPolicy.select(GpsDataSource.NMEA,null,network,8_000,7))
    }

    @Test fun androidNetworkPositionCannotSeedCurrentPositionAnchor(){
        val fix=nmeaFix().copy(positionProvider=PositionProvider.ANDROID_NETWORK,horizontalAccuracyMeters=4.0)
        val state=AcceptedPositionState(selectedSource=GpsDataSource.SYSTEM,acceptedFix=fix,trust=FixTrust.TRUSTED,disposition="ACCEPTED")
        val result=AcceptedAnchorPositionPolicy.evaluate(state,GpsDataSource.SYSTEM,10_000,5_000,NmeaConnectionState.DISCONNECTED,null,0)
        assertEquals("SYSTEM_PROVIDER_NOT_GNSS",result.reason)
    }
    @Test fun inaccuratePhoneGnssCannotSeedCurrentPositionAnchor(){
        val fix=nmeaFix().copy(positionProvider=PositionProvider.ANDROID_GNSS,horizontalAccuracyMeters=31.0)
        val state=AcceptedPositionState(selectedSource=GpsDataSource.SYSTEM,acceptedFix=fix,trust=FixTrust.TRUSTED,disposition="ACCEPTED")
        assertEquals("SYSTEM_ACCURACY_TOO_LOW",AcceptedAnchorPositionPolicy.evaluate(state,GpsDataSource.SYSTEM,10_000,5_000,NmeaConnectionState.DISCONNECTED,null,0).reason)
    }
    @Test fun mockPhonePositionCannotSeedCurrentPositionAnchor(){
        val fix=nmeaFix().copy(positionProvider=PositionProvider.ANDROID_GNSS,horizontalAccuracyMeters=4.0,isMockLocation=true)
        val state=AcceptedPositionState(selectedSource=GpsDataSource.SYSTEM,acceptedFix=fix,trust=FixTrust.TRUSTED,disposition="ACCEPTED")
        assertEquals("MOCK_SYSTEM_POSITION",AcceptedAnchorPositionPolicy.evaluate(state,GpsDataSource.SYSTEM,10_000,5_000,NmeaConnectionState.DISCONNECTED,null,0).reason)
    }

    @Test fun manualCoordinateMayCreateWaitingSessionWithoutGps(){
        val result=AnchorSetupReadinessEvaluator.evaluate(AnchorSetupReadinessInput(AnchorOriginMode.MANUAL_COORDINATE,false,true,true,true))
        assertTrue(result.canStart);assertTrue(result.willWaitForGps)
    }
    @Test fun mapPickMayCreateWaitingSessionWithoutGps(){
        val result=AnchorSetupReadinessEvaluator.evaluate(AnchorSetupReadinessInput(AnchorOriginMode.MAP_PICK,false,true,true,true))
        assertTrue(result.canStart);assertTrue(result.willWaitForGps)
    }
    @Test fun estimateModeRequiresAcceptedPosition(){
        val result=AnchorSetupReadinessEvaluator.evaluate(AnchorSetupReadinessInput(AnchorOriginMode.BACKDOWN_FROM_ACCEPTED_POSITION,false,false,true,true))
        assertFalse(result.canStart);assertTrue("ACCEPTED_POSITION_REQUIRED" in result.blockers)
    }
    @Test fun currentPositionModeRequiresAcceptedPosition(){
        val result=AnchorSetupReadinessEvaluator.evaluate(AnchorSetupReadinessInput(AnchorOriginMode.CURRENT_ACCEPTED_POSITION,false,true,true,true))
        assertFalse(result.canStart);assertTrue("ACCEPTED_POSITION_REQUIRED" in result.blockers)
    }
    @Test fun connectingInstrumentNmeaNeverPromotesPhoneGps(){
        assertEquals(GpsDataSource.SYSTEM,NewAnchorPositionSourcePolicy.resolve(GpsDataSource.SYSTEM,false))
        assertEquals(GpsDataSource.NMEA,NewAnchorPositionSourcePolicy.resolve(GpsDataSource.NMEA,false))
    }

    private val headingIdentity=VesselSourceIdentity("phone:vessel-heading",sourceType=VesselSourceType.PHONE_SENSOR,phoneSensorType="VESSEL_COMPASS",displayName="Phone vessel heading")
    private val aligned=VesselMountCalibration(version=3,calibratedAt=1,mountState=PhoneVesselMountState.VESSEL_MOUNTED,mountConfirmedVersion=3,headingAlignmentCompletedAt=2,headingAlignmentVersion=3)
    private fun heading(freshness:VesselDataFreshness=VesselDataFreshness.FRESH,received:Long=9_000,calibrationVersion:Int=3,sourceClass:VesselSourceClass=VesselSourceClass.PHONE_VESSEL_HEADING)=VesselObservation(
        123.0,VesselDataSource.PHONE_MAGNETOMETER,receivedElapsedRealtime=received,freshness=freshness,
        sourceIdentity=headingIdentity,sourceClass=sourceClass,provenanceDetail=VesselProvenance.PhoneSensor("heading",calibrationVersion),
    )
    @Test fun handheldPhoneNeverPublishesHdt(){assertEquals("PHONE_NOT_VESSEL_MOUNTED",PhoneVesselHeadingPublicationPolicy.evaluate(heading(),aligned,PhoneVesselMountState.HANDHELD,10_000).reason)}
    @Test fun uncalibratedPhoneNeverPublishesHdt(){assertEquals("HEADING_ALIGNMENT_REQUIRED",PhoneVesselHeadingPublicationPolicy.evaluate(heading(),VesselMountCalibration(),PhoneVesselMountState.VESSEL_MOUNTED,10_000).reason)}
    @Test fun phoneDeviceCompassCannotFallbackIntoVesselHdt(){assertEquals("NOT_PHONE_VESSEL_HEADING",PhoneVesselHeadingPublicationPolicy.evaluate(heading(sourceClass=VesselSourceClass.PHONE_DEVICE_COMPASS),aligned,PhoneVesselMountState.VESSEL_MOUNTED,10_000).reason)}
    @Test fun heldHeadingIsNeverRepublishedAsFreshHdt(){assertEquals("HELD_OR_STALE_HEADING",PhoneVesselHeadingPublicationPolicy.evaluate(heading(VesselDataFreshness.HELD),aligned,PhoneVesselMountState.VESSEL_MOUNTED,10_000).reason)}
    @Test fun staleHeadingIsNeverRepublished(){assertEquals("PHONE_HEADING_STALE",PhoneVesselHeadingPublicationPolicy.evaluate(heading(received=1_000),aligned,PhoneVesselMountState.VESSEL_MOUNTED,10_000).reason)}
    @Test fun headingResumesOnlyAfterCurrentMountEpochConfirmation(){assertEquals("HEADING_ALIGNMENT_EPOCH_MISMATCH",PhoneVesselHeadingPublicationPolicy.evaluate(heading(calibrationVersion=2),aligned,PhoneVesselMountState.VESSEL_MOUNTED,10_000).reason)}
    @Test fun currentAlignedMountedPhoneVesselHeadingMayPublish(){assertTrue(PhoneVesselHeadingPublicationPolicy.evaluate(heading(),aligned,PhoneVesselMountState.VESSEL_MOUNTED,10_000).allowed)}

    private val phone=VesselSourceIdentity("phone:gnss",sourceType=VesselSourceType.PHONE_SENSOR,phoneSensorType="GNSS",displayName="Phone GNSS")
    private val boat=VesselSourceIdentity("nmea:boat:7:RMC",sourceType=VesselSourceType.NMEA_INPUT,sentenceType="RMC",displayName="Boat GPS")
    private val echo=VesselSourceIdentity("echo:RMC",sourceType=VesselSourceType.PHONE_TX_ECHO,sentenceType="RMC",displayName="TX echo")
    private val derived=VesselSourceIdentity("derived:wind",sourceType=VesselSourceType.APP_DERIVED,displayName="Derived wind")
    private fun derivedObservation(inputs:List<VesselSourceIdentity>)=VesselObservation(12.0,VesselDataSource.DERIVED,receivedElapsedRealtime=1_000,freshness=VesselDataFreshness.FRESH,sourceIdentity=derived,sourceClass=VesselSourceClass.DERIVED_GROUND,provenanceDetail=VesselProvenance.Derived("wind",inputs))
    private fun candidate(identity:VesselSourceIdentity,provenance:VesselProvenance?)=VesselSourceCandidate(VesselMetricId.TRUE_WIND_SPEED,1.0,identity,if(identity.sourceType==VesselSourceType.PHONE_SENSOR)VesselSourceClass.PHONE_GNSS else VesselSourceClass.DERIVED_GROUND,receivedElapsedRealtime=1_000,provenance=provenance)

    @Test fun derivedWindWithAnyNmeaAncestorIsRejected(){assertEquals("NMEA_INPUT_ANCESTOR",PhoneOwnedPublicationProvenancePolicy.evaluate(derivedObservation(listOf(phone,boat)),listOf(candidate(phone,VesselProvenance.PhoneSensor("gps")))).reason)}
    @Test fun derivedWindWithEchoAncestorIsRejected(){assertEquals("PHONE_TX_ECHO_ANCESTOR",PhoneOwnedPublicationProvenancePolicy.evaluate(derivedObservation(listOf(echo)),emptyList()).reason)}
    @Test fun derivedWindWithUnknownAncestorIsRejected(){
        val nested=VesselSourceIdentity("derived:nested",sourceType=VesselSourceType.APP_DERIVED,displayName="Nested")
        assertEquals("UNKNOWN_DERIVED_ANCESTOR",PhoneOwnedPublicationProvenancePolicy.evaluate(derivedObservation(listOf(nested)),emptyList()).reason)
    }
    @Test fun derivedWindWithMissingProvenanceIsRejected(){
        val value=derivedObservation(listOf(phone)).copy(provenanceDetail=null)
        assertEquals("MISSING_DERIVED_PROVENANCE",PhoneOwnedPublicationProvenancePolicy.evaluate(value,emptyList()).reason)
    }
    @Test fun phoneOnlyDerivedWindIsAllowed(){
        val decision=PhoneOwnedPublicationProvenancePolicy.evaluate(derivedObservation(listOf(phone)),listOf(candidate(phone,VesselProvenance.PhoneSensor("gps"))))
        assertTrue(decision.allowed)
    }
    @Test fun phoneOnlyNestedDerivedWindIsAllowed(){
        val nested=VesselSourceIdentity("derived:nested",sourceType=VesselSourceType.APP_DERIVED,displayName="Nested")
        val outer=derivedObservation(listOf(nested))
        val candidates=listOf(candidate(phone,VesselProvenance.PhoneSensor("gps")),candidate(nested,VesselProvenance.Derived("nested",listOf(phone))))
        assertTrue(PhoneOwnedPublicationProvenancePolicy.evaluate(outer,candidates).allowed)
    }

    @Test fun freshInstallDerivedWindDefaultsOff(){assertEquals(PublicationPolicy.OFF,NmeaDeviceOutputSettings().derivedWindPolicy)}
    @Test fun everyOutputStreamIsExplicitlyOffByDefault(){assertFalse(NmeaDeviceOutputSettings().anyStreamSelected)}
    @Test fun kc2wPresetSelectsOnlyHdt(){
        val value=NmeaDeviceOutputSettings().withPreset(NmeaOutputPreset.KC2W_MINIMAL)
        assertTrue(value.phoneHeadingEnabled);assertFalse(value.phonePositionEnabled);assertFalse(value.phoneRateOfTurnEnabled);assertFalse(value.phonePressureEnabled)
    }
    @Test fun activeSafetyOwnerUsesBoundedInfiniteBackoff(){assertEquals(listOf(2_000L,5_000L,10_000L,15_000L,30_000L,30_000L), (1..6).map(NmeaSafetyRetryPolicy::delayMillis))}

    @Test fun anchorSetupDraftSurvivesActivityRecreation(){
        val original=AnchorSetupDraft(referenceKey="saved:1",manualCoordinate="-36.8, 174.7",alarmRadius="55",windGuard=true)
        assertEquals(original,Gson().fromJson(Gson().toJson(original),AnchorSetupDraft::class.java))
    }
    @Test fun referenceChangeDoesNotReuseWrongDraft(){
        val draft=AnchorSetupDraft(referenceKey="40.0:8.0:45.0")
        assertNotEquals("60.0:12.0:65.0",draft.referenceKey)
    }
}
