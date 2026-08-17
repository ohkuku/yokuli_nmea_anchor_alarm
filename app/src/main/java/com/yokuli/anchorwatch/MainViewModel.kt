package com.yokuli.anchorwatch

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.data.AlarmUiRepository
import com.yokuli.anchorwatch.data.backup.BackupOperationState
import com.yokuli.anchorwatch.data.backup.YokuliBackupManager
import com.yokuli.anchorwatch.data.database.AnchorDao
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.TrackPointEntity
import com.yokuli.anchorwatch.data.database.AlarmEventEntity
import com.yokuli.anchorwatch.data.database.DepthSampleEntity
import com.yokuli.anchorwatch.data.database.SonarDao
import com.yokuli.anchorwatch.data.database.SonarSurveyEntity
import com.yokuli.anchorwatch.data.database.IncidentLogEntity
import com.yokuli.anchorwatch.data.diagnostics.IncidentLogger
import com.yokuli.anchorwatch.data.diagnostics.StorageHealth
import com.yokuli.anchorwatch.data.diagnostics.StorageHealthRepository
import com.yokuli.anchorwatch.data.diagnostics.SupportBundleManager
import com.yokuli.anchorwatch.data.diagnostics.SupportBundleState
import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.nmea.NmeaDiagnostics
import com.yokuli.anchorwatch.data.nmea.NmeaEndpointPreflight
import com.yokuli.anchorwatch.data.preferences.AppSettings
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.data.sharing.NmeaSharingServer
import com.yokuli.anchorwatch.data.sharing.NmeaSharingStatus
import com.yokuli.anchorwatch.data.sonar.SonarRecorderStatus
import com.yokuli.anchorwatch.data.sonar.SonarSurveyRecorder
import com.yokuli.anchorwatch.data.sonar.SonarIncrementalGridUpdater
import com.yokuli.anchorwatch.data.linz.LinzDepthReferenceRepository
import com.yokuli.anchorwatch.data.linz.LinzDepthReference
import com.yokuli.anchorwatch.data.linz.LinzDepthDiagnostics
import com.yokuli.anchorwatch.domain.sonar.TideMode
import com.yokuli.anchorwatch.domain.sonar.SonarGrid
import com.yokuli.anchorwatch.domain.sonar.DepthUiState
import com.yokuli.anchorwatch.data.sonar.SonarGridScope
import com.yokuli.anchorwatch.domain.model.AnchorCenterSource
import com.yokuli.anchorwatch.domain.model.AnchorPlacementMode
import com.yokuli.anchorwatch.domain.model.AnchorRangeMode
import com.yokuli.anchorwatch.domain.model.AnchorSafetyPreset
import com.yokuli.anchorwatch.domain.model.AlarmSnapshot
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.domain.anchor.AnchorDepthSource
import com.yokuli.anchorwatch.location.GlobalMockLocationManager
import com.yokuli.anchorwatch.location.DemoGpsStatus
import com.yokuli.anchorwatch.location.DemoLocationRepository
import com.yokuli.anchorwatch.location.GpsSourceSafety
import com.yokuli.anchorwatch.location.MockGpsState
import com.yokuli.anchorwatch.location.MockGpsStatus
import com.yokuli.anchorwatch.location.NmeaSourceAvailability
import com.yokuli.anchorwatch.location.NmeaSourceSelectionPolicy
import com.yokuli.anchorwatch.location.SystemLocationRepository
import com.yokuli.anchorwatch.location.AcceptedPositionRepository
import com.yokuli.anchorwatch.location.AcceptedPositionState
import com.yokuli.anchorwatch.location.PhoneHeadingRepository
import com.yokuli.anchorwatch.location.PhoneHeadingSample
import com.yokuli.anchorwatch.service.AnchorForegroundService
import com.yokuli.anchorwatch.runtime.RuntimeDiagnostics
import com.yokuli.anchorwatch.runtime.RuntimeDiagnosticsRepository
import com.yokuli.anchorwatch.domain.safety.DeviceSafetyProbe
import com.yokuli.anchorwatch.domain.safety.WatchPreflightEvaluator
import com.yokuli.anchorwatch.domain.safety.WatchSafetyInput
import com.yokuli.anchorwatch.domain.safety.WatchSafetyReport
import com.yokuli.anchorwatch.map.OfflineMapInfo
import com.yokuli.anchorwatch.map.OfflineMapRepository
import com.yokuli.anchorwatch.domain.condition.ConditionGuardConfig
import com.yokuli.anchorwatch.domain.condition.ConditionRuntimeSnapshot
import com.yokuli.anchorwatch.data.condition.LiveDepthRepository
import com.yokuli.anchorwatch.data.condition.LiveDepthState
import com.yokuli.anchorwatch.data.condition.LiveWindRepository
import com.yokuli.anchorwatch.data.condition.LiveWindState
import com.yokuli.anchorwatch.runtime.condition.ConditionRuntime
import com.yokuli.anchorwatch.data.anchorage.AnchorageRepository
import com.yokuli.anchorwatch.data.anchorage.AnchorageApproachRepository
import com.yokuli.anchorwatch.data.anchorage.AnchorageQrImageGenerator
import com.yokuli.anchorwatch.data.anchorage.AnchorageShareContent
import com.yokuli.anchorwatch.data.anchorage.DuplicateAnchorageException
import com.yokuli.anchorwatch.data.database.SavedAnchorageEntity
import com.yokuli.anchorwatch.localization.usesChinese
import com.yokuli.anchorwatch.domain.anchorage.AnchorageApproachEngine
import com.yokuli.anchorwatch.domain.anchorage.AnchorageApproachState
import com.yokuli.anchorwatch.domain.anchorage.AnchorageCluster
import com.yokuli.anchorwatch.domain.anchorage.AnchorageClusterDistance
import com.yokuli.anchorwatch.domain.anchorage.AnchorageNearbyEpisodeTracker
import com.yokuli.anchorwatch.domain.anchorage.AnchorageNearbyPolicy
import com.yokuli.anchorwatch.domain.anchorage.ApproachDirectionPolicy
import com.yokuli.anchorwatch.domain.anchorage.ApproachHeadingMode
import com.yokuli.anchorwatch.domain.navigation.NmeaCourseTrustGate
import com.yokuli.anchorwatch.domain.navigation.TrustedNmeaCourse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class ConnectionAttemptState { IDLE, TESTING, FAILED }
data class ConnectionAttempt(val state:ConnectionAttemptState=ConnectionAttemptState.IDLE,val message:String="")
const val CORRECTED_SONAR_HISTORY_ID = -1L

data class MainUiState(
    val fix:NavigationFix?=null,
    val nmeaFix:NavigationFix?=null,
    val nmeaConnectionStartedElapsed:Long?=null,
    val systemFix:NavigationFix?=null,
    val connection:NmeaConnectionState=NmeaConnectionState.DISCONNECTED,
    val connectionAttempt:ConnectionAttempt=ConnectionAttempt(),
    val diagnostics:NmeaDiagnostics=NmeaDiagnostics(),
    val settings:AppSettings=AppSettings(),
    val settingsReady:Boolean=false,
    val sessions:List<AnchorSessionEntity> = emptyList(),
    val active:AnchorSessionEntity?=null,
    val points:List<TrackPointEntity> = emptyList(),
    val follow:Boolean=true,
    val page:Int=0,
    val mockGps:MockGpsStatus=MockGpsStatus(),
    val proxyFeedback:String?=null,
    val demoGps:DemoGpsStatus=DemoGpsStatus(),
    val alarmSnapshot:AlarmSnapshot=AlarmSnapshot(),
    val rangeEditorRequested:Boolean=false,
    val eventsBySession:Map<Long,List<AlarmEventEntity>> = emptyMap(),
    val positionHealth:com.yokuli.anchorwatch.domain.model.PositionHealth=com.yokuli.anchorwatch.domain.model.PositionHealth.GPS_LOST,
    val nmeaSharing:NmeaSharingStatus=NmeaSharingStatus(),
    val acceptedPosition:AcceptedPositionState=AcceptedPositionState(),
    val sonarSurveys:List<SonarSurveyEntity> = emptyList(),
    val selectedSonarSurveyId:Long? = null,
    val activeSonarSurvey:SonarSurveyEntity? = null,
    val sonarSamples:List<DepthSampleEntity> = emptyList(),
    val sonarGrid:SonarGrid = SonarGrid.build(emptyList()),
    val sonarGridVersion:Long=0L,
    val sonarGridChangedCells:Set<Pair<Long,Long>> = emptySet(),
    val sonarRecorder:SonarRecorderStatus = SonarRecorderStatus(),
    val linzDepth:LinzDepthReference=LinzDepthReference(),
    val linzDepthDiagnostics:LinzDepthDiagnostics=LinzDepthDiagnostics(),
    val depthUi:DepthUiState=DepthUiState(),
    val backup:BackupOperationState=BackupOperationState(),
    val runtimeDiagnostics:RuntimeDiagnostics=RuntimeDiagnostics(),
    val watchSafety:WatchSafetyReport=WatchSafetyReport(),
    val storageHealth:StorageHealth=StorageHealth(),
    val incidents:List<IncidentLogEntity> = emptyList(),
    val supportBundle:SupportBundleState=SupportBundleState(),
    val offlineMap:OfflineMapInfo=OfflineMapInfo(),
    /** Live orientation is intentionally separate from GPS publication cadence. */
    val phoneHeading:PhoneHeadingSample=PhoneHeadingSample(),
    /** NMEA COG accepted only after the anti-wander speed/time gate. */
    val trustedNmeaCourse:TrustedNmeaCourse?=null,
    val approachHeadingMode:ApproachHeadingMode=ApproachHeadingMode.PHONE,
    val vesselApproachHeadingAvailable:Boolean=false,
    val liveDepth:LiveDepthState=LiveDepthState(),
    val liveWind:LiveWindState=LiveWindState(),
    val conditions:ConditionRuntimeSnapshot=ConditionRuntimeSnapshot(),
    val savedAnchorages:List<SavedAnchorageEntity> = emptyList(),
    val anchorageClusters:List<AnchorageCluster> = emptyList(),
    val anchorageApproach:AnchorageApproachState = AnchorageApproachState(),
    val nearbyAnchoragePrompt:List<AnchorageClusterDistance> = emptyList(),
    val approachDisclaimerTargetId:String?=null,
    val anchorageDuplicateExisting:SavedAnchorageEntity?=null,
    val anchorageOperationError:String?=null,
)

private data class PositionSources(val selected:NavigationFix?,val nmea:NavigationFix?,val system:NavigationFix?,val settings:AppSettings)
private data class AvailablePositions(val nmea:NavigationFix?,val system:NavigationFix?,val demo:NavigationFix?,val demoStatus:DemoGpsStatus)
data class AnchorWatchInput(val placement:AnchorPlacementMode,val rangeMode:AnchorRangeMode,val safetyPreset:AnchorSafetyPreset,val depthMeters:Double?,val rodeMeters:Double,val bowHeightMeters:Double,val boatLengthMeters:Double?,val alarmRadiusMeters:Double,val positionSource:GpsDataSource=GpsDataSource.SYSTEM,val centerSource:AnchorCenterSource=AnchorCenterSource.CURRENT_POSITION,val usePhoneHeading:Boolean=false,val depthSource:AnchorDepthSource=AnchorDepthSource.MANUAL,val conditions:ConditionGuardConfig=ConditionGuardConfig())

@HiltViewModel
class MainViewModel @Inject constructor(
    private val app:Application,
    private val nav:NavigationRepository,
    private val dao:AnchorDao,
    private val prefs:SettingsRepository,
    private val mockManager:GlobalMockLocationManager,
    private val systemLocation:SystemLocationRepository,
    private val demoLocation:DemoLocationRepository,
    private val endpointPreflight:NmeaEndpointPreflight,
    private val alarmUi:AlarmUiRepository,
    private val sharingServer:NmeaSharingServer,
    private val acceptedPosition:AcceptedPositionRepository,
    private val phoneHeadingRepository:PhoneHeadingRepository,
    private val sonarDao:SonarDao,
    private val sonarRecorder:SonarSurveyRecorder,
    private val sonarGridUpdater:SonarIncrementalGridUpdater,
    private val linzDepthRepository:LinzDepthReferenceRepository,
    private val backupManager:YokuliBackupManager,
    private val runtimeDiagnostics:RuntimeDiagnosticsRepository,
    private val safetyProbe:DeviceSafetyProbe,
    private val storageHealthRepository:StorageHealthRepository,
    private val supportBundleManager:SupportBundleManager,
    private val incidentLogger:IncidentLogger,
    private val offlineMapRepository:OfflineMapRepository,
    private val liveDepthRepository:LiveDepthRepository,
    private val liveWindRepository:LiveWindRepository,
    private val conditionRuntime:ConditionRuntime,
    private val anchorageRepository:AnchorageRepository,
    private val anchorageApproachRepository:AnchorageApproachRepository,
    private val anchorageQrImageGenerator:AnchorageQrImageGenerator,
):AndroidViewModel(app){
    private val _ui=MutableStateFlow(MainUiState());val ui=_ui.asStateFlow()
    private var pointsJob:Job?=null
    private var observedSessionId:Long?=null
    private var sonarSamplesJob:Job?=null
    private var observedSonarSurveyId:Long?=null
    private val anchorageNearbyTracker=AnchorageNearbyEpisodeTracker()
    private val nmeaCourseTrustGate=NmeaCourseTrustGate()
    private var selectedApproachClusterId:String?=null
    private var lastApproachHeadingRefreshElapsed=0L

    init{
        // Safety and recording repositories consume the full provider rate. Compose/map only
        // needs a bounded visual cadence; drawing every 10‑20 Hz NMEA sentence can starve taps,
        // service commands and test idling on slower phones without adding navigational value.
        val nmeaForUi=nav.fix.map{fix->delay(UI_POSITION_FRAME_MILLIS);fix}
        val diagnosticsForUi=nav.diagnostics.map{diagnostics->delay(UI_POSITION_FRAME_MILLIS);diagnostics}
        val available=combine(nmeaForUi,systemLocation.fix,demoLocation.fix,demoLocation.status){nmea,system,demo,status->AvailablePositions(nmea,system,demo,status)}
        val sources=combine(available,prefs.settings){positions,settings->
            val selected=when(settings.gpsDataSource){GpsDataSource.NMEA->positions.nmea;GpsDataSource.SYSTEM->positions.system;GpsDataSource.DEMO->if(positions.demoStatus.running)positions.demo else positions.system}
            PositionSources(selected,positions.nmea,positions.system,settings) to positions.demoStatus
        }
        viewModelScope.launch{
            combine(available,prefs.settings,dao.sessions()){positions,settings,sessions->Triple(positions,settings,sessions.firstOrNull{it.active})}.collect{(positions,settings,active)->
                val lockedSource=active?.positionSource?.let{runCatching{GpsDataSource.valueOf(it)}.getOrNull()}
                val source=lockedSource?:settings.gpsDataSource
                if(active!=null)acceptedPosition.lockSource(active.id,source)else{acceptedPosition.unlockSource(null);acceptedPosition.selectSource(source)}
                acceptedPosition.setPhoneHeadingEvidenceEnabled(active?.usePhoneHeading==true)
                val raw=when(source){GpsDataSource.NMEA->positions.nmea;GpsDataSource.SYSTEM->positions.system;GpsDataSource.DEMO->positions.demo.takeIf{positions.demoStatus.running}}
                raw?.let{acceptedPosition.submit(source,it)}
            }
        }
        viewModelScope.launch{
            combine(sources,nav.connectionState,nav.connectionStartedElapsed,diagnosticsForUi,dao.sessions()){sourceAndDemo,connection,connectionStarted,diagnostics,sessions->
                arrayOf(sourceAndDemo,connection,connectionStarted,diagnostics,sessions)
            }.collect{values->
                @Suppress("UNCHECKED_CAST") val sourceAndDemo=values[0] as Pair<PositionSources,DemoGpsStatus>
                val position=sourceAndDemo.first
                @Suppress("UNCHECKED_CAST") val sessions=values[4] as List<AnchorSessionEntity>
                val active=sessions.firstOrNull{it.active}
                val connection=values[1] as NmeaConnectionState
                val nowElapsed=android.os.SystemClock.elapsedRealtime()
                val trustedNmeaCourse=nmeaCourseTrustGate.update(position.nmea,nowElapsed)
                _ui.update{it.copy(nmeaFix=position.nmea,trustedNmeaCourse=trustedNmeaCourse,nmeaConnectionStartedElapsed=values[2] as Long?,systemFix=position.system,connection=connection,diagnostics=values[3] as NmeaDiagnostics,settings=position.settings,settingsReady=true,sessions=sessions,active=active,demoGps=sourceAndDemo.second)}
                observePoints(active)
                if(selectedApproachClusterId!=null)refreshAnchorageApproach(nowElapsed)
            }
        }
        viewModelScope.launch{acceptedPosition.state.map{accepted->delay(UI_POSITION_FRAME_MILLIS);accepted}.collect{accepted->_ui.update{it.copy(fix=accepted.acceptedFix,positionHealth=accepted.health,acceptedPosition=accepted)};refreshDepthUi()}}
        // Rotation-vector sensors commonly publish much faster than Android GNSS. Keeping
        // this stream independent makes the boat symbol turn immediately while the
        // estimator still receives phone-heading evidence only with accepted positions.
        viewModelScope.launch{phoneHeadingRepository.sample.collect{sample->
            _ui.update{it.copy(phoneHeading=sample)}
            val nowElapsed=android.os.SystemClock.elapsedRealtime()
            if(selectedApproachClusterId!=null&&nowElapsed-lastApproachHeadingRefreshElapsed>=100L){
                lastApproachHeadingRefreshElapsed=nowElapsed
                refreshAnchorageApproach(nowElapsed)
            }
        }}
        viewModelScope.launch{liveDepthRepository.state.collect{value->_ui.update{it.copy(liveDepth=value)}}}
        viewModelScope.launch{liveWindRepository.state.collect{value->_ui.update{it.copy(liveWind=value)}}}
        viewModelScope.launch{conditionRuntime.state.collect{value->_ui.update{it.copy(conditions=value)}}}
        viewModelScope.launch{anchorageRepository.anchorages.collect{value->_ui.update{it.copy(savedAnchorages=value)}}}
        viewModelScope.launch{anchorageApproachRepository.clusters.collect{clusters->
            if(selectedApproachClusterId!=null&&clusters.none{it.id==selectedApproachClusterId})selectedApproachClusterId=null
            _ui.update{it.copy(anchorageClusters=clusters)}
            refreshAnchorageApproach()
        }}
        viewModelScope.launch{prefs.settings.map{it.gpsDataSource}.distinctUntilChanged().collect{systemLocation.setAppEnabled(it==GpsDataSource.SYSTEM||it==GpsDataSource.DEMO)}}
        viewModelScope.launch{mockManager.status.collect{status->_ui.update{current->val defaultInactive=status.state==MockGpsState.INACTIVE&&status.message=="Android GPS is using the normal system source.";current.copy(mockGps=status,proxyFeedback=if(defaultInactive)current.proxyFeedback?:status.message else status.message)}}}
        viewModelScope.launch{alarmUi.snapshot.collect{snapshot->_ui.update{it.copy(alarmSnapshot=snapshot)}}}
        viewModelScope.launch{sharingServer.status.collect{status->_ui.update{it.copy(nmeaSharing=status)}}}
        viewModelScope.launch{sonarDao.surveys().collect{surveys->
            val selected=_ui.value.selectedSonarSurveyId?.takeIf{selectedId->selectedId==CORRECTED_SONAR_HISTORY_ID||surveys.any{it.id==selectedId}}?:surveys.firstOrNull()?.id
            _ui.update{it.copy(sonarSurveys=surveys,selectedSonarSurveyId=selected)}
            if(_ui.value.activeSonarSurvey==null)observeSonarSamples(selected)
        }}
        viewModelScope.launch{sonarRecorder.status.collect{status->
            val selected=status.activeSurvey?.id?:_ui.value.selectedSonarSurveyId?:_ui.value.sonarSurveys.firstOrNull()?.id
            _ui.update{it.copy(activeSonarSurvey=status.activeSurvey,sonarRecorder=status,selectedSonarSurveyId=selected)}
            observeSonarSamples(selected)
            refreshDepthUi()
        }}
        viewModelScope.launch{linzDepthRepository.state.collect{value->_ui.update{it.copy(linzDepth=value)};refreshDepthUi()}}
        viewModelScope.launch{linzDepthRepository.diagnostics.collect{value->_ui.update{it.copy(linzDepthDiagnostics=value)}}}
        viewModelScope.launch{backupManager.state.collect{value->_ui.update{it.copy(backup=value)}}}
        viewModelScope.launch{runtimeDiagnostics.state.collect{value->_ui.update{it.copy(runtimeDiagnostics=value)}}}
        viewModelScope.launch{incidentLogger.recent.collect{value->_ui.update{it.copy(incidents=value)}}}
        viewModelScope.launch{supportBundleManager.state.collect{value->_ui.update{it.copy(supportBundle=value)}}}
        viewModelScope.launch{offlineMapRepository.state.collect{value->_ui.update{it.copy(offlineMap=value)}}}
        viewModelScope.launch{combine(acceptedPosition.state.map{it.acceptedFix}.distinctUntilChanged(),prefs.settings.map{it.showLinzDepthReference}.distinctUntilChanged()){fix,enabled->fix to enabled}.conflate().collect{(fix,enabled)->delay(2_000);if(enabled&&fix?.valid==true)linzDepthRepository.refresh(fix.latitude,fix.longitude)}}
        viewModelScope.launch{while(true){refreshDepthUi();refreshWatchSafety();refreshAnchorageApproach();delay(1_000)}}
        viewModelScope.launch{while(true){refreshStorageHealth();delay(30_000)}}
        incidentLogger.record("app","UI_STARTED")
    }

    private companion object { const val UI_POSITION_FRAME_MILLIS=250L;const val MAX_ACTIVE_TRAIL_POINTS=4_800 }

    private fun refreshWatchSafety(){
        val state=_ui.value
        val report=WatchPreflightEvaluator.evaluate(WatchSafetyInput(
            nowElapsed=android.os.SystemClock.elapsedRealtime(),nowWall=System.currentTimeMillis(),settings=state.settings,
            selectedFix=if(state.active==null&&state.settings.demoMode)state.systemFix else state.fix,nmeaConnection=state.connection,device=safetyProbe.snapshot(),sonar=state.sonarRecorder,
        ))
        _ui.update{it.copy(watchSafety=report)}
    }

    private fun refreshAnchorageApproach(nowElapsed:Long=android.os.SystemClock.elapsedRealtime()){
        val state=_ui.value
        if(state.active!=null&&selectedApproachClusterId!=null)selectedApproachClusterId=null
        val accepted=state.fix?.takeIf{
            it.valid&&state.positionHealth!=com.yokuli.anchorwatch.domain.model.PositionHealth.GPS_LOST
        }
        val physicalNmeaHeadingFresh=state.nmeaFix?.let{fix->
            fix.headingSource==com.yokuli.anchorwatch.domain.model.HeadingSource.NMEA_PHYSICAL&&
                fix.headingTrueDegrees!=null&&
                (fix.headingReceivedElapsedRealtime?:fix.receivedElapsedRealtime).let{nowElapsed-it in 0L..ApproachDirectionPolicy.FRESH_MILLIS}
        }==true
        val trustedNmeaCourse=state.trustedNmeaCourse?.takeIf{it.isFresh(nowElapsed)}
        val vesselHeadingAvailable=state.connection==NmeaConnectionState.CONNECTED&&
            (physicalNmeaHeadingFresh||trustedNmeaCourse!=null)
        val headingMode=if(state.approachHeadingMode==ApproachHeadingMode.VESSEL&&!vesselHeadingAvailable)ApproachHeadingMode.PHONE else state.approachHeadingMode
        val approach=AnchorageApproachEngine.evaluate(
            clusters=state.anchorageClusters,
            selectedClusterId=selectedApproachClusterId,
            positionLatitude=accepted?.latitude,
            positionLongitude=accepted?.longitude,
        ){bearing->
            ApproachDirectionPolicy.resolve(
                nowElapsed=nowElapsed,
                targetBearingDegrees=bearing,
                nmeaTrueHeadingDegrees=state.nmeaFix?.headingTrueDegrees,
                nmeaHeadingReceivedElapsed=state.nmeaFix?.headingReceivedElapsedRealtime?:state.nmeaFix?.receivedElapsedRealtime,
                cogTrueDegrees=trustedNmeaCourse?.trueDegrees,
                sogKnots=trustedNmeaCourse?.sogKnots,
                cogReceivedElapsed=trustedNmeaCourse?.receivedElapsedRealtime,
                phoneTrueHeadingDegrees=state.phoneHeading.trueHeadingDegrees,
                phoneHeadingTrusted=state.phoneHeading.receivedElapsedRealtime?.let{nowElapsed-it in 0L..1_500L}==true,
                preferredMode=headingMode,
                cogTrustedBySourcePolicy=trustedNmeaCourse!=null,
            )
        }
        val allDistances=if(accepted==null)emptyList() else AnchorageNearbyPolicy.distances(
            accepted.latitude,accepted.longitude,state.anchorageClusters,
        )
        val promptIds=anchorageNearbyTracker.update(
            allDistances,
            automaticPromptEnabled=state.active==null&&selectedApproachClusterId==null,
        )
        val prompt=allDistances.filter{it.cluster.id in promptIds}
        _ui.update{it.copy(anchorageApproach=approach,nearbyAnchoragePrompt=prompt,approachHeadingMode=headingMode,vesselApproachHeadingAvailable=vesselHeadingAvailable)}
    }

    private fun refreshStorageHealth()=viewModelScope.launch{
        val value=storageHealthRepository.snapshot();_ui.update{it.copy(storageHealth=value)}
    }

    private fun observePoints(session:AnchorSessionEntity?){
        if(pointsJob?.isActive==true&&observedSessionId==session?.id)return
        pointsJob?.cancel()
        observedSessionId=session?.id
        if(session==null){_ui.update{it.copy(points=emptyList())};return}
        // AnchorWatchRuntime owns centre estimation. Compose observes only the
        // bounded render trail, avoiding an all-session query and refit per insert.
        pointsJob=viewModelScope.launch{dao.recentPoints(session.id,MAX_ACTIVE_TRAIL_POINTS).collect{points->_ui.update{it.copy(points=points)}}}
    }

    private fun observeSonarSamples(surveyId:Long?){
        _ui.update{it.copy(selectedSonarSurveyId=surveyId)}
        if(sonarSamplesJob?.isActive==true&&observedSonarSurveyId==surveyId)return
        sonarSamplesJob?.cancel();observedSonarSurveyId=surveyId
        if(surveyId==null){_ui.update{it.copy(sonarSamples=emptyList(),sonarGrid=SonarGrid.build(emptyList()))};return}
        val scope=if(surveyId==CORRECTED_SONAR_HISTORY_ID)SonarGridScope.CORRECTED_HISTORY else SonarGridScope.SURVEY
        val scopeId=if(surveyId==CORRECTED_SONAR_HISTORY_ID)SonarGridScope.CORRECTED_HISTORY_ID else surveyId
        sonarSamplesJob=viewModelScope.launch{
            var grid=SonarGrid.fromPersisted(sonarDao.gridCellsNow(scope,scopeId))
            _ui.update{it.copy(sonarSamples=emptyList(),sonarGrid=grid,sonarGridVersion=it.sonarGridVersion+1,sonarGridChangedCells=emptySet())};refreshDepthUi()
            sonarGridUpdater.changes.collect{change->
                if(change.scopeType!=scope||change.scopeId!=scopeId)return@collect
                if(change.reload){grid=SonarGrid.fromPersisted(sonarDao.gridCellsNow(scope,scopeId));_ui.update{it.copy(sonarGrid=grid,sonarGridVersion=it.sonarGridVersion+1,sonarGridChangedCells=emptySet())}}
                else{val x=change.gridX?:return@collect;val y=change.gridY?:return@collect;grid.applyCell(x,y,change.cell);_ui.update{it.copy(sonarGrid=grid,sonarGridVersion=it.sonarGridVersion+1,sonarGridChangedCells=if(it.page==0)it.sonarGridChangedCells+(x to y)else emptySet())}}
                refreshDepthUi()
            }
        }
    }

    fun consumeSonarGridChanges(version:Long)=_ui.update{state->if(state.sonarGridVersion==version)state.copy(sonarGridChangedCells=emptySet())else state}

    private fun refreshDepthUi(nowElapsed:Long=android.os.SystemClock.elapsedRealtime()){
        _ui.update{state->
            val recorder=state.sonarRecorder;val age=recorder.lastDepthReceivedElapsedRealtime?.let{(nowElapsed-it).coerceAtLeast(0L)};val fresh=age!=null&&age<=2_000L
            val inspection=if(state.settings.showPersonalMapReference)state.fix?.let{state.sonarGrid.inspect(it.latitude,it.longitude)}else null
            val selectedSurvey=state.selectedSonarSurveyId?.takeIf{it!=CORRECTED_SONAR_HISTORY_ID}?.let{id->state.sonarSurveys.firstOrNull{it.id==id}}
            state.copy(depthUi=DepthUiState(
                liveDepthMeters=recorder.lastDepthMeters.takeIf{fresh},liveDepthReference=recorder.lastDepthReference.takeIf{fresh},liveDepthAgeMillis=age,
                correctedDepthMeters=recorder.lastDepthMeters.takeIf{fresh&&recorder.lastDepthIsChartDatum},linz=state.linzDepth,
                personalMapDepthMeters=inspection?.depthMeters,personalMapMeasured=inspection?.measured,personalMapSamples=inspection?.sampleCount,personalMapUncertaintyMeters=inspection?.uncertaintyMeters,
                personalSurveyName=selectedSurvey?.name,personalSurveyStartedAt=selectedSurvey?.startedAt,
            ))
        }
    }

    fun validateProfile(profile:ConnectionProfile)=endpointPreflight.validate(profile,_ui.value.settings.nmeaSharingEnabled,_ui.value.settings.nmeaSharingPort)

    fun saveAndConnect(profile:ConnectionProfile)=viewModelScope.launch{
        if(_ui.value.connection!=NmeaConnectionState.DISCONNECTED||_ui.value.connectionAttempt.state==ConnectionAttemptState.TESTING)return@launch
        endpointPreflight.validate(profile,_ui.value.settings.nmeaSharingEnabled,_ui.value.settings.nmeaSharingPort)?.let{message->_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,message))};return@launch}
        _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.TESTING,"Testing the endpoint and waiting for valid NMEA data…"))}
        val result=endpointPreflight.check(profile,_ui.value.settings.nmeaSharingEnabled,_ui.value.settings.nmeaSharingPort)
        if(result.isFailure){val detail=result.exceptionOrNull()?.message?.takeIf{it.isNotBlank()}?:"The NMEA endpoint test failed.";_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,detail))};return@launch}
        val previous=_ui.value.settings
        prefs.save(previous.copy(profile=profile))
        val connectedAt=android.os.SystemClock.elapsedRealtime()
        nav.connect(profile)
        val liveFix=withTimeoutOrNull(10_000){nav.fix.filterNotNull().first{it.valid&&it.receivedElapsedRealtime>=connectedAt}}
        if(liveFix==null){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"The endpoint test passed, but the live NMEA connection did not deliver a fresh position."))};return@launch}
        val active=_ui.value.active
        if(previous.demoMode){
            prefs.save(previous.copy(profile=profile,gpsDataSource=GpsDataSource.DEMO,mockEnabled=false))
        }else if(active!=null){
            // A live or paused session owns its persisted source. Connecting a
            // server must not silently alter the safety input of that session.
            prefs.save(previous.copy(profile=profile))
        }else{
            prefs.save(previous.copy(profile=profile,gpsDataSource=GpsDataSource.NMEA,mockEnabled=false))
        }
        _ui.update{it.copy(connectionAttempt=ConnectionAttempt())}
    }

    fun disconnect(){
        if(_ui.value.active?.paused==false&&_ui.value.active?.positionSource==GpsDataSource.NMEA.name){
            _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"This active anchor session is locked to NMEA. Pause the watch before disconnecting, or lift the anchor to end the session."))}
            return
        }
        _ui.update{it.copy(connectionAttempt=ConnectionAttempt())};nav.disconnect()
    }
    fun stopActiveWatchAndDisconnect(){
        _ui.update{it.copy(connectionAttempt=ConnectionAttempt())}
        ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.STOP_WATCH_AND_DISCONNECT))
    }
    fun clearConnectionAttempt()=_ui.update{it.copy(connectionAttempt=ConnectionAttempt())}
    fun updateSettings(settings:AppSettings){_ui.update{it.copy(settings=settings)};viewModelScope.launch{prefs.save(settings)}}
    fun completeOnboarding()=updateSettings(_ui.value.settings.copy(onboardingCompleted=true))
    fun setSonarLayerEnabled(enabled:Boolean,acceptDisclaimer:Boolean=false){
        val state=_ui.value
        updateSettings(state.settings.copy(sonarLayerEnabled=enabled,sonarDisclaimerAccepted=state.settings.sonarDisclaimerAccepted||acceptDisclaimer))
    }
    fun exportBackup(uri:Uri)=viewModelScope.launch{backupManager.export(uri)}
    fun restoreBackup(uri:Uri)=viewModelScope.launch{backupManager.restore(uri)}
    fun clearBackupResult()=backupManager.clearResult()
    fun importOfflineMap(uri:Uri)=viewModelScope.launch{
        val outcome=offlineMapRepository.import(uri)
        val result=outcome.getOrNull()
        if(result!=null){
            val current=_ui.value.settings
            prefs.save(current.copy(offlineMapEnabled=true,offlineMapName=result.info.name,offlineMapAttribution=result.info.attribution))
            incidentLogger.record("offline_map","IMPORTED",details=mapOf("name" to result.info.name,"tiles" to result.info.tileCount,"bytes" to result.info.sizeBytes))
            refreshStorageHealth()
        }else{val error=outcome.exceptionOrNull();_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,error?.message?:"Offline map import failed."))}}
    }
    fun removeOfflineMap()=viewModelScope.launch{
        val outcome=offlineMapRepository.remove()
        if(outcome.isSuccess){val current=_ui.value.settings;prefs.save(current.copy(offlineMapEnabled=false,offlineMapName=null,offlineMapAttribution=null));incidentLogger.record("offline_map","REMOVED");refreshStorageHealth()}
        else _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,outcome.exceptionOrNull()?.message?:"Offline map removal failed."))}
    }
    fun setOfflineMapEnabled(enabled:Boolean){val current=_ui.value.settings;updateSettings(current.copy(offlineMapEnabled=enabled&&_ui.value.offlineMap.installed))}
    fun createOfflineMapProvider()=offlineMapRepository.provider()
    fun exportSupportBundle(uri:Uri)=viewModelScope.launch{supportBundleManager.export(uri)}
    fun clearSupportBundleResult()=supportBundleManager.clearResult()
    fun clearIncidentLog()=viewModelScope.launch{storageHealthRepository.clearIncidentLog();refreshStorageHealth()}
    fun clearRebuildableCaches()=viewModelScope.launch{storageHealthRepository.clearRebuildableCaches();incidentLogger.record("storage","REBUILDABLE_CACHES_CLEARED");refreshStorageHealth()}
    fun refreshStorage()=refreshStorageHealth()
    fun confirmAlarmAudible(){val current=_ui.value.settings;updateSettings(current.copy(alarmAudibleConfirmedAt=System.currentTimeMillis()));incidentLogger.record("alarm","AUDIBLE_TEST_CONFIRMED")}
    fun setNmeaSharing(enabled:Boolean,port:Int){
        val safePort=port.takeIf{it in 1024..65535}
        if(safePort==null){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"NMEA Sharing port must be between 1024 and 65535."))};return}
        val settings=_ui.value.settings.copy(nmeaSharingEnabled=enabled,nmeaSharingPort=safePort);_ui.update{it.copy(settings=settings)}
        viewModelScope.launch{prefs.save(settings);if(enabled||sharingServer.status.value.state!=com.yokuli.anchorwatch.data.sharing.SharingServerState.STOPPED)ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.SET_NMEA_SHARING).putExtra("enabled",enabled).putExtra("port",safePort))}
    }
    fun deleteHistorySession(session:AnchorSessionEntity){if(session.active)return;viewModelScope.launch{dao.deleteCompletedSession(session.id)}}
    fun setMapType(mapType:Int){val value=mapType.takeIf{it in 1..3}?:1;val updated=_ui.value.settings.copy(mapType=value);_ui.update{it.copy(settings=updated)};viewModelScope.launch{prefs.save(updated)}}
    fun setGpsDataSource(source:GpsDataSource)=switchGpsDataSource(source)
    fun switchGpsDataSource(source:GpsDataSource)=viewModelScope.launch{
        val current=_ui.value.settings
        if(current.demoMode){
            if(source!=GpsDataSource.DEMO)_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Demo mode owns the App GPS source. Lift the current anchor and disable Demo mode before choosing System or NMEA."))}
            return@launch
        }
        val activeSession=_ui.value.active
        val lockedSource=activeSession?.positionSource?.let{runCatching{GpsDataSource.valueOf(it)}.getOrNull()}
        if(!GpsSourceSafety.allowsSessionSource(activeSession!=null,lockedSource,source)){
            activeSession?.let{dao.insertEvent(AlarmEventEntity(sessionId=it.id,timestamp=System.currentTimeMillis(),type="WATCH_GPS_SOURCE_CHANGE_REJECTED_ACTIVE_SESSION",detail="LOCKED=${it.positionSource};REQUESTED=${source.name}"))}
            _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"GPS source is locked for the whole active anchor session, including while paused. Lift the anchor before changing source."))}
            return@launch
        }
        if(source==current.gpsDataSource)return@launch
        if(source==GpsDataSource.NMEA){
            val availability=NmeaSourceSelectionPolicy.availability(_ui.value.connection,_ui.value.nmeaFix,_ui.value.nmeaConnectionStartedElapsed,android.os.SystemClock.elapsedRealtime(),current.gpsLossSeconds*1000L)
            if(availability!=NmeaSourceAvailability.AVAILABLE){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Connect the NMEA source and wait for a fresh valid position before selecting NMEA GPS."))};return@launch}
        }
        if(source==GpsDataSource.DEMO){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Demo GPS is selected only by enabling Demo mode."))};return@launch}
        if(source==GpsDataSource.SYSTEM&&GpsSourceSafety.blocksSystemGps(current.mockEnabled,mockManager.status.value.state)){
            _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Disable the global NMEA GPS proxy first. While Android mock mode is active, System GPS is not an independent source."))};return@launch
        }
        if(source==GpsDataSource.DEMO&&GpsSourceSafety.blocksSystemGps(current.mockEnabled,mockManager.status.value.state)){
            _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Disable the global NMEA GPS proxy before selecting Demo. Demo uses the real System GPS as its starting point."))};return@launch
        }
        if(current.gpsDataSource==GpsDataSource.DEMO&&source!=GpsDataSource.DEMO)demoLocation.stop();prefs.save(current.copy(gpsDataSource=source,mockEnabled=false));_ui.update{it.copy(connectionAttempt=ConnectionAttempt())}
    }
    fun setDemoMode(enabled:Boolean)=viewModelScope.launch{
        val current=_ui.value.settings
        if(_ui.value.active!=null){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Lift the current anchor session before changing Demo mode."))};return@launch}
        if(enabled&&GpsSourceSafety.blocksSystemGps(current.mockEnabled,mockManager.status.value.state)){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Disable the global NMEA GPS proxy before enabling Demo mode. Demo needs an independent System GPS origin."))};return@launch}
        demoLocation.stop()
        prefs.save(current.copy(demoMode=enabled,gpsDataSource=if(enabled)GpsDataSource.DEMO else GpsDataSource.SYSTEM,mockEnabled=false))
        _ui.update{it.copy(connectionAttempt=ConnectionAttempt())}
    }
    fun updateDemoConfiguration(scenario:com.yokuli.anchorwatch.domain.model.DemoScenario?=null,speed:Int?=null)=viewModelScope.launch{
        val current=_ui.value.settings
        if(_ui.value.active!=null){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Lift the current anchor session before changing the Demo trajectory."))};return@launch}
        if(!current.demoMode)return@launch
        prefs.save(current.copy(demoScenario=scenario?:current.demoScenario,demoSpeedMultiplier=speed?:current.demoSpeedMultiplier))
    }
    fun onPermissionsChanged(){systemLocation.refreshPermission()}

    fun clearDiagnostics()=nav.clearDiagnostics()
    fun arm(lat:Double,lon:Double,input:AnchorWatchInput){
        val intent=Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.ARM)
            .putExtra("lat",lat).putExtra("lon",lon).putExtra("rode",input.rodeMeters).putExtra("depth",input.depthMeters?:Double.NaN).putExtra("bowHeight",input.bowHeightMeters).putExtra("boatLength",input.boatLengthMeters?:Double.NaN)
            .putExtra("antennaOffset",if(input.positionSource==GpsDataSource.NMEA)_ui.value.settings.nmeaGpsAntennaToBowMeters else 0.0)
            .putExtra("warning",maxOf(input.alarmRadiusMeters*.8,input.alarmRadiusMeters-10).coerceAtMost(input.alarmRadiusMeters-.1)).putExtra("alarm",input.alarmRadiusMeters).putExtra("placement",input.placement.name).putExtra("rangeMode",input.rangeMode.name).putExtra("safetyPreset",input.safetyPreset.name).putExtra("positionSource",input.positionSource.name).putExtra("centerSource",input.centerSource.name).putExtra("usePhoneHeading",input.usePhoneHeading)
            .putExtra("depthSource",input.depthSource.name)
            .putExtra("depthGuard",input.conditions.depthGuardEnabled).putExtra("shallowDepth",input.conditions.shallowDepthAlarmMeters?:Double.NaN).putExtra("deepDepth",input.conditions.deepDepthAlarmMeters?:Double.NaN).putExtra("windGuard",input.conditions.windGuardEnabled).putExtra("windWarning",input.conditions.windWarningKnots?:Double.NaN).putExtra("windAlarm",input.conditions.windAlarmKnots?:Double.NaN).putExtra("windShift",input.conditions.windShiftEnabled).putExtra("windShiftDegrees",input.conditions.windShiftThresholdDegrees?:Double.NaN).putExtra("apparentFallback",input.conditions.windAllowApparentFallback)
        ContextCompat.startForegroundService(app,intent)
    }
    fun updateAnchorSettings(input:AnchorWatchInput){val intent=Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.UPDATE_RADIUS).putExtra("alarm",input.alarmRadiusMeters);ContextCompat.startForegroundService(app,intent)}
    fun setPhoneHeadingEvidence(enabled:Boolean)=ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.UPDATE_PHONE_HEADING).putExtra("enabled",enabled))
    fun updateConditionGuards(config:ConditionGuardConfig){val value=config.validated();ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.UPDATE_CONDITION_GUARDS).putExtra("depthGuard",value.depthGuardEnabled).putExtra("shallowDepth",value.shallowDepthAlarmMeters?:Double.NaN).putExtra("deepDepth",value.deepDepthAlarmMeters?:Double.NaN).putExtra("windGuard",value.windGuardEnabled).putExtra("windWarning",value.windWarningKnots?:Double.NaN).putExtra("windAlarm",value.windAlarmKnots?:Double.NaN).putExtra("windShift",value.windShiftEnabled).putExtra("windShiftDegrees",value.windShiftThresholdDegrees?:Double.NaN).putExtra("apparentFallback",value.windAllowApparentFallback))}
    fun resetWindBaseline()=ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.RESET_WIND_BASELINE))
    fun pauseWatch()=app.startService(Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.PAUSE_WATCH))
    fun resumeWatch()=ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.RESUME_WATCH))
    fun liftAnchor()=ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.LIFT_ANCHOR))
    fun stop()=pauseWatch()
    fun acknowledge()=app.startService(Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.ACK))
    fun acceptEstimatedCenter(session:AnchorSessionEntity)=session.candidateId?.let{candidateId->ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.ACCEPT_ESTIMATED_CENTER).putExtra("sessionId",session.id).putExtra("candidateId",candidateId))}
    fun keepCurrentCenter(session:AnchorSessionEntity)=session.candidateId?.let{candidateId->ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.KEEP_CURRENT_CENTER).putExtra("sessionId",session.id).putExtra("candidateId",candidateId))}
    fun continueEstimatingCenter(session:AnchorSessionEntity)=session.candidateId?.let{candidateId->ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.CONTINUE_ESTIMATING_CENTER).putExtra("sessionId",session.id).putExtra("candidateId",candidateId))}
    fun testAlarm()=ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.TEST_ALARM))
    fun stopAlarmTest()=app.startService(Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.STOP_ALARM_TEST))
    fun startSonarSurvey(name:String,tideMode:TideMode,manualTideOffsetMeters:Double,tideStationId:String?=null){
        val intent=Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.START_SONAR_SURVEY).putExtra("name",name).putExtra("tideMode",tideMode.name).putExtra("manualTideOffset",manualTideOffsetMeters).putExtra("tideStationId",tideStationId)
        ContextCompat.startForegroundService(app,intent)
    }
    fun stopSonarSurvey()=app.startService(Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.STOP_SONAR_SURVEY))
    fun renameSonarSurvey(surveyId:Long,name:String)=viewModelScope.launch{sonarRecorder.rename(surveyId,name)}
    fun deleteSonarSurvey(surveyId:Long)=viewModelScope.launch{sonarRecorder.delete(surveyId)}
    fun rebuildSonarSurvey(surveyId:Long)=viewModelScope.launch{sonarRecorder.rebuild(surveyId)}
    fun selectSonarSurvey(surveyId:Long)=observeSonarSamples(surveyId)
    fun selectCorrectedSonarHistory()=observeSonarSamples(CORRECTED_SONAR_HISTORY_ID)
    fun exportSonarCsv(survey:SonarSurveyEntity)=viewModelScope.launch{
        val file=withContext(Dispatchers.IO){java.io.File(app.cacheDir,"sonar-${survey.id}.csv").also{target->target.bufferedWriter().use{writer->
            writer.appendLine("timestamp,latitude,longitude,raw_depth_m,measured_depth_m,normalized_depth_m,reference,sentence,nmea_offset_m,gps_source,position_provider,position_accuracy_m,hdop,sog_knots,position_age_ms,fix_trust,disposition,usable,position_correction,base_grid_x,base_grid_y,survey_id,tide_mode,tide_height_m,tide_station_id,tide_station_distance_m,tide_year,tide_method,tide_source,tide_source_updated_at,tide_status")
            var afterTimestamp=Long.MIN_VALUE;var afterId=Long.MIN_VALUE
            while(true){val page=sonarDao.samplesPage(survey.id,afterTimestamp,afterId,1_000);if(page.isEmpty())break;page.forEach{sample->writer.appendLine("${sample.timestamp},${sample.latitude},${sample.longitude},${sample.rawDepthMeters},${sample.measuredDepthMeters},${sample.normalizedDepthMeters?:""},${sample.depthReference},${sample.sentenceType},${sample.nmeaOffsetMeters?:""},${sample.gpsSource},${sample.positionProvider},${sample.horizontalAccuracyMeters?:""},${sample.hdop?:""},${sample.sogKnots?:""},${sample.positionAgeMillis},${sample.fixTrust},${sample.disposition},${sample.usable},${sample.positionCorrectionMethod},${sample.baseGridX},${sample.baseGridY},${survey.id},${sample.tideCorrectionMode},${sample.tideHeightMetersApplied?:""},${sample.tideStationId?:""},${sample.tideStationDistanceMeters?:""},${sample.tidePredictionYear?:""},${sample.tideCorrectionMethod?:""},${sample.tideSource?:""},${sample.tideSourceUpdatedAt?:""},${sample.tideCorrectionStatus}")};val last=page.last();afterTimestamp=last.timestamp;afterId=last.id}
        }}}
        shareExport(file,"text/csv")
    }
    fun startGpsProxy(){val state=_ui.value;val problem=when{state.settings.gpsDataSource!=GpsDataSource.NMEA->"Select NMEA GPS before enabling the global proxy.";state.connection!=NmeaConnectionState.CONNECTED->"Connect to the NMEA source first.";state.nmeaFix?.valid!=true->"The NMEA connection has not supplied a valid position yet.";else->null};if(problem!=null){_ui.update{it.copy(proxyFeedback=problem)};return};_ui.update{it.copy(proxyFeedback="Checking Android mock-location access…")};ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.START_PROXY))}
    fun stopGpsProxy(){_ui.update{it.copy(proxyFeedback=null)};app.startService(Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.STOP_PROXY))}
    fun openDeveloperOptions(){runCatching{app.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}.onFailure{app.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}}
    fun openAlarmNotificationSettings(){val channelReady=android.os.Build.VERSION.SDK_INT>=26&&app.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel(AnchorForegroundService.ALARM_CH)!=null;val intent=when{channelReady->Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,app.packageName).putExtra(android.provider.Settings.EXTRA_CHANNEL_ID,AnchorForegroundService.ALARM_CH);android.os.Build.VERSION.SDK_INT>=26->Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,app.packageName);else->Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${app.packageName}"))};app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    fun openBatteryOptimization(){app.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    fun openFullScreenAlarmSettings(){
        val intent=if(android.os.Build.VERSION.SDK_INT>=34)Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,Uri.parse("package:${app.packageName}")) else Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,app.packageName)
        runCatching{app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}.onFailure{openAlarmNotificationSettings()}
    }
    fun openAnchorInGoogleMaps(session:AnchorSessionEntity){
        if(session.centerStatus!=com.yokuli.anchorwatch.domain.model.AnchorCenterStatus.RESOLVED.name)return
        openCoordinatesInGoogleMaps(session.anchorLatitude,session.anchorLongitude)
    }
    fun openAnchorageInGoogleMaps(value:SavedAnchorageEntity)=openCoordinatesInGoogleMaps(value.latitude,value.longitude)
    fun approachSavedAnchorage(savedAnchorageId:Long){
        val cluster=_ui.value.anchorageClusters.firstOrNull{savedAnchorageId in it.savedAnchorageIds}?:return
        approachAnchorage(cluster.id)
    }
    fun approachAnchorage(clusterId:String){
        if(_ui.value.anchorageClusters.none{it.id==clusterId})return
        if(_ui.value.settings.anchorageApproachDisclaimerAccepted)startAnchorageApproach(clusterId)
        else _ui.update{it.copy(page=0,approachDisclaimerTargetId=clusterId)}
    }
    fun confirmAnchorageApproachDisclaimer(){
        val target=_ui.value.approachDisclaimerTargetId?:return
        val updated=_ui.value.settings.copy(anchorageApproachDisclaimerAccepted=true)
        _ui.update{it.copy(settings=updated,approachDisclaimerTargetId=null)}
        viewModelScope.launch{prefs.save(updated)}
        startAnchorageApproach(target)
    }
    fun dismissAnchorageApproachDisclaimer()=_ui.update{it.copy(approachDisclaimerTargetId=null)}
    private fun startAnchorageApproach(clusterId:String){
        selectedApproachClusterId=clusterId
        phoneHeadingRepository.setApproachDemand(true)
        anchorageNearbyTracker.dismiss(_ui.value.nearbyAnchoragePrompt.map{it.cluster.id})
        _ui.update{it.copy(page=0,approachDisclaimerTargetId=null,nearbyAnchoragePrompt=emptyList(),approachHeadingMode=if(it.vesselApproachHeadingAvailable)ApproachHeadingMode.VESSEL else ApproachHeadingMode.PHONE)}
        refreshAnchorageApproach()
    }
    fun setApproachHeadingMode(mode:ApproachHeadingMode){
        val state=_ui.value
        if(selectedApproachClusterId==null)return
        if(mode==ApproachHeadingMode.VESSEL&&!state.vesselApproachHeadingAvailable)return
        _ui.update{it.copy(approachHeadingMode=mode)}
        refreshAnchorageApproach()
    }
    fun cancelAnchorageApproach(){selectedApproachClusterId=null;phoneHeadingRepository.setApproachDemand(false);refreshAnchorageApproach()}
    fun setMapHeadingDisplayActive(active:Boolean){phoneHeadingRepository.setDisplayDemand(active)}
    fun dismissNearbyAnchorage(){
        anchorageNearbyTracker.dismiss(_ui.value.nearbyAnchoragePrompt.map{it.cluster.id})
        _ui.update{it.copy(nearbyAnchoragePrompt=emptyList())}
    }
    private fun openCoordinatesInGoogleMaps(latitude:Double,longitude:Double){
        val uri=android.net.Uri.parse(AnchorageShareContent.googleMapsUrl(latitude,longitude))
        val google=Intent(Intent.ACTION_VIEW,uri).setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val intent=if(google.resolveActivity(app.packageManager)!=null)google else Intent(Intent.ACTION_VIEW,uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching{app.startActivity(intent)}.onFailure{_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"No map application or browser is available to open the anchor position."))}}
    }
    fun shareAnchorageQr(value:SavedAnchorageEntity)=viewModelScope.launch{
        runCatching{
            val file=withContext(Dispatchers.IO){anchorageQrImageGenerator.generate(value,_ui.value.settings.appLanguage.usesChinese())}
            val uri=androidx.core.content.FileProvider.getUriForFile(app,"${app.packageName}.files",file)
            val send=Intent(Intent.ACTION_SEND).setType("image/png")
                .putExtra(Intent.EXTRA_STREAM,uri)
                .putExtra(Intent.EXTRA_TEXT,AnchorageShareContent.shareText(value))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            send.clipData=android.content.ClipData.newUri(app.contentResolver,"Saved anchorage",uri)
            val title=if(_ui.value.settings.appLanguage.usesChinese())"分享收藏锚地" else "Share saved anchorage"
            app.startActivity(Intent.createChooser(send,title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure{_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Could not create or share the anchorage QR image."))}}
    }
    fun page(index:Int)=_ui.update{it.copy(page=index,sonarGridChangedCells=emptySet())}
    fun follow(value:Boolean)=_ui.update{it.copy(follow=value)}
    fun requestRangeEditor()=_ui.update{it.copy(page=0,rangeEditorRequested=true)}
    fun consumeRangeEditorRequest()=_ui.update{it.copy(rangeEditorRequested=false)}
    fun loadHistoryEvents(sessionId:Long)=viewModelScope.launch{val events=dao.recentEvents(sessionId,30);_ui.update{it.copy(eventsBySession=mapOf(sessionId to events))}}
    fun saveAnchorage(value:SavedAnchorageEntity)=viewModelScope.launch{
        val proposed=value.copy(updatedAt=System.currentTimeMillis())
        try{anchorageRepository.save(proposed)}catch(cancelled:CancellationException){throw cancelled}catch(duplicate:DuplicateAnchorageException){
            _ui.update{it.copy(anchorageDuplicateExisting=duplicate.existing)}
        }catch(error:Throwable){
            android.util.Log.e("AnchorLibrary","Failed to save anchorage",error)
            _ui.update{it.copy(anchorageOperationError="Could not save the anchorage. No data was changed.")}
        }
    }
    fun dismissAnchorageDuplicate()=_ui.update{it.copy(anchorageDuplicateExisting=null)}
    fun deleteAnchorage(id:Long)=viewModelScope.launch{
        try{
            anchorageRepository.delete(id)
            _ui.update{state->state.copy(anchorageDuplicateExisting=state.anchorageDuplicateExisting?.takeUnless{it.id==id})}
        }catch(cancelled:CancellationException){throw cancelled}catch(error:Throwable){
            android.util.Log.e("AnchorLibrary","Failed to delete anchorage $id",error)
            _ui.update{it.copy(anchorageOperationError="Could not delete the anchorage. It is still saved.")}
        }
    }
    fun dismissAnchorageOperationError()=_ui.update{it.copy(anchorageOperationError=null)}
    fun exportCsv(session:AnchorSessionEntity)=viewModelScope.launch{
        val points=dao.points(session.id).first()
        val file=java.io.File(app.cacheDir,"anchor-${session.id}.csv")
        file.writeText(buildString{
            appendLine("timestamp,latitude,longitude,distance_from_anchor_m,gps_source,provider,hdop,horizontal_accuracy_m,fix_trust,was_quarantined,sog_knots,cog_deg,heading_deg,heading_source,heading_quality,heading_epoch,wind_direction_true,wind_speed_knots")
            points.forEach{appendLine("${it.timestamp},${it.latitude},${it.longitude},${it.distanceFromAnchor},${it.positionSource},${it.positionProvider},${it.hdop?:""},${it.horizontalAccuracyMeters?:""},${it.fixTrust},${it.wasQuarantined},${it.sog?:""},${it.cog?:""},${it.heading?:""},${it.headingSource},${it.headingQuality},${it.headingEpoch?:""},${it.windDirectionTrue?:""},${it.windSpeedKnots?:""}")}
        })
        shareExport(file,"text/csv")
    }
    fun exportGpx(session:AnchorSessionEntity)=viewModelScope.launch{
        val points=dao.points(session.id).first();val events=dao.events(session.id).first();val file=java.io.File(app.cacheDir,"anchor-${session.id}.gpx")
        file.writeText(buildString{
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<gpx version=\"1.1\" creator=\"Anchor Watch\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
            if(events.isNotEmpty())appendLine("<metadata><desc>${xmlEscape(events.joinToString(" | "){event->"${java.time.Instant.ofEpochMilli(event.timestamp)} ${event.type} ${event.detail}"})}</desc></metadata>")
            appendLine("<wpt lat=\"${session.anchorLatitude}\" lon=\"${session.anchorLongitude}\"><name>Active anchor</name></wpt>")
            appendLine("<trk><name>Anchor session ${session.id}</name><trkseg>")
            points.forEach{appendLine("<trkpt lat=\"${it.latitude}\" lon=\"${it.longitude}\"><time>${java.time.Instant.ofEpochMilli(it.timestamp)}</time></trkpt>")}
            appendLine("</trkseg></trk>");appendLine("</gpx>")
        })
        shareExport(file,"application/gpx+xml")
    }
    private fun shareExport(file:java.io.File,mime:String){val uri=androidx.core.content.FileProvider.getUriForFile(app,"${app.packageName}.files",file);val intent=Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK);runCatching{app.startActivity(Intent.createChooser(intent,"Export anchor session").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}.onFailure{_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"No app is available to receive the export."))}}}
    private fun xmlEscape(value:String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    override fun onCleared(){phoneHeadingRepository.setApproachDemand(false);phoneHeadingRepository.setDisplayDemand(false);systemLocation.setAppEnabled(false);super.onCleared()}
}
