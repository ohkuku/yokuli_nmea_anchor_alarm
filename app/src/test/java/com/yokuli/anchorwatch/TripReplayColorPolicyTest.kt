package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.trip.*
import org.junit.Assert.assertEquals
import org.junit.Test

class TripReplayColorPolicyTest {
    private fun point(sog:Double?=null,bsp:Double?=null,heel:Double?=null,depth:Double?=null,tws:Double?=null,aws:Double?=null,motion:Double?=null)=TripReplayPoint(1,null,null,sog,null,null,depth,bsp,tws,aws,heel,motion)
    @Test fun replayModesUseExplicitThresholdsAndUnknownBucket(){
        assertEquals(2,TripReplayPolicy.colorBucket(point(sog=6.0),TripReplayColorMode.SOG))
        assertEquals(1,TripReplayPolicy.colorBucket(point(bsp=4.0),TripReplayColorMode.BSP))
        assertEquals(2,TripReplayPolicy.colorBucket(point(heel=-25.0),TripReplayColorMode.HEEL))
        assertEquals(2,TripReplayPolicy.colorBucket(point(depth=2.0),TripReplayColorMode.DEPTH))
        assertEquals(2,TripReplayPolicy.colorBucket(point(tws=26.0),TripReplayColorMode.TWS))
        assertEquals(1,TripReplayPolicy.colorBucket(point(aws=18.0),TripReplayColorMode.AWS))
        assertEquals(2,TripReplayPolicy.colorBucket(point(motion=70.0),TripReplayColorMode.MOTION))
        assertEquals(-1,TripReplayPolicy.colorBucket(point(),TripReplayColorMode.TWS))
    }
}
