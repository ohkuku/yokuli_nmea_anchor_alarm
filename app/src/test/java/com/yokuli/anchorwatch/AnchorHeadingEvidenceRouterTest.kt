package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.model.*
import com.yokuli.anchorwatch.domain.vessel.*
import com.yokuli.anchorwatch.location.AnchorHeadingEvidenceRouter
import com.yokuli.anchorwatch.location.PhoneHeadingSample
import com.yokuli.anchorwatch.location.PhoneHeadingPresentationQuality
import com.yokuli.anchorwatch.location.vessel.PhoneVesselMountState
import org.junit.Assert.*
import org.junit.Test

class AnchorHeadingEvidenceRouterTest{
    private fun boat(heading:Double?=42.0,source:HeadingSource=HeadingSource.NMEA_PHYSICAL)=NavigationFix(1.0,2.0,receivedElapsedRealtime=100,headingTrueDegrees=heading,cogTrueDegrees=190.0,sourceSentence="TEST",valid=true,headingSource=source,headingQuality=HeadingQuality.STABLE,headingEpoch=7,headingSampleSequence=8)
    private fun phone(evidence:Double?=84.0,quality:HeadingQuality=HeadingQuality.STABLE)=PhoneHeadingSample(liveTrueHeadingDegrees=85.0,liveMagneticHeadingDegrees=65.0,trueHeadingDegrees=evidence,presentationQuality=PhoneHeadingPresentationQuality.GOOD,quality=quality,epoch=9,sequence=10,receivedElapsedRealtime=100,declinationReferenceReady=true,magneticDeclinationDegrees=20.0)

    @Test fun boatChoiceAcceptsOnlyPhysicalNmeaHeading(){
        assertEquals(42.0,AnchorHeadingEvidenceRouter.route(VesselSourcePreference.BOAT,boat(),phone(),PhoneVesselMountState.VESSEL_MOUNTED).trueDegrees?:Double.NaN,0.0)
        val cogOnly=AnchorHeadingEvidenceRouter.route(VesselSourcePreference.BOAT,boat(null,HeadingSource.NONE),phone(),PhoneVesselMountState.VESSEL_MOUNTED)
        assertNull(cogOnly.trueDegrees);assertEquals("SELECTED_BOAT_HEADING_UNAVAILABLE",cogOnly.reason)
    }

    @Test fun phoneChoiceUsesIntegrityChannelNotResponsivePresentationChannel(){
        val presentationOnly=AnchorHeadingEvidenceRouter.route(VesselSourcePreference.PHONE,boat(),phone(null,HeadingQuality.MOVING),PhoneVesselMountState.VESSEL_MOUNTED)
        assertNull(presentationOnly.trueDegrees)
        val stable=AnchorHeadingEvidenceRouter.route(VesselSourcePreference.PHONE,boat(),phone(),PhoneVesselMountState.VESSEL_MOUNTED)
        assertEquals(84.0,stable.trueDegrees?:Double.NaN,0.0);assertEquals(HeadingSource.PHONE,stable.source)
    }

    @Test fun automaticChoicePrefersBoatThenStablePhone(){
        assertEquals(HeadingSource.NMEA_PHYSICAL,AnchorHeadingEvidenceRouter.route(VesselSourcePreference.AUTO,boat(),phone(),PhoneVesselMountState.VESSEL_MOUNTED).source)
        assertEquals(HeadingSource.PHONE,AnchorHeadingEvidenceRouter.route(VesselSourcePreference.AUTO,boat(null,HeadingSource.NONE),phone(),PhoneVesselMountState.VESSEL_MOUNTED).source)
    }

    @Test fun handheldPhoneNeverBecomesVesselHeadingEvidence(){
        val result=AnchorHeadingEvidenceRouter.route(VesselSourcePreference.PHONE,boat(),phone(),PhoneVesselMountState.HANDHELD)
        assertNull(result.trueDegrees)
        assertEquals(HeadingSource.NONE,result.source)
        assertEquals("PHONE_NOT_VESSEL_MOUNTED",result.reason)
    }

    @Test fun selectedEvidenceAcceptsOnlyBoatClassFromUnifiedRouting(){
        val identity=VesselSourceIdentity("nmea:helm:HDT",sourceType=VesselSourceType.NMEA_INPUT,sentenceType="HDT",displayName="IIHDT")
        val selected=VesselObservation(51.0,VesselDataSource.BOAT_NMEA,receivedElapsedRealtime=123,quality=VesselDataQuality.GOOD,freshness=VesselDataFreshness.FRESH,sourceIdentity=identity,sourceClass=VesselSourceClass.BOAT_NMEA,reference=VesselReference.TrueNorth)
        val result=AnchorHeadingEvidenceRouter.routeSelected(VesselSourcePreference.AUTO,selected,null,false,phone(),PhoneVesselMountState.VESSEL_MOUNTED)
        assertEquals(51.0,result.trueDegrees?:Double.NaN,0.0)
        assertEquals(HeadingSource.NMEA_PHYSICAL,result.source)
        assertEquals(123L,result.sequence)
    }

    @Test fun autoConflictSuppressesEvidenceUntilAnExactSourceIsPinned(){
        val identity=VesselSourceIdentity("nmea:helm:HDT",sourceType=VesselSourceType.NMEA_INPUT,sentenceType="HDT",displayName="IIHDT")
        val selected=VesselObservation(51.0,VesselDataSource.BOAT_NMEA,receivedElapsedRealtime=123,quality=VesselDataQuality.GOOD,freshness=VesselDataFreshness.FRESH,sourceIdentity=identity,sourceClass=VesselSourceClass.BOAT_NMEA,reference=VesselReference.TrueNorth)
        val conflict=VesselSourceConflict(true,identity,listOf(identity.copy(id="nmea:pilot:HDT")),"conflict")
        assertEquals("AUTO_SOURCE_CONFLICT",AnchorHeadingEvidenceRouter.routeSelected(VesselSourcePreference.AUTO,selected,conflict,false,phone(),PhoneVesselMountState.VESSEL_MOUNTED).reason)
        assertEquals(HeadingSource.NMEA_PHYSICAL,AnchorHeadingEvidenceRouter.routeSelected(VesselSourcePreference.AUTO,selected,conflict,true,phone(),PhoneVesselMountState.VESSEL_MOUNTED).source)
    }

    @Test fun heldOrDegradedBoatHeadingIsNotSafetyEvidence(){
        val selected=VesselObservation(51.0,VesselDataSource.BOAT_NMEA,receivedElapsedRealtime=123,quality=VesselDataQuality.GOOD,freshness=VesselDataFreshness.HELD,sourceClass=VesselSourceClass.BOAT_NMEA,reference=VesselReference.TrueNorth)
        assertNull(AnchorHeadingEvidenceRouter.routeSelected(VesselSourcePreference.BOAT,selected,null,false,phone(),PhoneVesselMountState.VESSEL_MOUNTED).trueDegrees)
    }
}
