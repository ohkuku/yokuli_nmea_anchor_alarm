package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.database.TrackPointEntity
import com.yokuli.anchorwatch.map.TrailVisibilityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailVisibilityPolicyTest {
    private fun point(index: Int, timestamp: Long) = TrackPointEntity(
        sessionId = 1,
        timestamp = timestamp,
        latitude = index / 110_540.0,
        longitude = 0.0,
        distanceFromAnchor = index.toDouble(),
        sog = null,
        cog = null,
        heading = null,
        hdop = 1.0,
    )

    @Test fun keepsTheLatestTwentyFourHoursInsteadOfAFixedSampleCount() {
        val hour = 60L * 60L * 1_000L
        val points = (0..30).map { point(it, it * hour) }
        val visible = TrailVisibilityPolicy.visiblePoints(points)
        assertEquals(6L * hour, visible.first().timestamp)
        assertEquals(30L * hour, visible.last().timestamp)
    }

    @Test fun renderingIsBoundedWithoutDroppingTheNewestPoint() {
        val points = (0..10_000).map { point(it, it * 1_000L) }
        val visible = TrailVisibilityPolicy.visiblePoints(points)
        assertTrue(visible.size <= TrailVisibilityPolicy.MAX_RENDER_POINTS)
        assertEquals(points.first().timestamp, visible.first().timestamp)
        assertEquals(points.last().timestamp, visible.last().timestamp)
    }
}
