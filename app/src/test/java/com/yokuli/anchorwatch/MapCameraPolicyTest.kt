package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.location.PhoneHeadingSample
import com.yokuli.anchorwatch.domain.model.HeadingSource
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
        assertEquals(123.0,displayHeading(fix,session,emptyList(),PhoneHeadingSample(liveTrueHeadingDegrees=123.0,receivedElapsedRealtime=100),nowElapsed=100)?:Double.NaN,0.001)
        assertEquals(287.0,displayHeading(fix,session,emptyList(),PhoneHeadingSample(liveTrueHeadingDegrees=287.0,receivedElapsedRealtime=120),nowElapsed=120)?:Double.NaN,0.001)
    }

    @Test fun livePhoneHeadingTurnsSystemGpsBoatEvenWhenNotUsedAsEstimatorEvidence(){
        val fix=NavigationFix(-36.8,175.1,receivedElapsedRealtime=100,sourceSentence="ANDROID",valid=true,cogTrueDegrees=42.0,sogKnots=1.2)
        assertEquals(211.0,displayHeading(fix,null,emptyList(),PhoneHeadingSample(liveTrueHeadingDegrees=211.0,receivedElapsedRealtime=500),nowElapsed=500)!!,0.001)
    }

    @Test fun freshPhysicalNmeaHeadingRemainsAuthoritativeOverPhone(){
        val fix=NavigationFix(-36.8,175.1,receivedElapsedRealtime=1000,sourceSentence="HDT",valid=true,headingTrueDegrees=78.0,headingReceivedElapsedRealtime=1000,headingSource=HeadingSource.NMEA_PHYSICAL)
        assertEquals(78.0,displayHeading(fix,null,emptyList(),PhoneHeadingSample(liveTrueHeadingDegrees=211.0,receivedElapsedRealtime=1200),nowElapsed=1200)!!,0.001)
    }

    @Test fun connectedNmeaHeadingWinsEvenWhenSystemGpsSuppliesPosition(){
        val system=NavigationFix(-36.8,175.1,receivedElapsedRealtime=1000,sourceSentence="ANDROID",valid=true,cogTrueDegrees=42.0)
        val nmea=NavigationFix(-36.8,175.1,receivedElapsedRealtime=1180,sourceSentence="HDT",valid=true,headingTrueDegrees=96.0,headingReceivedElapsedRealtime=1180,headingSource=HeadingSource.NMEA_PHYSICAL)
        assertEquals(96.0,displayHeading(system,null,emptyList(),PhoneHeadingSample(liveTrueHeadingDegrees=211.0,receivedElapsedRealtime=1200),nmeaHeadingFix=nmea,nowElapsed=1200)!!,0.001)
    }

    @Test fun stalePhoneHeadingNeverOverridesCurrentCourse(){
        val fix=NavigationFix(-36.8,175.1,receivedElapsedRealtime=4_000,sourceSentence="ANDROID",valid=true,cogTrueDegrees=42.0,sogKnots=1.2)
        assertEquals(42.0,displayHeading(fix,null,emptyList(),PhoneHeadingSample(liveTrueHeadingDegrees=211.0,receivedElapsedRealtime=1_000),nowElapsed=4_000)!!,0.001)
    }
}
