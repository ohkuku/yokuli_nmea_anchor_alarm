package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.map.MapDistanceTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapDistanceToolsTest {
    @Test fun scaleShrinksAsTheMapZoomsIn() {
        val wide = MapDistanceTools.scaleBar(-36.85, 12f, 100f)
        val close = MapDistanceTools.scaleBar(-36.85, 18f, 100f)
        assertTrue(wide.distanceMeters > close.distanceMeters)
        assertTrue(wide.widthPixels in 1f..100f)
        assertTrue(close.widthPixels in 1f..100f)
    }

    @Test fun distanceUsesMarineAndMetricUnitsWithoutAmbiguity() {
        assertEquals("500 m", MapDistanceTools.measurementLabel(500.9))
        assertEquals("1 NM · 1.85 km", MapDistanceTools.measurementLabel(1_852.0))
    }

    @Test fun aucklandHarbourDistanceIsPlausible() {
        val result = MapDistanceTools.distanceMeters(-36.8420, 174.7510, -36.8420, 174.7622)
        assertTrue(result in 990.0..1_010.0)
    }

    @Test fun rulerReportsBearingAndStableMidpoint(){
        assertEquals(90.0,MapDistanceTools.initialBearingDegrees(0.0,0.0,0.0,1.0),.01)
        val midpoint=MapDistanceTools.midpoint(-36.0,174.0,-38.0,176.0)
        assertEquals(-37.0,midpoint.first,.0001);assertEquals(175.0,midpoint.second,.0001)
    }

    @Test fun wholeRulerTranslationPreservesDistanceAndBearing(){
        val beforeDistance=MapDistanceTools.distanceMeters(-36.85,174.76,-36.845,174.77)
        val beforeBearing=MapDistanceTools.initialBearingDegrees(-36.85,174.76,-36.845,174.77)
        val moved=MapDistanceTools.translateRuler(-36.85,174.76,-36.845,174.77,-35.0,175.0)
        val afterDistance=MapDistanceTools.distanceMeters(moved.first.first,moved.first.second,moved.second.first,moved.second.second)
        val afterBearing=MapDistanceTools.initialBearingDegrees(moved.first.first,moved.first.second,moved.second.first,moved.second.second)
        assertEquals(beforeDistance,afterDistance,.05)
        assertEquals(beforeBearing,afterBearing,.01)
    }
}
