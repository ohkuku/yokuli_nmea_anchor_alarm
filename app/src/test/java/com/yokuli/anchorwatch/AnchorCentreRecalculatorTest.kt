package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.TrackPointEntity
import com.yokuli.anchorwatch.domain.anchor.AnchorCentreRecalculationStatus
import com.yokuli.anchorwatch.domain.anchor.AnchorCentreRecalculator
import com.yokuli.anchorwatch.domain.anchor.AnchorCentreApplyPolicy
import com.yokuli.anchorwatch.domain.model.AlarmState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorCentreRecalculatorTest {
    private val session=AnchorSessionEntity(startedAt=0,anchorLatitude=12.0/110_540.0,anchorLongitude=0.0,rodeLengthMeters=45.0,waterDepthMeters=18.0,bowRollerHeightMeters=1.0,gpsAntennaOffsetMeters=0.0,expectedSwingRadiusMeters=40.0,warningRadiusMeters=45.0,alarmRadiusMeters=55.0,active=false)
    private fun point(second:Int,radius:Double,angle:Double)=TrackPointEntity(sessionId=1,timestamp=second*1_000L,latitude=radius*kotlin.math.cos(angle)/110_540.0,longitude=radius*kotlin.math.sin(angle)/111_320.0,distanceFromAnchor=radius,sog=.1,cog=null,heading=null,hdop=.8,horizontalAccuracyMeters=2.5,positionProvider="NMEA",fixTrust="TRUSTED")

    @Test fun knownCentreLocalCircleIsComparisonFailureNotAReplacement(){
        val points=(0..1_000).map{second->point(second,5.0,2.0*Math.PI*second/120.0)}
        val result=AnchorCentreRecalculator.analyze(session,points)
        assertEquals(AnchorCentreRecalculationStatus.RADIAL_NOT_OBSERVABLE,result.status)
        assertTrue(result.candidate?.radialObservable==false)
    }

    @Test fun completeBroadTrackCanProduceAlternativeWithoutMutatingSession(){
        val points=(0..900).map{second->val angle=Math.toRadians(110.0+110.0*kotlin.math.sin(2.0*Math.PI*second/180.0));point(second,25.0,angle)}
        val result=AnchorCentreRecalculator.analyze(session,points)
        assertEquals(result.candidate?.debugSummary(),AnchorCentreRecalculationStatus.READY,result.status)
        assertNotNull(result.shiftMeters)
        assertEquals(12.0,result.shiftMeters?:0.0,2.0)
        assertEquals(12.0/110_540.0,session.anchorLatitude,0.0)
    }

    @Test fun warningAlarmAndAcknowledgedStatesBlockApplyingTheAlternative(){
        assertTrue(!AnchorCentreApplyPolicy.mayApply(AlarmState.WARNING))
        assertTrue(!AnchorCentreApplyPolicy.mayApply(AlarmState.ALARM))
        assertTrue(!AnchorCentreApplyPolicy.mayApply(AlarmState.ACKNOWLEDGED))
        assertTrue(AnchorCentreApplyPolicy.mayApply(AlarmState.ARMED))
        assertTrue(AnchorCentreApplyPolicy.mayApply(AlarmState.STOPPED))
    }
}
