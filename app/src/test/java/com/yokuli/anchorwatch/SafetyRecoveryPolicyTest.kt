package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.model.AlarmType
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.safety.SafetyRecoveryDestination
import com.yokuli.anchorwatch.domain.safety.SafetyRecoveryPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class SafetyRecoveryPolicyTest {
    @Test fun nmeaPositionLossOpensNmeaRecovery() {
        assertEquals(
            SafetyRecoveryDestination.NMEA,
            SafetyRecoveryPolicy.destination(GpsDataSource.NMEA, AlarmType.GPS_DATA_LOST, false),
        )
    }

    @Test fun systemPositionLossNeverPretendsToBeAnNmeaFailure() {
        assertEquals(
            SafetyRecoveryDestination.SYSTEM_GPS,
            SafetyRecoveryPolicy.destination(GpsDataSource.SYSTEM, AlarmType.GPS_DATA_LOST, false),
        )
        assertEquals(
            SafetyRecoveryDestination.SYSTEM_GPS,
            SafetyRecoveryPolicy.destination(GpsDataSource.SYSTEM, AlarmType.GPS_QUALITY_BAD, false),
        )
    }

    @Test fun instrumentLossAlwaysReturnsToTheBoatNmeaStream() {
        assertEquals(
            SafetyRecoveryDestination.NMEA,
            SafetyRecoveryPolicy.destination(GpsDataSource.SYSTEM, AlarmType.GPS_QUALITY_BAD, true),
        )
    }

    @Test fun demoFailureDoesNotOfferARealSourceRecoveryShortcut() {
        assertEquals(
            SafetyRecoveryDestination.NONE,
            SafetyRecoveryPolicy.destination(GpsDataSource.DEMO, AlarmType.GPS_DATA_LOST, false),
        )
    }
}
