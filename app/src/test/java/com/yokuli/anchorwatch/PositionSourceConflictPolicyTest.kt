package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.vessel.*
import org.junit.Assert.*
import org.junit.Test

class PositionSourceConflictPolicyTest {
    private fun state(output:Boolean=false,selected:GpsDataSource=GpsDataSource.SYSTEM,active:GpsDataSource?=null)=PositionSourceConflictState(output,selected,active)

    @Test fun phoneOutputAndNmeaPositionAreMutuallyExclusive(){
        assertTrue(PositionSourceConflictPolicy.canSelectNmeaPosition(state()))
        assertFalse(PositionSourceConflictPolicy.canSelectNmeaPosition(state(output=true)))
        assertFalse(PositionSourceConflictPolicy.canEnablePhonePositionOutput(state(selected=GpsDataSource.NMEA)))
        assertFalse(PositionSourceConflictPolicy.canEnablePhonePositionOutput(state(active=GpsDataSource.NMEA)))
    }

    @Test fun nonPositionBoatDataRemainsAvailableDuringPhoneOutput(){
        assertTrue(PositionSourceConflictPolicy.canConsumeBoatNonPositionData(state(output=true)))
    }
}
