package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.vessel.*
import org.junit.Assert.*
import org.junit.Test

class PositionSourceConflictPolicyTest {
    private fun state(output:Boolean=false,selected:GpsDataSource=GpsDataSource.SYSTEM,active:GpsDataSource?=null)=PositionSourceConflictState(output,selected,active)

    @Test fun phoneOwnedOutputAndNmeaInputMayCoexistWithoutChangingEitherSource(){
        assertTrue(PositionSourceConflictPolicy.canSelectNmeaPosition(state()))
        assertTrue(PositionSourceConflictPolicy.canSelectNmeaPosition(state(output=true)))
        assertTrue(PositionSourceConflictPolicy.canEnablePhonePositionOutput(state(selected=GpsDataSource.NMEA)))
        assertTrue(PositionSourceConflictPolicy.canEnablePhonePositionOutput(state(active=GpsDataSource.NMEA)))
    }

    @Test fun nonPositionBoatDataRemainsAvailableDuringPhoneOutput(){
        assertTrue(PositionSourceConflictPolicy.canConsumeBoatNonPositionData(state(output=true)))
    }
}
