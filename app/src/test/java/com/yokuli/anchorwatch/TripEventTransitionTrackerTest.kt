package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.vessel.TripEventTransitionTracker
import com.yokuli.anchorwatch.domain.vessel.TripTransitionInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEventTransitionTrackerTest {
    private fun input(now:Long,nmea:Boolean=true,depth:Boolean=false,wind:Boolean=false,motion:Boolean=true,mount:Boolean=false,score:Double?=null)=TripTransitionInput(now,true,nmea,depth,depth,wind,wind,true,motion,mount,score)

    @Test fun availabilityEventsAreEdgesNotTickerSpam(){
        val tracker=TripEventTransitionTracker()
        assertTrue(tracker.update(input(0,nmea=true,depth=true,wind=true)).isEmpty())
        val lost=tracker.update(input(1_000,nmea=false,depth=false,wind=false,motion=false)).map{it.type}.toSet()
        assertEquals(setOf("NMEA_DATA_GAP","DEPTH_DATA_UNAVAILABLE","WIND_DATA_UNAVAILABLE","PHONE_MOTION_UNAVAILABLE"),lost)
        assertTrue(tracker.update(input(2_000,nmea=false,depth=false,wind=false,motion=false)).isEmpty())
        val restored=tracker.update(input(3_000,nmea=true,depth=true,wind=true,motion=true)).map{it.type}.toSet()
        assertEquals(setOf("NMEA_DATA_RESTORED","DEPTH_DATA_RESTORED","WIND_DATA_RESTORED","PHONE_MOTION_AVAILABLE"),restored)
    }

    @Test fun highMotionRequiresTenSecondsAndTwentySecondsOfCalmToClear(){
        val tracker=TripEventTransitionTracker();tracker.update(input(0,score=72.0))
        assertTrue(tracker.update(input(9_999,score=80.0)).isEmpty())
        assertEquals(listOf("HIGH_MOTION"),tracker.update(input(10_000,score=80.0)).map{it.type})
        tracker.update(input(11_000,score=40.0))
        assertTrue(tracker.update(input(30_999,score=40.0)).isEmpty())
        assertEquals(listOf("HIGH_MOTION_CLEARED"),tracker.update(input(31_000,score=40.0)).map{it.type})
    }

    @Test fun mountSuspectIsRecordedOnlyOnEntry(){
        val tracker=TripEventTransitionTracker();tracker.update(input(0))
        assertEquals(listOf("PHONE_MOUNT_SUSPECT"),tracker.update(input(1,mount=true)).map{it.type})
        assertTrue(tracker.update(input(2,mount=true)).isEmpty())
    }

    @Test fun expectedInstrumentStillReportsMissingAfterRuntimeEdgeReset(){
        val tracker=TripEventTransitionTracker()
        tracker.update(TripTransitionInput(0,true,true,true,true,true,true,true,true,false,null))
        tracker.reset()
        assertEquals(
            setOf("NMEA_DATA_GAP","DEPTH_DATA_UNAVAILABLE","WIND_DATA_UNAVAILABLE"),
            tracker.update(TripTransitionInput(1_000,true,false,true,false,true,false,true,true,false,null)).map{it.type}.toSet(),
        )
    }
}
