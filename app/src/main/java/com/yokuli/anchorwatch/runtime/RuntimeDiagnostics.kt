package com.yokuli.anchorwatch.runtime

import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.data.sharing.NmeaSharingServer
import com.yokuli.anchorwatch.data.sonar.SonarSurveyRecorder
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.location.AcceptedPositionRepository
import com.yokuli.anchorwatch.data.tide.TideRuntimeDiagnostics
import com.yokuli.anchorwatch.map.SonarTileDiagnostics
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

data class RuntimeUserFeedback(
    val id:Long,
    val title:String,
    val message:String,
    val highPriority:Boolean,
    val receivedElapsedRealtime:Long,
)

data class RuntimeDiagnostics(
    val acceptedFixCount:Long=0,
    val quarantinedFixCount:Long=0,
    val rejectedFixCount:Long=0,
    val nmeaReconnectCount:Long=0,
    val estimatorRuns:Long=0,
    val estimatorLastDurationMs:Long=0,
    val estimatorMaxDurationMs:Long=0,
    val positionIntegrityLastDurationMicros:Long=0,
    val positionIntegrityMaxDurationMicros:Long=0,
    val sonarSamplesWritten:Long=0,
    val sonarGridUpdates:Long=0,
    val sonarGridLastDurationMs:Long=0,
    val sonarGridMaxDurationMs:Long=0,
    val sonarTileLastDurationMs:Long=0,
    val sonarTileMaxDurationMs:Long=0,
    val tideCorrections:Long=0,
    val tideLastDurationMs:Long=0,
    val tideMaxDurationMs:Long=0,
    val sharingClients:Int=0,
    val sharingSlowClientsDropped:Long=0,
    val wakeLockHeld:Boolean=false,
    val wifiLockHeld:Boolean=false,
    val phoneMotionActive:Boolean=false,
    val phoneHeadingActive:Boolean=false,
    val phonePressureActive:Boolean=false,
    val activeOwners:Set<RuntimeOwner> = emptySet(),
    val serviceGeneration:Long=0,
    val serviceReady:Boolean=false,
    val restoredSessionId:Long?=null,
    val restoreStage:String="IDLE",
    val restoreError:String?=null,
    val lastUserFeedback:RuntimeUserFeedback?=null,
)

/**
 * Process-lifetime counters for the Diagnostics screen only. Coordinates,
 * complete NMEA sentences and API credentials are deliberately excluded.
 */
@Singleton
class RuntimeDiagnosticsRepository @Inject constructor(
    acceptedPosition:AcceptedPositionRepository,
    navigation:NavigationRepository,
    recorder:SonarSurveyRecorder,
    sharing:NmeaSharingServer,
    resources:RuntimeResourceManager,
){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val _state=MutableStateFlow(RuntimeDiagnostics())
    val state=_state.asStateFlow()
    private val feedbackIds=AtomicLong(0L)

    init{
        scope.launch(start=CoroutineStart.UNDISPATCHED){acceptedPosition.accepted.collect{_state.update{it.copy(acceptedFixCount=it.acceptedFixCount+1)}}}
        scope.launch{acceptedPosition.state.collect{position->_state.update{it.copy(positionIntegrityLastDurationMicros=position.integrityLastDurationMicros,positionIntegrityMaxDurationMicros=position.integrityMaxDurationMicros)}}}
        scope.launch{
            var previous=NmeaConnectionState.DISCONNECTED
            navigation.connectionState.collect{current->
                if(current==NmeaConnectionState.RECONNECTING&&previous!=current)_state.update{it.copy(nmeaReconnectCount=it.nmeaReconnectCount+1)}
                previous=current
            }
        }
        scope.launch{
            var surveyId:Long?=null
            var sampleCount=0L
            var lastGridMarker:Pair<String?,Long?>?=null
            recorder.status.collect{status->
                val active=status.activeSurvey
                if(active?.id!=surveyId){surveyId=active?.id;sampleCount=active?.sampleCount?.toLong()?:0L}
                else if(active!=null&&active.sampleCount.toLong()>sampleCount){val delta=active.sampleCount.toLong()-sampleCount;sampleCount=active.sampleCount.toLong();_state.update{it.copy(sonarSamplesWritten=it.sonarSamplesWritten+delta)}}
                val marker=status.gridDiagnostics.lastUpdatedCell to status.gridDiagnostics.lastUpdateDurationMillis
                if(marker.first!=null&&marker!=lastGridMarker){lastGridMarker=marker;val duration=status.gridDiagnostics.lastUpdateDurationMillis?:0L;_state.update{it.copy(sonarGridUpdates=it.sonarGridUpdates+1,sonarGridLastDurationMs=duration,sonarGridMaxDurationMs=maxOf(it.sonarGridMaxDurationMs,duration))}}
            }
        }
        scope.launch{SonarTileDiagnostics.state.collect{tile->_state.update{it.copy(sonarTileLastDurationMs=tile.lastRenderDurationMillis,sonarTileMaxDurationMs=tile.maxRenderDurationMillis)}}}
        scope.launch{TideRuntimeDiagnostics.state.collect{tide->_state.update{it.copy(tideCorrections=tide.corrections,tideLastDurationMs=tide.lastDurationMillis,tideMaxDurationMs=tide.maxDurationMillis)}}}
        scope.launch{sharing.status.collect{status->_state.update{it.copy(sharingClients=status.clientCount,sharingSlowClientsDropped=status.droppedSlowClients)}}}
        scope.launch{resources.state.collect{snapshot->_state.update{it.copy(wakeLockHeld=snapshot.wakeLockHeld,wifiLockHeld=snapshot.wifiLockHeld,phoneMotionActive=snapshot.phoneMotionActive,phoneHeadingActive=snapshot.phoneHeadingActive,phonePressureActive=snapshot.phonePressureActive,activeOwners=snapshot.owners)}}}
    }

    fun recordPositionDisposition(disposition:String){_state.update{state->when(disposition){
        "QUARANTINED"->state.copy(quarantinedFixCount=state.quarantinedFixCount+1)
        "REJECTED"->state.copy(rejectedFixCount=state.rejectedFixCount+1)
        else->state
    }}}

    fun recordEstimatorRun(durationMillis:Long){_state.update{it.copy(estimatorRuns=it.estimatorRuns+1,estimatorLastDurationMs=durationMillis,estimatorMaxDurationMs=maxOf(it.estimatorMaxDurationMs,durationMillis))}}

    /** Distinguishes a newly-created Android service from stale process state. */
    fun serviceStarting(){_state.update{it.copy(serviceGeneration=it.serviceGeneration+1,serviceReady=false,restoredSessionId=null,restoreStage="STARTING",restoreError=null)}}
    fun restoring(stage:String){_state.update{it.copy(restoreStage=stage,restoreError=null)}}
    fun restoreFailed(stage:String,error:Throwable){_state.update{it.copy(serviceReady=false,restoreStage=stage,restoreError="${error.javaClass.simpleName}: ${error.message.orEmpty()}".trim())}}
    fun serviceReady(restoredSessionId:Long?){_state.update{it.copy(serviceReady=true,restoredSessionId=restoredSessionId,restoreStage="READY",restoreError=null)}}
    fun serviceStopped(){_state.update{it.copy(serviceReady=false,restoredSessionId=null,restoreStage="STOPPED")}}

    /** Mirrors a Service command result into the foreground UI. A safety
     * action must never be observable only through the notification shade. */
    fun recordUserFeedback(title:String,message:String,highPriority:Boolean){
        val feedback=RuntimeUserFeedback(
            id=feedbackIds.incrementAndGet(),
            title=title,
            message=message,
            highPriority=highPriority,
            receivedElapsedRealtime=android.os.SystemClock.elapsedRealtime(),
        )
        _state.update{it.copy(lastUserFeedback=feedback)}
    }
}
