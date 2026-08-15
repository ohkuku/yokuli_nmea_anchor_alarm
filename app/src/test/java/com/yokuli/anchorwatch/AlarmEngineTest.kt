package com.yokuli.anchorwatch
import com.yokuli.anchorwatch.domain.anchor.*
import com.yokuli.anchorwatch.domain.model.*
import org.junit.Assert.*
import org.junit.Test
class AlarmEngineTest{private val c=AnchorConfig(0.0,0.0,40.0,warningRadiusMeters=40.0,alarmRadiusMeters=50.0);private fun f(north:Double,t:Long)=NavigationFix(north/110540,0.0,receivedElapsedRealtime=t,sourceSentence="test",valid=true)
 @Test fun warningSpikeAndDrag(){val e=AlarmEngine(persistenceMillis=8000,requiredFixes=3);e.arm(c);assertEquals(AlarmState.ARMED,e.onFix(f(20.0,0)).state);assertEquals(AlarmState.WARNING,e.onFix(f(45.0,1000)).state);assertNotEquals(AlarmState.ALARM,e.onFix(f(60.0,2000)).state);assertEquals(AlarmState.ARMED,e.onFix(f(20.0,3000)).state);e.onFix(f(60.0,4000));e.onFix(f(60.0,5000));assertEquals(AlarmState.ALARM,e.onFix(f(60.0,6000)).state)}
 @Test fun gpsLoss(){val e=AlarmEngine(gpsLossMillis=15000);e.arm(c);e.onFix(f(10.0,1000));assertEquals(AlarmType.GPS_DATA_LOST,e.tick(16000).type)}
 @Test fun learningProtectsTemporaryBoundaryAndTracksGpsLoss(){val e=AlarmEngine(gpsLossMillis=15000,requiredFixes=3);assertEquals(AlarmState.LEARNING,e.learn(c,0).state);assertEquals(AlarmState.LEARNING,e.onFix(f(10.0,1000)).state);assertNotEquals(AlarmState.ALARM,e.onFix(f(60.0,2000)).state);assertNotEquals(AlarmState.ALARM,e.onFix(f(60.0,3000)).state);assertEquals(AlarmType.ANCHOR_RADIUS_EXCEEDED,e.onFix(f(60.0,4000)).type);assertEquals(AlarmType.GPS_DATA_LOST,e.tick(19000).type)}
}
