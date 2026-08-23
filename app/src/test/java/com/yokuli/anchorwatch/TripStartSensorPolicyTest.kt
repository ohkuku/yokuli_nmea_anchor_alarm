package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.vessel.TripStartSensorPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripStartSensorPolicyTest {
    @Test fun calibratedAvailableSensorCanBeRecorded(){assertTrue(TripStartSensorPolicy.phoneMotionEnabled(true,true,1L))}
    @Test fun uncalibratedPhoneIsNeverSilentlyTreatedAsNeutral(){assertFalse(TripStartSensorPolicy.phoneMotionEnabled(true,true,0L))}
    @Test fun unavailableOrUnrequestedMotionStaysOff(){assertFalse(TripStartSensorPolicy.phoneMotionEnabled(true,false,1L));assertFalse(TripStartSensorPolicy.phoneMotionEnabled(false,true,1L))}
}
