package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.anchorage.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorageExperienceStateTest {
    @Test fun farApproachNeverAlsoBecomesNearbyAtOneNauticalMile() {
        val approach = AnchorageExperienceReducer.reduce(
            AnchorageExperienceState.Browsing,
            AnchorageExperienceEvent.StartApproach(7, 11, 22, 100),
        )
        val inside = AnchorageExperienceReducer.reduce(approach, AnchorageExperienceEvent.NearbyDetected(7, setOf(11, 12)))
        assertEquals(approach, inside)
    }

    @Test fun arrivalReplacesApproachAndNearby() {
        val approach = AnchorageExperienceState.Approaching(7, 11, 22, 100)
        assertEquals(AnchorageExperienceState.Arrived(7, 11, 22), AnchorageExperienceReducer.reduce(approach, AnchorageExperienceEvent.TargetAreaEntered))
    }

    @Test fun cancellingInsideSuppressesUntilThePlaceLeavesRearmZone() {
        val cancelled = AnchorageExperienceReducer.reduce(
            AnchorageExperienceState.Approaching(7, 11, 22, 100),
            AnchorageExperienceEvent.CancelApproach(setOf(11)),
        )
        assertEquals(AnchorageExperienceState.DepartureCooldown(setOf(11)), cancelled)
        assertEquals(cancelled, AnchorageExperienceReducer.reduce(cancelled, AnchorageExperienceEvent.NearbyDetected(8, setOf(11))))
        assertEquals(AnchorageExperienceState.Browsing, AnchorageExperienceReducer.reduce(cancelled, AnchorageExperienceEvent.RearmZoneExited(setOf(11))))
    }

    @Test fun activeAnchorSuppressesDiscoveryAndLiftInsideStartsCooldown() {
        val anchored = AnchorageExperienceState.Anchored(11, 22, 99)
        assertEquals(anchored, AnchorageExperienceReducer.reduce(anchored, AnchorageExperienceEvent.NearbyDetected(8, setOf(11))))
        assertEquals(AnchorageExperienceState.DepartureCooldown(setOf(11)), AnchorageExperienceReducer.reduce(anchored, AnchorageExperienceEvent.AnchorLifted(setOf(11))))
    }

    @Test fun snapshotRestoresStablePlaceAndSpotIdentity() {
        val original = AnchorageExperienceState.Approaching(7, 11, 22, 1234)
        assertEquals(original, AnchorageExperienceSnapshotCodec.decode(AnchorageExperienceSnapshotCodec.encode(original)))
    }

    @Test fun malformedSnapshotFailsClosedToBrowsing() {
        assertTrue(AnchorageExperienceSnapshotCodec.decode(AnchorageExperienceSnapshot("APPROACHING")) is AnchorageExperienceState.Browsing)
    }
}
