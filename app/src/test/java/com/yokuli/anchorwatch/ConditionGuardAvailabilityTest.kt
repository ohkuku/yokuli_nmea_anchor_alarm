package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.condition.ConditionGuardAvailability
import com.yokuli.anchorwatch.domain.condition.ConditionGuardConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionGuardAvailabilityTest{
    private val depth=ConditionGuardConfig(depthGuardEnabled=true,shallowDepthAlarmMeters=2.5)

    @Test fun disconnectedNmeaCannotEnableANewGuard(){
        assertFalse(ConditionGuardAvailability.canApply(ConditionGuardConfig(),depth,nmeaConnected=false,demoSession=false))
    }

    @Test fun disconnectedNmeaCanDisableAnExistingGuard(){
        assertTrue(ConditionGuardAvailability.canApply(depth,ConditionGuardConfig(),nmeaConnected=false,demoSession=false))
    }

    @Test fun disconnectedNmeaCannotAddADeepBoundaryOrEditThresholdsOnAnArmedGuard(){
        assertFalse(ConditionGuardAvailability.canApply(depth,depth.copy(deepDepthAlarmMeters=15.0),nmeaConnected=false,demoSession=false))
        assertFalse(ConditionGuardAvailability.canApply(depth,depth.copy(shallowDepthAlarmMeters=3.0),nmeaConnected=false,demoSession=false))
    }

    @Test fun connectedNmeaOrDemoCanEnableGuard(){
        assertTrue(ConditionGuardAvailability.canApply(ConditionGuardConfig(),depth,nmeaConnected=true,demoSession=false))
        assertTrue(ConditionGuardAvailability.canApply(ConditionGuardConfig(),depth,nmeaConnected=false,demoSession=true))
    }
}
