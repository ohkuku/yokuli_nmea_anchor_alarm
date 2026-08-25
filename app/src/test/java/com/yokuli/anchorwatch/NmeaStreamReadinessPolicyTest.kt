package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.vessel.NmeaStreamReadiness
import com.yokuli.anchorwatch.data.vessel.NmeaOutputTransportMode
import com.yokuli.anchorwatch.location.vessel.PhoneMountMovementPolicy
import com.yokuli.anchorwatch.location.vessel.VesselMountCalibration
import com.yokuli.anchorwatch.location.vessel.PhoneVesselMountState
import com.yokuli.anchorwatch.location.vessel.PhoneVesselOutputBlocker
import com.yokuli.anchorwatch.location.vessel.PhoneVesselOutputReadinessPolicy
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
    @Test fun positionWaitsForFreshGnssWithoutDependingOnMountCalibration(){
        assertEquals(NmeaStreamReadiness.WAITING_POSITION,NmeaStreamReadinessPolicy.position(false))
        assertEquals(NmeaStreamReadiness.READY,NmeaStreamReadinessPolicy.position(true))
    }

    @Test fun headingRequiresExplicitAlignmentMountDeclinationAndFreshCompass(){
        assertEquals(NmeaStreamReadiness.WAITING_CALIBRATION,NmeaStreamReadinessPolicy.heading(true,false,true,true,true))
        assertEquals(NmeaStreamReadiness.WAITING_CALIBRATION,NmeaStreamReadinessPolicy.heading(true,true,false,true,true))
        assertEquals(NmeaStreamReadiness.WAITING_POSITION,NmeaStreamReadinessPolicy.heading(true,true,true,false,true))
        assertEquals(NmeaStreamReadiness.STANDBY,NmeaStreamReadinessPolicy.heading(true,true,true,true,false))
        assertEquals(NmeaStreamReadiness.READY,NmeaStreamReadinessPolicy.heading(true,true,true,true,true))
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

    @Test fun formalOutputRequiresVersionMatchedZeroMountAndHeadingAlignment(){
        val calibrated=VesselMountCalibration(
            version=4,
            calibratedAt=1,
            mountState=PhoneVesselMountState.VESSEL_MOUNTED,
            mountConfirmedVersion=4,
            headingAlignmentCompletedAt=2,
            headingAlignmentVersion=4,
        )
        assertTrue(PhoneVesselOutputReadinessPolicy.evaluate(calibrated,PhoneVesselMountState.VESSEL_MOUNTED).ready)

        val recalibrated=calibrated.copy(version=5)
        val stale=PhoneVesselOutputReadinessPolicy.evaluate(recalibrated,PhoneVesselMountState.VESSEL_MOUNTED)
        assertFalse(stale.ready)
        assertTrue(PhoneVesselOutputBlocker.MOUNT_CONFIRMATION_REQUIRED in stale.blockers)
        assertTrue(PhoneVesselOutputBlocker.HEADING_ALIGNMENT_REQUIRED in stale.blockers)
    }

    @Test fun suspectMountImmediatelyBlocksFormalOutput(){
        val calibrated=VesselMountCalibration(
            calibratedAt=1,
            mountState=PhoneVesselMountState.VESSEL_MOUNTED,
            mountConfirmedVersion=1,
            headingAlignmentCompletedAt=2,
            headingAlignmentVersion=1,
        )
        val result=PhoneVesselOutputReadinessPolicy.evaluate(calibrated,PhoneVesselMountState.MOUNT_SUSPECT)
        assertFalse(result.ready)
        assertTrue(PhoneVesselOutputBlocker.MOUNT_SUSPECT in result.blockers)
    }

    @Test fun magneticFusionJumpWithoutPhysicalGyroEvidenceDoesNotInvalidateTheMount(){
        assertFalse(PhoneMountMovementPolicy.suspect(rotationJumpDegrees=90.0,angularVelocityRadPerSecond=0.02))
        assertTrue(PhoneMountMovementPolicy.suspect(rotationJumpDegrees=90.0,angularVelocityRadPerSecond=0.8))
        assertTrue(PhoneMountMovementPolicy.suspect(rotationJumpDegrees=2.0,angularVelocityRadPerSecond=4.0))
    }

    @Test fun activeOutputMountWarningSuppressesOnlyUnsafeVesselFrameStreamsOnEveryTransport(){
        assertEquals("MOUNT_SUSPECT",PhoneOwnedRuntimeSafety.suppression(NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION,AnchorWatchNmeaStream.HEADING,PhoneVesselMountState.MOUNT_SUSPECT))
        assertEquals("MOUNT_SUSPECT",PhoneOwnedRuntimeSafety.suppression(NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION,AnchorWatchNmeaStream.MOTION,PhoneVesselMountState.MOUNT_SUSPECT))
        assertNull(PhoneOwnedRuntimeSafety.suppression(NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION,AnchorWatchNmeaStream.POSITION,PhoneVesselMountState.MOUNT_SUSPECT))
        assertNull(PhoneOwnedRuntimeSafety.suppression(NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION,AnchorWatchNmeaStream.PRESSURE,PhoneVesselMountState.MOUNT_SUSPECT))
        assertEquals("MOUNT_SUSPECT",PhoneOwnedRuntimeSafety.suppression(NmeaOutputTransportMode.DEDICATED_TCP,AnchorWatchNmeaStream.HEADING,PhoneVesselMountState.MOUNT_SUSPECT))
    }

    @Test fun runtimeMountWarningCannotTurnOffAnAlreadyStartedPublicationSession(){
        val blocked=PhoneVesselOutputReadinessPolicy.evaluate(
            VesselMountCalibration(),
            PhoneVesselMountState.MOUNT_SUSPECT,
        )
        assertTrue(FormalOutputSessionReadinessPolicy.blocksStart(requestedRunning=true,currentlyEnabled=false,readiness=blocked))
        assertFalse(FormalOutputSessionReadinessPolicy.blocksStart(requestedRunning=true,currentlyEnabled=true,readiness=blocked))
    }
}
