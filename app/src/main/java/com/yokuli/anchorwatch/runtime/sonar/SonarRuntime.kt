package com.yokuli.anchorwatch.runtime.sonar

import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.data.sonar.SonarSurveyRecorder
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.domain.sonar.SonarSurveyStartDecision
import com.yokuli.anchorwatch.domain.sonar.SonarSurveyStartPolicy
import com.yokuli.anchorwatch.domain.sonar.TideMode
import com.yokuli.anchorwatch.runtime.RuntimeOwner
import com.yokuli.anchorwatch.runtime.RuntimeRequirement
import com.yokuli.anchorwatch.runtime.RuntimeResourceManager
import com.yokuli.anchorwatch.runtime.MonotonicClock
import com.yokuli.anchorwatch.runtime.nmea.NmeaRuntime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

data class SonarRuntimeResult(val started:Boolean,val title:String?=null,val message:String?=null)

/** Integrates sonar survey ownership with the shared NMEA transport and runtime resources. */
@Singleton
class SonarRuntime @Inject constructor(
    private val recorder:SonarSurveyRecorder,
    private val settings:SettingsRepository,
    private val navigation:NavigationRepository,
    private val resources:RuntimeResourceManager,
    private val nmeaRuntime:NmeaRuntime,
    private val clock:MonotonicClock,
){
    val status get()=recorder.status

    /** Restores ownership before normal collectors can decide the service is idle. */
    suspend fun restore():Boolean{
        val active=recorder.restoreActiveSurvey()?:return false
        val appSettings=settings.settings.first()
        val demo=appSettings.demoMode
        resources.set(
            RuntimeOwner.SONAR_MAPPING,
            RuntimeRequirement(
                needsNmeaTransport=!demo,
                needsWakeLock=true,
                needsWifiLock=!demo&&appSettings.keepWifiAwake,
            ),
        )
        if(!demo)nmeaRuntime.ensureConnected(appSettings.profile)
        return active.active
    }

    suspend fun start(name:String,tideMode:TideMode,manualTideOffsetMeters:Double,tideStationId:String?=null):SonarRuntimeResult{
        val appSettings=settings.settings.first()
        val demo=appSettings.demoMode
        // Freshness remains a strict 2 s pairing rule. Waiting here only lets the
        // serialized command observe the next complete same-stream pair.
        if(!demo){
            withTimeoutOrNull(5_000){
                recorder.status.first{status->
                    val now=clock.elapsedRealtime()
                    navigation.connectionState.value==NmeaConnectionState.CONNECTED&&
                        status.hasFreshRealDepth(now)&&status.hasFreshNmeaPosition(now)
                }
            }
        }
        val now=clock.elapsedRealtime()
        val current=recorder.status.value
        when(SonarSurveyStartPolicy.evaluate(demo,navigation.connectionState.value,current.hasFreshRealDepth(now),current.hasFreshNmeaPosition(now))){
            SonarSurveyStartDecision.NMEA_NOT_CONNECTED->return rejected("Connect the NMEA server before starting a sonar survey.")
            SonarSurveyStartDecision.DEPTH_NOT_FRESH->return rejected("The NMEA server is connected but has not supplied fresh DPT/DBT depth data.")
            SonarSurveyStartDecision.NMEA_POSITION_NOT_FRESH->return rejected("The NMEA server is sending depth but has not supplied a fresh valid GPS position from the same stream.")
            SonarSurveyStartDecision.ALLOWED->Unit
        }
        resources.set(
            RuntimeOwner.SONAR_MAPPING,
            RuntimeRequirement(needsNmeaTransport=!demo,needsWakeLock=true,needsWifiLock=!demo&&appSettings.keepWifiAwake),
        )
        if(!demo)nmeaRuntime.ensureConnected(appSettings.profile)
        recorder.start(name,tideMode,manualTideOffsetMeters,appSettings.sounderOffsetMeters,tideStationId)
        return SonarRuntimeResult(started=true)
    }

    suspend fun stop(){
        recorder.stop()
        resources.release(RuntimeOwner.SONAR_MAPPING)
        releaseNmeaIfUnowned()
    }

    fun shutdown(){resources.release(RuntimeOwner.SONAR_MAPPING)}

    private fun rejected(message:String)=SonarRuntimeResult(false,"Sonar survey not started",message)
    private fun releaseNmeaIfUnowned()=nmeaRuntime.releaseIfUnowned()
}
