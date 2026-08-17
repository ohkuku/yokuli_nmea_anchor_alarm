package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.location.PhoneHeadingSample
import com.yokuli.anchorwatch.map.FollowCameraMove
import com.yokuli.anchorwatch.map.MapCameraPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun lockedFollowStillAllowsPanAndZoomButKeepsOrientationStable() {
        val locked=MapCameraPolicy.gestures(mapLockedToBoat=true)
        assertTrue(locked.scrollEnabled)
        assertTrue(locked.zoomEnabled)
        assertFalse(locked.rotationEnabled)
        assertFalse(locked.tiltEnabled)
    }

    @Test fun unlockedBrowsingAllowsEveryGesture() {
        val unlocked=MapCameraPolicy.gestures(mapLockedToBoat=false)
        assertTrue(unlocked.scrollEnabled&&unlocked.zoomEnabled&&unlocked.rotationEnabled&&unlocked.tiltEnabled)
    }

    @Test fun systemGpsEstimateUsesTheLatestLivePhoneHeadingForTheBoatArrow(){
        val session=AnchorSessionEntity(
            startedAt=1,anchorLatitude=-36.8,anchorLongitude=175.1,
            rodeLengthMeters=40.0,waterDepthMeters=8.0,bowRollerHeightMeters=1.5,
            gpsAntennaOffsetMeters=0.0,expectedSwingRadiusMeters=35.0,
            warningRadiusMeters=45.0,alarmRadiusMeters=50.0,
            positionSource=GpsDataSource.SYSTEM.name,anchorPositionMode="ESTIMATE",usePhoneHeading=true,
        )
        val fix=NavigationFix(-36.8,175.1,receivedElapsedRealtime=10,sourceSentence="ANDROID",valid=true,cogTrueDegrees=42.0,sogKnots=1.2)
        assertEquals(123.0,displayHeading(fix,session,emptyList(),PhoneHeadingSample(trueHeadingDegrees=123.0))?:Double.NaN,0.001)
        assertEquals(287.0,displayHeading(fix,session,emptyList(),PhoneHeadingSample(trueHeadingDegrees=287.0))?:Double.NaN,0.001)
    }
}
