package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.location.NewAnchorPositionSourcePolicy
import com.yokuli.anchorwatch.location.NmeaSourceAvailability
import com.yokuli.anchorwatch.location.NmeaSourceSelectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class NmeaSourceSelectionPolicyTest {
    private val now = 50_000L
    private val validFix = NavigationFix(
        latitude = -36.8485,
        longitude = 174.7633,
        receivedElapsedRealtime = now - 1_000,
        sourceSentence = "TEST",
        valid = true,
    )

    @Test fun disconnectedServerCannotBeSelected() {
        assertEquals(
            NmeaSourceAvailability.NOT_CONNECTED,
            NmeaSourceSelectionPolicy.availability(NmeaConnectionState.DISCONNECTED, validFix, now - 5_000, now, 15_000),
        )
    }

    @Test fun connectedServerWithoutAValidPositionCannotBeSelected() {
        assertEquals(
            NmeaSourceAvailability.NO_VALID_FIX,
            NmeaSourceSelectionPolicy.availability(NmeaConnectionState.CONNECTED, null, now - 5_000, now, 15_000),
        )
    }

    @Test fun stalePositionCannotBeSelected() {
        assertEquals(
            NmeaSourceAvailability.STALE_FIX,
            NmeaSourceSelectionPolicy.availability(
                NmeaConnectionState.CONNECTED,
                validFix.copy(receivedElapsedRealtime = now - 20_000),
                now - 30_000,
                now,
                15_000,
            ),
        )
    }

    @Test fun connectedServerWithAFreshValidPositionCanBeSelected() {
        assertEquals(
            NmeaSourceAvailability.AVAILABLE,
            NmeaSourceSelectionPolicy.availability(NmeaConnectionState.CONNECTED, validFix, now - 5_000, now, 15_000),
        )
    }

    @Test fun aFixFromBeforeTheCurrentConnectionCannotBeSelected() {
        assertEquals(
            NmeaSourceAvailability.NO_VALID_FIX,
            NmeaSourceSelectionPolicy.availability(NmeaConnectionState.CONNECTED, validFix, now, now, 15_000),
        )
    }

    @Test fun aConnectedFreshFixWithExplicitlyBadQualityIsNotUsableForSafetyFeatures() {
        assertEquals(
            false,
            NmeaSourceSelectionPolicy.isUsablePosition(
                NmeaConnectionState.CONNECTED,
                validFix.copy(hdop = 7.0),
                now - 5_000,
                now,
                15_000,
            ),
        )
        assertEquals(
            false,
            NmeaSourceSelectionPolicy.isUsablePosition(
                NmeaConnectionState.CONNECTED,
                validFix.copy(fixQuality = 0),
                now - 5_000,
                now,
                15_000,
            ),
        )
    }

    @Test fun unknownQualityMayContinueButOnlyOnTheCurrentFreshConnection() {
        assertEquals(
            true,
            NmeaSourceSelectionPolicy.isUsablePosition(
                NmeaConnectionState.CONNECTED,
                validFix,
                now - 5_000,
                now,
                15_000,
            ),
        )
    }

    @Test fun instrumentOnlyNmeaFallsBackToPhoneGpsForANewAnchor() {
        assertEquals(
            GpsDataSource.SYSTEM,
            NewAnchorPositionSourcePolicy.resolve(
                configuredSource = GpsDataSource.NMEA,
                demoMode = false,
                nmeaPositionUsable = false,
            ),
        )
    }

    @Test fun freshNmeaPositionRemainsPreferredForANewAnchor() {
        assertEquals(
            GpsDataSource.NMEA,
            NewAnchorPositionSourcePolicy.resolve(
                configuredSource = GpsDataSource.NMEA,
                demoMode = false,
                nmeaPositionUsable = true,
            ),
        )
    }

    @Test fun fallbackDoesNotChangeAnExplicitPhoneGpsChoice() {
        assertEquals(
            GpsDataSource.SYSTEM,
            NewAnchorPositionSourcePolicy.resolve(
                configuredSource = GpsDataSource.SYSTEM,
                demoMode = false,
                nmeaPositionUsable = false,
            ),
        )
    }

    @Test fun demoModeKeepsItsLockedDemoSource() {
        assertEquals(
            GpsDataSource.DEMO,
            NewAnchorPositionSourcePolicy.resolve(
                configuredSource = GpsDataSource.NMEA,
                demoMode = true,
                nmeaPositionUsable = false,
            ),
        )
    }
}
