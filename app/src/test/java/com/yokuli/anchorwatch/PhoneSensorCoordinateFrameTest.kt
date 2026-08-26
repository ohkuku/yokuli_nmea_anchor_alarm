package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.location.vessel.DeviceBowAxis
import com.yokuli.anchorwatch.location.vessel.PhoneVesselAttitudeFrame
import com.yokuli.anchorwatch.location.vessel.PhoneVesselMountState
import com.yokuli.anchorwatch.location.vessel.SensorQuaternion
import com.yokuli.anchorwatch.location.vessel.VesselMountCalibration
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSensorCoordinateFrameTest {
    @Test fun confirmingWhileVesselIsHeeledDoesNotZeroTheCurrentHeel(){
        val halfAngle=Math.toRadians(20.0)/2.0
        val current=SensorQuaternion(cos(halfAngle),sin(halfAngle),0.0,0.0)
        val attitude=PhoneVesselAttitudeFrame.resolve(current,DoubleArray(3),DeviceBowAxis.TOP)
        assertEquals(20.0,attitude.heelDegrees,0.01)
        assertEquals(0.0,attitude.pitchDegrees,0.01)
    }

    @Test fun headingAlignmentSurvivesAttitudeSegmentReconfirmationAndInvalidation(){
        val aligned=VesselMountCalibration(
            version=3,
            calibratedAt=1_000,
            mountState=PhoneVesselMountState.VESSEL_MOUNTED,
            mountConfirmedVersion=3,
            headingAlignmentCompletedAt=500,
            headingAlignmentVersion=1,
        )
        assertTrue(aligned.headingAligned)
        assertTrue(aligned.attitudeFrameConfirmed)

        val moved=aligned.copy(attitudeInvalidatedAt=2_000,mountState=PhoneVesselMountState.MOUNT_SUSPECT)
        assertTrue(moved.headingAligned)
        assertFalse(moved.attitudeFrameConfirmed)

        val nextTripFrame=moved.copy(version=4,calibratedAt=3_000,attitudeInvalidatedAt=0,mountState=PhoneVesselMountState.HANDHELD,mountConfirmedVersion=0)
        assertTrue(nextTripFrame.headingAligned)
        assertTrue(nextTripFrame.attitudeFrameConfirmed)
    }
}
