package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.trip.TripReplayPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripReplayPolicyTest{
    @Test fun eventNavigationUsesNearestAlreadyLoadedReplayPoint(){val times=listOf(1_000L,2_000L,3_000L);assertEquals(0,TripReplayPolicy.nearestIndex(times,1_100));assertEquals(2,TripReplayPolicy.nearestIndex(times,2_700))}
    @Test fun eventNavigationHandlesEmptyAndOuterBounds(){assertNull(TripReplayPolicy.nearestIndex(emptyList(),1));assertEquals(0,TripReplayPolicy.nearestIndex(listOf(10L,20L),0));assertEquals(1,TripReplayPolicy.nearestIndex(listOf(10L,20L),30))}
}
