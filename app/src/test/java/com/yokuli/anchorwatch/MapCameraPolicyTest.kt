package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.map.FollowCameraMove
import com.yokuli.anchorwatch.map.MapCameraPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class MapCameraPolicyTest {
    @Test fun firstValidFixRestoresTheNavigationZoom() {
        assertEquals(
            FollowCameraMove.CENTER_WITH_DEFAULT_ZOOM,
            MapCameraPolicy.nextMove(false, null, GpsDataSource.SYSTEM),
        )
    }

    @Test fun laterFixesKeepTheUsersCurrentZoom() {
        assertEquals(
            FollowCameraMove.CENTER_PRESERVING_ZOOM,
            MapCameraPolicy.nextMove(true, GpsDataSource.SYSTEM, GpsDataSource.SYSTEM),
        )
    }

    @Test fun changingGpsSourceRecentresAtTheNavigationZoom() {
        assertEquals(
            FollowCameraMove.CENTER_WITH_DEFAULT_ZOOM,
            MapCameraPolicy.nextMove(true, GpsDataSource.SYSTEM, GpsDataSource.NMEA),
        )
    }
}
