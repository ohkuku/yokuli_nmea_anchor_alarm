package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.anchorage.AnchorageShareContent
import com.yokuli.anchorwatch.data.database.SavedAnchorageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorageShareContentTest{
    @Test fun googleMapsQrPayloadUsesStableLocaleIndependentCoordinates(){
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=-36.8123456,174.7123456",
            AnchorageShareContent.googleMapsUrl(-36.8123456,174.7123456),
        )
    }

    @Test fun shareTextContainsNameCoordinatesNotesAndScannableMapUrl(){
        val saved=SavedAnchorageEntity(name="Little Bay",latitude=-36.8,longitude=175.1,createdAt=1,updatedAt=1,notes="Mud, good holding")
        val text=AnchorageShareContent.shareText(saved)
        assertTrue(text.contains("Little Bay"))
        assertTrue(text.contains("-36.8000000,175.1000000"))
        assertTrue(text.contains("Mud, good holding"))
        assertTrue(text.contains("https://www.google.com/maps/search/?api=1&query="))
    }
}
