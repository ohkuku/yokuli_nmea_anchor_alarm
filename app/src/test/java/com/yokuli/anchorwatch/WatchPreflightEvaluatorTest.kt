package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.preferences.AppSettings
import com.yokuli.anchorwatch.data.sonar.SonarRecorderStatus
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.domain.safety.DeviceSafetySnapshot
import com.yokuli.anchorwatch.domain.safety.SafetyCheckStatus
import com.yokuli.anchorwatch.domain.safety.WatchPreflightEvaluator
import com.yokuli.anchorwatch.domain.safety.WatchSafetyInput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchPreflightEvaluatorTest {
    @Test fun allChecksGreenProducesReadyToWatch(){
        val report=WatchPreflightEvaluator.evaluate(input())
        assertTrue(report.ready);assertTrue(report.canContinue)
    }

    @Test fun missingNotificationAndStaleGpsBlockArming(){
        val report=WatchPreflightEvaluator.evaluate(input(fix=fix(received=80_000),device=device().copy(notificationPermission=false)))
        assertFalse(report.canContinue)
        assertTrue(report.checks.any{it.id=="gps_fresh"&&it.status==SafetyCheckStatus.BLOCKER})
        assertTrue(report.checks.any{it.id=="notifications"&&it.status==SafetyCheckStatus.BLOCKER})
    }

    @Test fun selectedNmeaMustBeConnectedAndReachable(){
        val report=WatchPreflightEvaluator.evaluate(input(settings=readySettings().copy(gpsDataSource=GpsDataSource.NMEA),connection=NmeaConnectionState.RECONNECTING,device=device().copy(networkConnected=false,wifiConnected=false)))
        assertFalse(report.canContinue)
        assertTrue(report.checks.any{it.id=="nmea"&&it.status==SafetyCheckStatus.BLOCKER})
        assertTrue(report.checks.any{it.id=="network"&&it.status==SafetyCheckStatus.BLOCKER})
    }

    @Test fun aConnectedSocketDoesNotMakePoorNmeaPositionReadyToWatch(){
        val report=WatchPreflightEvaluator.evaluate(
            input(
                settings=readySettings().copy(gpsDataSource=GpsDataSource.NMEA),
                fix=fix().copy(hdop=7.0),
                connection=NmeaConnectionState.CONNECTED,
                connectionStarted=90_000,
            ),
        )
        assertFalse(report.canContinue)
        assertTrue(report.checks.any{it.id=="nmea"&&it.status==SafetyCheckStatus.BLOCKER})
    }

    @Test fun batteryOptimizationAndUnconfirmedAlarmAreExplicitWarnings(){
        val report=WatchPreflightEvaluator.evaluate(input(settings=readySettings().copy(alarmAudibleConfirmedAt=null),device=device().copy(batteryOptimizationExempt=false)))
        assertFalse(report.ready);assertTrue(report.canContinue)
        assertTrue(report.checks.count{it.status==SafetyCheckStatus.WARNING}>=2)
    }

    @Test fun deniedFullScreenAlarmAccessIsAnExplicitNonBlockingWarning(){
        val report=WatchPreflightEvaluator.evaluate(input(device=device().copy(fullScreenAlarmAllowed=false)))
        assertFalse(report.ready);assertTrue(report.canContinue)
        assertTrue(report.checks.any{it.id=="full_screen_alarm"&&it.status==SafetyCheckStatus.WARNING})
    }

    private fun input(
        settings:AppSettings=readySettings(),
        fix:NavigationFix=fix(),
        connection:NmeaConnectionState=NmeaConnectionState.DISCONNECTED,
        device:DeviceSafetySnapshot=device(),
        connectionStarted:Long?=null,
    )=WatchSafetyInput(100_000,1_000_000,settings,fix,connection,device,SonarRecorderStatus(),connectionStarted)

    private fun readySettings()=AppSettings(gpsDataSource=GpsDataSource.SYSTEM,alarmAudibleConfirmedAt=999_000)
    private fun device()=DeviceSafetySnapshot(true,true,80,5,10,true,true,true,2L*1024L*1024L*1024L)
    private fun fix(received:Long=99_000)=NavigationFix(-36.84,174.76,receivedElapsedRealtime=received,horizontalAccuracyMeters=4.0,sourceSentence="test",valid=true)
}
