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
}
