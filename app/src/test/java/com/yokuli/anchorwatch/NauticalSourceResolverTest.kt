package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.map.nautical.*
import com.yokuli.anchorwatch.map.style.BaseMapStyle
import org.junit.Assert.*
import org.junit.Test

class NauticalSourceResolverTest {
    @Test fun nauticalPrefersEnabledInstalledUserChart(){
        val value=NauticalSourceResolver.resolve(BaseMapStyle.NAUTICAL,true,true,"NZ North")
        assertTrue(value.active);assertTrue(value.userChartEnabled);assertEquals(NauticalPrimarySource.USER_MBTILES,value.primary)
    }
    @Test fun standardNauticalIsSafeFallback(){
        assertEquals(NauticalPrimarySource.STANDARD_NAUTICAL,NauticalSourceResolver.resolve(BaseMapStyle.NAUTICAL,false,true,null).primary)
        assertEquals(NauticalPrimarySource.STANDARD_NAUTICAL,NauticalSourceResolver.resolve(BaseMapStyle.NAUTICAL,true,false,"chart").primary)
    }
    @Test fun mapAndSatelliteNeverMountUserChart(){
        listOf(BaseMapStyle.MAP,BaseMapStyle.SATELLITE).forEach{style->
            val value=NauticalSourceResolver.resolve(style,true,true,"chart")
            assertFalse(value.active);assertFalse(value.userChartEnabled)
        }
    }
}
