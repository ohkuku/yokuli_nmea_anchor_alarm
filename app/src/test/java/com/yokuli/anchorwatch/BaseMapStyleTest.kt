package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.map.style.BaseMapStyle
import com.yokuli.anchorwatch.map.style.GoogleBaseMapKind
import com.yokuli.anchorwatch.map.style.MapStylePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseMapStyleTest {
    @Test fun persistedValuesAreStableAndUnknownValuesFallBackToMap() {
        assertEquals(BaseMapStyle.MAP, BaseMapStyle.fromPersisted(1))
        assertEquals(BaseMapStyle.SATELLITE, BaseMapStyle.fromPersisted(2))
        assertEquals(BaseMapStyle.NAUTICAL, BaseMapStyle.fromPersisted(3))
        assertEquals(BaseMapStyle.MAP, BaseMapStyle.fromPersisted(-1))
        assertEquals(BaseMapStyle.MAP, BaseMapStyle.fromPersisted(99))
    }

    @Test fun eachStyleHasAnExclusiveRenderPolicyAndLeavingNauticalResetsIt() {
        val nautical = MapStylePolicy.forStyle(BaseMapStyle.NAUTICAL)
        assertEquals(GoogleBaseMapKind.NORMAL, nautical.googleBaseMap)
        assertTrue(nautical.applyNauticalStyle)
        assertTrue(nautical.showSeamarks)

        val map = MapStylePolicy.forStyle(BaseMapStyle.MAP)
        assertEquals(GoogleBaseMapKind.NORMAL, map.googleBaseMap)
        assertFalse(map.applyNauticalStyle)
        assertFalse(map.showSeamarks)

        val satellite = MapStylePolicy.forStyle(BaseMapStyle.SATELLITE)
        assertEquals(GoogleBaseMapKind.SATELLITE, satellite.googleBaseMap)
        assertFalse(satellite.applyNauticalStyle)
        assertFalse(satellite.showSeamarks)
    }
}
