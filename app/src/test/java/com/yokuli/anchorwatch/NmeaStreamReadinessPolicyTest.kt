package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.vessel.NmeaStreamReadiness
import com.yokuli.anchorwatch.location.vessel.VesselMountCalibration
import com.yokuli.anchorwatch.runtime.output.NmeaStreamReadinessPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val aligned=legacy.copy(headingAlignmentCompletedAt=2)
        assertFalse(legacy.headingAligned)
        assertTrue(aligned.headingAligned)
    }
}
