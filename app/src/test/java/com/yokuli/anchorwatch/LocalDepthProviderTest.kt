package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.map.localdepth.GeoPoint
import com.yokuli.anchorwatch.map.localdepth.LinzNewZealandDepthProvider
import com.yokuli.anchorwatch.map.localdepth.LocalDepthAvailability
import com.yokuli.anchorwatch.map.localdepth.MapChartPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDepthProviderTest {
    @Test fun linzCoverageIncludesNewZealandButNotUnrelatedRegions() {
        listOf(
            GeoPoint(-36.8509, 174.7645), // Auckland
            GeoPoint(-36.7613, 175.4964), // Coromandel
            GeoPoint(-41.2866, 174.7756), // Wellington
            GeoPoint(-43.95, -176.55), // Chatham Islands
        ).forEach { assertTrue("Expected LINZ coverage at $it", LinzNewZealandDepthProvider.supports(it)) }
        listOf(
            GeoPoint(-33.8688, 151.2093), // Sydney
            GeoPoint(25.0330, 121.5654), // Taipei
            GeoPoint(37.7749, -122.4194), // San Francisco
        ).forEach { assertFalse("Unexpected LINZ coverage at $it", LinzNewZealandDepthProvider.supports(it)) }
    }

    @Test fun lockedMapUsesBoatWhileUnlockedMapUsesInspectionCamera() {
        val auckland = GeoPoint(-36.8509, 174.7645)
        val sydney = GeoPoint(-33.8688, 151.2093)
        val locked = MapChartPolicy.resolve(true, auckland, sydney, true, true, .70)
        assertTrue(locked.availability is LocalDepthAvailability.Available)
        assertTrue(locked.localDepthVisible)
        val unlocked = MapChartPolicy.resolve(false, auckland, sydney, true, true, .70)
        assertEquals(LocalDepthAvailability.UnsupportedArea, unlocked.availability)
        assertFalse(unlocked.localDepthVisible)
    }

    @Test fun unsupportedAreaPreservesPreferenceAndReturningMakesLayerVisibleAgain() {
        val sydney = MapChartPolicy.resolve(false, null, GeoPoint(-33.8688, 151.2093), true, true, .66)
        assertTrue(sydney.localDepthPreferenceEnabled)
        assertFalse(sydney.localDepthVisible)
        val auckland = MapChartPolicy.resolve(false, null, GeoPoint(-36.8509, 174.7645), true, sydney.localDepthPreferenceEnabled, sydney.localDepthOpacity)
        assertTrue(auckland.localDepthVisible)
        assertEquals(.66, auckland.localDepthOpacity, .0001)
    }

    @Test fun positionAndBuildConfigurationProduceExplicitAvailabilityStates() {
        assertEquals(
            LocalDepthAvailability.PositionUnknown,
            MapChartPolicy.resolve(true, null, null, true, false, .70).availability,
        )
        val notConfigured = MapChartPolicy.resolve(true, GeoPoint(-36.8509, 174.7645), null, false, true, .70)
        assertTrue(notConfigured.availability is LocalDepthAvailability.ProviderNotConfigured)
        assertFalse(notConfigured.localDepthVisible)
    }
}
