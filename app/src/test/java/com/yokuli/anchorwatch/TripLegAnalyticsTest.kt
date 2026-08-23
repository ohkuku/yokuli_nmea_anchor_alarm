package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import com.yokuli.anchorwatch.domain.report.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripLegAnalyticsTest{
    @Test fun waypointsSplitStreamingStatisticsWithoutJoiningAcrossLegs(){
        val value=TripLegAccumulator(0,listOf(TripLegBoundary(10_000,"Cape")))
        fun point(at:Long,east:Double,sog:Double)=AnchorGeometry.project(-36.84,174.76,90.0,east).let{TripLegPoint(at,it.first,it.second,sog,sog-.5,12.0,-10.0)}
        value.add(point(0,0.0,4.0));value.add(point(5_000,20.0,6.0));value.add(point(10_000,100.0,7.0));value.add(point(15_000,130.0,9.0))
        val legs=value.summaries(20_000)
        assertEquals(2,legs.size);assertEquals("Start → Cape",legs[0].name);assertEquals("Cape → End",legs[1].name)
        assertTrue(legs[0].distanceMeters in 19.0..21.0);assertTrue(legs[1].distanceMeters in 29.0..31.0)
        assertEquals(5.0,legs[0].averageSogKnots!!,.001);assertEquals(8.0,legs[1].averageSogKnots!!,.001)
    }
}
