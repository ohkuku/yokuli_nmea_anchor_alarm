package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.vessel.NmeaStreamReadiness
import com.yokuli.anchorwatch.data.vessel.NmeaOutputTransportMode
import com.yokuli.anchorwatch.location.vessel.VesselMountCalibration
import com.yokuli.anchorwatch.location.vessel.PhoneVesselMountState
import com.yokuli.anchorwatch.location.vessel.PhoneVesselOutputReadinessPolicy
import com.yokuli.anchorwatch.location.vessel.PhoneHeadingAlignmentPolicy
import com.yokuli.anchorwatch.location.vessel.PhoneHeadingAlignmentReference
import com.yokuli.anchorwatch.runtime.output.AnchorWatchNmeaStream
import com.yokuli.anchorwatch.runtime.output.FormalOutputSessionReadinessPolicy
import com.yokuli.anchorwatch.runtime.output.NmeaStreamReadinessPolicy
import com.yokuli.anchorwatch.runtime.output.PhoneOwnedRuntimeSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaStreamReadinessPolicyTest {
    @Test fun liveHeadingAlignmentUsesOnlyMatchingNorthReferences(){
        val trueMatch=PhoneHeadingAlignmentPolicy.matchLiveReference(350.0,340.0,10.0,20.0)
        assertEquals(PhoneHeadingAlignmentReference.TRUE_NORTH,trueMatch?.reference)
        assertEquals(20.0,trueMatch?.offsetDegrees?:Double.NaN,0.001)

        val magneticMatch=PhoneHeadingAlignmentPolicy.matchLiveReference(null,350.0,10.0,5.0)
        assertEquals(PhoneHeadingAlignmentReference.MAGNETIC_NORTH,magneticMatch?.reference)
        assertEquals(15.0,magneticMatch?.offsetDegrees?:Double.NaN,0.001)
        assertNull(PhoneHeadingAlignmentPolicy.matchLiveReference(null,350.0,10.0,null))
    }

    @Test fun positionWaitsForFreshGnssWithoutDependingOnMountCalibration(){
        assertEquals(NmeaStreamReadiness.WAITING_POSITION,NmeaStreamReadinessPolicy.position(false))
        assertEquals(NmeaStreamReadiness.READY,NmeaStreamReadinessPolicy.position(true))
    }

    @Test fun headingRequiresOnlyItsOwnAlignmentDeclinationAndFreshCompass(){
        assertEquals(NmeaStreamReadiness.WAITING_CALIBRATION,NmeaStreamReadinessPolicy.heading(false,true,true))
        assertEquals(NmeaStreamReadiness.WAITING_POSITION,NmeaStreamReadinessPolicy.heading(true,false,true))
        assertEquals(NmeaStreamReadiness.STANDBY,NmeaStreamReadinessPolicy.heading(true,true,false))
        assertEquals(NmeaStreamReadiness.READY,NmeaStreamReadinessPolicy.heading(true,true,true))
    }

    @Test fun motionAndPressureDoNotShareHeadingPrerequisites(){
        assertEquals(NmeaStreamReadiness.READY,NmeaStreamReadinessPolicy.motion(true,true,true))
        assertEquals(NmeaStreamReadiness.STANDBY,NmeaStreamReadinessPolicy.sensor(false))
        assertEquals(NmeaStreamReadiness.READY,NmeaStreamReadinessPolicy.sensor(true))
    }

    @Test fun headingAlignmentIsAnExplicitPersistedFactNotAnImplicitZeroOffset(){
        val legacy=VesselMountCalibration(calibratedAt=1,headingAlignmentOffsetDegrees=0.0)
        val aligned=legacy.copy(headingAlignmentCompletedAt=2,headingAlignmentVersion=legacy.version)
        assertFalse(legacy.headingAligned)
        assertTrue(aligned.headingAligned)
    }

    @Test fun formalOutputRequiresDurableHeadingAlignmentButNotTripAttitudeMount(){
        val calibrated=VesselMountCalibration(
            version=4,
            calibratedAt=1,
            mountState=PhoneVesselMountState.VESSEL_MOUNTED,
            mountConfirmedVersion=4,
            headingAlignmentCompletedAt=2,
            headingAlignmentVersion=4,
        )
        assertTrue(PhoneVesselOutputReadinessPolicy.evaluate(calibrated,PhoneVesselMountState.VESSEL_MOUNTED).ready)

        val recalibrated=calibrated.copy(version=5,mountState=PhoneVesselMountState.MOUNT_SUSPECT,attitudeInvalidatedAt=3)
        val stale=PhoneVesselOutputReadinessPolicy.evaluate(recalibrated,PhoneVesselMountState.VESSEL_MOUNTED)
        assertTrue(stale.ready)
        assertTrue(stale.blockers.isEmpty())
    }

    @Test fun invalidTripAttitudeSegmentDoesNotBlockHeadingGpsOrPressureOutput(){
        val calibrated=VesselMountCalibration(
            calibratedAt=1,
            mountState=PhoneVesselMountState.VESSEL_MOUNTED,
            mountConfirmedVersion=1,
            headingAlignmentCompletedAt=2,
            headingAlignmentVersion=1,
        )
        val result=PhoneVesselOutputReadinessPolicy.evaluate(calibrated,PhoneVesselMountState.MOUNT_SUSPECT)
        assertTrue(result.ready)
        assertTrue(result.blockers.isEmpty())
    }

    @Test fun invalidAttitudeSegmentSuppressesOnlyMotionOnEveryTransport(){
        assertNull(PhoneOwnedRuntimeSafety.suppression(NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION,AnchorWatchNmeaStream.HEADING,PhoneVesselMountState.MOUNT_SUSPECT))
        assertEquals("MOUNT_SUSPECT",PhoneOwnedRuntimeSafety.suppression(NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION,AnchorWatchNmeaStream.MOTION,PhoneVesselMountState.MOUNT_SUSPECT))
        assertNull(PhoneOwnedRuntimeSafety.suppression(NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION,AnchorWatchNmeaStream.POSITION,PhoneVesselMountState.MOUNT_SUSPECT))
        assertNull(PhoneOwnedRuntimeSafety.suppression(NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION,AnchorWatchNmeaStream.PRESSURE,PhoneVesselMountState.MOUNT_SUSPECT))
        assertNull(PhoneOwnedRuntimeSafety.suppression(NmeaOutputTransportMode.DEDICATED_TCP,AnchorWatchNmeaStream.HEADING,PhoneVesselMountState.MOUNT_SUSPECT))
    }

    @Test fun missingHeadingAlignmentBlocksOnlyAFormalStartNotAnExistingSession(){
        val blocked=PhoneVesselOutputReadinessPolicy.evaluate(
            VesselMountCalibration(),
            PhoneVesselMountState.MOUNT_SUSPECT,
        )
        assertTrue(FormalOutputSessionReadinessPolicy.blocksStart(requestedRunning=true,currentlyEnabled=false,readiness=blocked))
        assertFalse(FormalOutputSessionReadinessPolicy.blocksStart(requestedRunning=true,currentlyEnabled=true,readiness=blocked))
    }
}
