package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.anchorage.AnchorageShareContent
import com.yokuli.anchorwatch.data.database.SavedAnchorageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorageShareContentTest{
    @Test fun shareImageUsesTheRequestedYokuliBrandLine(){
        assertEquals("Made aboard Yokuli",AnchorageShareContent.BRANDING_LINE)
    }

    @Test fun googleMapsQrPayloadUsesStableLocaleIndependentCoordinates(){
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=-36.8123456,174.7123456",
            AnchorageShareContent.googleMapsUrl(-36.8123456,174.7123456),
        )
    }

    @Test fun shareTextContainsNameCoordinatesNotesAndScannableMapUrl(){
        val saved=SavedAnchorageEntity(name="Little Bay",latitude=-36.8,longitude=175.1,createdAt=1,updatedAt=1,preferredAlarmRadiusMeters=50.0,typicalWaterDepthMeters=6.1,typicalRodeLengthMeters=40.0,seabedType="MUD",rating=4,notes="Mud, good holding")
        val text=AnchorageShareContent.shareText(saved)
        assertTrue(text.contains("Little Bay"))
        assertTrue(text.contains("-36.8000000,175.1000000"))
        assertTrue(text.contains("Mud, good holding"))
        assertTrue(text.contains("Saved radius: 50 m"))
        assertTrue(text.contains("Depth: 6.1 m"))
        assertTrue(text.contains("Rode: 40 m"))
        assertTrue(text.contains("Seabed: Mud"))
        assertTrue(text.contains("Rating: 4/5"))
        assertTrue(text.contains("https://www.google.com/maps/search/?api=1&query="))
    }
}
