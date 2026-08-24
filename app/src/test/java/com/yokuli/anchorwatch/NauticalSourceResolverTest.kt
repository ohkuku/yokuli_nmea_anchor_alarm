package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.map.nautical.*
import com.yokuli.anchorwatch.map.style.BaseMapStyle
import org.junit.Assert.*
import org.junit.Test

class NauticalSourceResolverTest {
    @Test fun nauticalPrefersEnabledInstalledUserChart(){
        val value=NauticalSourceResolver.resolve(BaseMapStyle.NAUTICAL,true,NauticalSourcePreference.USER_MBTILES,"NZ North")
        assertTrue(value.active);assertTrue(value.userChartEnabled);assertEquals(NauticalPrimarySource.USER_MBTILES,value.primary)
    }
    @Test fun defaultOnlineIsSafeFallbackWhenMissingUnselectedOrRemoved(){
        assertEquals(NauticalPrimarySource.STANDARD_NAUTICAL,NauticalSourceResolver.resolve(BaseMapStyle.NAUTICAL,false,NauticalSourcePreference.USER_MBTILES,null).primary)
        assertEquals(NauticalPrimarySource.STANDARD_NAUTICAL,NauticalSourceResolver.resolve(BaseMapStyle.NAUTICAL,true,NauticalSourcePreference.DEFAULT_ONLINE,"chart").primary)
        val removed=NauticalSourceResolver.resolve(BaseMapStyle.NAUTICAL,false,NauticalSourcePreference.USER_MBTILES,"deleted chart")
        assertTrue(removed.active);assertFalse(removed.userChartEnabled);assertNull(removed.userChartName)
    }
    @Test fun mapAndSatelliteNeverMountUserChart(){
        listOf(BaseMapStyle.STANDARD,BaseMapStyle.SATELLITE).forEach{style->
            val value=NauticalSourceResolver.resolve(style,true,NauticalSourcePreference.USER_MBTILES,"chart")
            assertFalse(value.active);assertFalse(value.userChartEnabled)
        }
    }

    @Test fun independentOverlaysDoNotChangeThePrimaryNauticalSource(){
        listOf(false,true).forEach{linz->listOf(false,true).forEach{sonar->
            val overlays=MapOverlayPreferences(linz,.7,sonar,true,true)
            val source=NauticalSourceResolver.resolve(BaseMapStyle.NAUTICAL,true,NauticalSourcePreference.USER_MBTILES,"chart")
            assertEquals(NauticalPrimarySource.USER_MBTILES,source.primary)
            assertEquals(linz,overlays.linzNzChartEnabled);assertEquals(sonar,overlays.personalSonarEnabled)
        }}
    }
}
