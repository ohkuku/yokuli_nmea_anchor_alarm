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

    suspend fun start(name:String,tideMode:TideMode,manualTideOffsetMeters:Double,tideStationId:String?=null,demoWatchRunning:Boolean=false):SonarRuntimeResult{
        val appSettings=settings.settings.first()
        val demo=appSettings.demoMode
        // Position pairing stays strict. Depth may be a bounded held value from
        // the same connection because many gateways emit DBT/DPT only on change.
        if(!demo){
            withTimeoutOrNull(5_000){
                recorder.status.first{status->
                    val now=clock.elapsedRealtime()
                    navigation.connectionState.value==NmeaConnectionState.CONNECTED&&
                        status.realDepthHoldState()!=com.yokuli.anchorwatch.domain.sonar.SonarDepthHoldState.NO_DEPTH&&status.hasFreshNmeaPosition(now)
                }
            }
        }
        val now=clock.elapsedRealtime()
        val current=recorder.status.value
        when(SonarSurveyStartPolicy.evaluate(demo,demoWatchRunning,navigation.connectionState.value,current.realDepthHoldState(),current.hasFreshNmeaPosition(now))){
            SonarSurveyStartDecision.DEMO_WATCH_REQUIRED->return rejected("Start and resume a Demo anchor watch before starting its sonar survey.")
            SonarSurveyStartDecision.NMEA_NOT_CONNECTED->return rejected("Connect the NMEA server before starting a sonar survey.")
            SonarSurveyStartDecision.DEPTH_NOT_SEEN->return rejected("Waiting for the first valid DPT/DBT depth from this NMEA connection.")
            SonarSurveyStartDecision.DEPTH_HOLD_EXPIRED->return rejected("The last real DPT/DBT depth is no longer valid. Wait for a new depth sentence.")
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

    suspend fun watchdog():SonarRuntimeResult?{
        val reason=recorder.evaluateDepthHold(clock.elapsedRealtime())?:return null
        val message=when(reason){
            com.yokuli.anchorwatch.domain.sonar.SonarAutoStopReason.DEPTH_HOLD_EXPIRED_TIME->"Sonar survey stopped because no new real DPT/DBT depth was received for 5 minutes."
            com.yokuli.anchorwatch.domain.sonar.SonarAutoStopReason.DEPTH_HOLD_EXPIRED_DISTANCE->"Sonar survey stopped because the vessel travelled more than 500 m without a new real DPT/DBT depth."
        }
        recorder.stop(message);resources.release(RuntimeOwner.SONAR_MAPPING);releaseNmeaIfUnowned()
        return SonarRuntimeResult(false,"Sonar survey stopped",message)
    }

    fun shutdown(){resources.release(RuntimeOwner.SONAR_MAPPING)}

    private fun rejected(message:String)=SonarRuntimeResult(false,"Sonar survey not started",message)
    private fun releaseNmeaIfUnowned()=nmeaRuntime.releaseIfUnowned()
}
