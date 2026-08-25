package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.database.entity.AnchoragePersonalAssessmentEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class AnchorageAssessmentCompatibilityTest {
    @Test fun legacyBackupStarsNormalizeIntoFinalCategories() {
        val value = AnchoragePersonalAssessmentEntity(
            placeId = 7,
            wouldReturn = "FAVORITE",
            holding = "5",
            comfort = "3",
            shoreAccess = "1",
            crowding = "",
            quietness = "2",
            notes = "kept",
            legacyOverallRating = 4,
            updatedAt = 100,
        ).normalized()
        assertEquals("UNKNOWN", value.wouldReturn)
        assertEquals("GOOD", value.holding)
        assertEquals("AVERAGE", value.comfort)
        assertEquals("POOR", value.shoreAccess)
        assertEquals("UNKNOWN", value.crowding)
        assertEquals("POOR", value.quietness)
        assertEquals(4, value.legacyOverallRating)
    }
}
