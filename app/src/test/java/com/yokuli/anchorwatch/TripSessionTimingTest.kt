package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.trip.TripSessionTiming
import org.junit.Assert.assertEquals
import org.junit.Test

class TripSessionTimingTest{
    @Test fun endingWhilePausedIncludesTheStillOpenPause(){assertEquals(15_000L,TripSessionTiming.pendingPausedMillis(10_000L,25_000L))}
    @Test fun clockRollbackCannotCreateNegativePause(){assertEquals(0L,TripSessionTiming.pendingPausedMillis(20_000L,10_000L));assertEquals(0L,TripSessionTiming.pendingPausedMillis(null,10_000L))}
}
