package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.map.LinzHydroConfiguration
import com.yokuli.anchorwatch.map.LinzHydroTileProvider
import com.yokuli.anchorwatch.map.MapOverlayZ
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinzHydroConfigurationTest {
    @Test fun requiresHttpsAndAllXyzPlaceholders(){
        assertTrue(LinzHydroConfiguration.isUsable("https://tiles/{z}/{x}/{y}.png"))
        assertFalse(LinzHydroConfiguration.isUsable("http://tiles/{z}/{x}/{y}.png"))
        assertFalse(LinzHydroConfiguration.isUsable("https://tiles/{z}/{x}.png"))
    }

    @Test fun expandsCoordinatesAndStableSubdomain(){
        val url=LinzHydroConfiguration.tileUrl("https://{s}.tiles/{z}/{x}/{y}.png",7,8,9)
        assertEquals("https://a.tiles/9/7/8.png",url)
    }

    @Test fun opacityMapsToGoogleTransparencyAndAttributionFollowsVisibility(){
        assertEquals(.30f,LinzHydroConfiguration.transparency(.70),.0001f)
        assertEquals(.0f,LinzHydroConfiguration.transparency(5.0),.0001f)
        assertEquals(.70f,LinzHydroConfiguration.transparency(-1.0),.0001f)
        assertTrue(LinzHydroConfiguration.isOverlayVisible(configured=true,enabled=true))
        assertFalse(LinzHydroConfiguration.isOverlayVisible(configured=false,enabled=true))
        assertFalse(LinzHydroConfiguration.isOverlayVisible(configured=true,enabled=false))
        assertEquals("LINZ · CC BY 4.0",LinzHydroConfiguration.ATTRIBUTION)
    }

    @Test fun tileProviderRejectsOutOfRangeCoordinatesAndZoom(){
        val provider=LinzHydroTileProvider("https://tiles/{z}/{x}/{y}.png")
        assertTrue(provider.getTileUrl(0,0,0)!=null)
        assertTrue(provider.getTileUrl(2,0,1)==null)
        assertTrue(provider.getTileUrl(0,2,1)==null)
        assertTrue(provider.getTileUrl(0,0,25)==null)
    }

    @Test fun chartCannotCoverYokuliSafetyOverlays(){
        assertTrue(MapOverlayZ.LINZ_CHART<MapOverlayZ.SONAR)
        assertTrue(MapOverlayZ.SONAR<MapOverlayZ.NAUTICAL_SEAMARKS)
        assertTrue(MapOverlayZ.NAUTICAL_SEAMARKS<MapOverlayZ.TRAIL)
        assertTrue(MapOverlayZ.TRAIL<MapOverlayZ.ALARM_GEOMETRY)
        assertTrue(MapOverlayZ.ALARM_GEOMETRY<MapOverlayZ.ANCHOR)
        assertTrue(MapOverlayZ.ANCHOR<MapOverlayZ.BOAT)
    }
}
