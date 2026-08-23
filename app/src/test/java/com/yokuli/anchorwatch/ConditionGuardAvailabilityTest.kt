package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.condition.ConditionGuardAvailability
import com.yokuli.anchorwatch.domain.condition.ConditionGuardConfig
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionGuardAvailabilityTest{
    private val depth=ConditionGuardConfig(depthGuardEnabled=true,shallowDepthAlarmMeters=2.5)
    private val none=ConditionGuardAvailability.Sensors(false,false,false,false)
    private val all=ConditionGuardAvailability.Sensors(true,true,true,true)

    @Test fun disconnectedNmeaCannotEnableANewGuard(){
        assertFalse(ConditionGuardAvailability.canApply(ConditionGuardConfig(),depth,none))
    }

    @Test fun disconnectedNmeaCanDisableAnExistingGuard(){
        assertTrue(ConditionGuardAvailability.canApply(depth,ConditionGuardConfig(),none))
    }

    @Test fun disconnectedNmeaCannotAddADeepBoundaryOrEditThresholdsOnAnArmedGuard(){
        assertFalse(ConditionGuardAvailability.canApply(depth,depth.copy(deepDepthAlarmMeters=15.0),none))
        assertFalse(ConditionGuardAvailability.canApply(depth,depth.copy(shallowDepthAlarmMeters=3.0),none))
    }

    @Test fun unavailableSounderDoesNotTrapTheOptionalDeepBoundaryOn(){
        val withDeep=depth.copy(deepDepthAlarmMeters=15.0)
        assertTrue(ConditionGuardAvailability.canApply(withDeep,withDeep.copy(deepDepthAlarmMeters=null),none))
    }

    @Test fun connectedNmeaOrDemoCanEnableGuard(){
        assertTrue(ConditionGuardAvailability.canApply(ConditionGuardConfig(),depth,all))
        assertTrue(ConditionGuardAvailability.canApply(ConditionGuardConfig(),depth,none.copy(demoSession=true)))
    }

    @Test fun unrelatedNmeaTrafficCannotEnableMissingSensorGuard(){
        val trafficOnly=ConditionGuardAvailability.Sensors(true,false,false,false)
        assertFalse(ConditionGuardAvailability.canApply(ConditionGuardConfig(),depth,trafficOnly))
        assertFalse(ConditionGuardAvailability.canApply(ConditionGuardConfig(),ConditionGuardConfig(windGuardEnabled=true,windWarningKnots=20.0,windAlarmKnots=30.0),trafficOnly))
        assertFalse(ConditionGuardAvailability.canApply(ConditionGuardConfig(),ConditionGuardConfig(windShiftEnabled=true,windShiftThresholdDegrees=45.0),trafficOnly))
    }

    @Test fun eachFreshSensorOnlyUnlocksItsOwnGuard(){
        val depthOnly=ConditionGuardAvailability.Sensors(true,true,false,false)
        assertTrue(ConditionGuardAvailability.canApply(ConditionGuardConfig(),depth,depthOnly))
        assertFalse(ConditionGuardAvailability.canApply(ConditionGuardConfig(),ConditionGuardConfig(windGuardEnabled=true,windWarningKnots=20.0,windAlarmKnots=30.0),depthOnly))
    }

    @Test fun instrumentTrafficDoesNotRequireAnNmeaGpsFix(){
        assertTrue(ConditionGuardAvailability.hasInstrumentTraffic(NmeaConnectionState.CONNECTED))
        assertTrue(ConditionGuardAvailability.hasInstrumentTraffic(NmeaConnectionState.CONNECTED_NO_FIX))
        assertTrue(ConditionGuardAvailability.hasInstrumentTraffic(NmeaConnectionState.STALE))
        assertFalse(ConditionGuardAvailability.hasInstrumentTraffic(NmeaConnectionState.CONNECTED_NO_DATA))
        assertFalse(ConditionGuardAvailability.hasInstrumentTraffic(NmeaConnectionState.DISCONNECTED))
    }
}
