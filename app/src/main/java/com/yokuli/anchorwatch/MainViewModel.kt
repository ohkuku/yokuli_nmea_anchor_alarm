package com.yokuli.anchorwatch

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.data.NmeaInstrumentState
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
import com.yokuli.anchorwatch.data.database.TripDao
import com.yokuli.anchorwatch.data.database.TripSessionEntity
import com.yokuli.anchorwatch.data.export.TripExportManager
import com.yokuli.anchorwatch.data.diagnostics.IncidentLogger
import com.yokuli.anchorwatch.data.diagnostics.StorageHealth
import com.yokuli.anchorwatch.data.diagnostics.StorageHealthRepository
import com.yokuli.anchorwatch.data.diagnostics.SupportBundleManager
import com.yokuli.anchorwatch.data.diagnostics.SupportBundleState
import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.nmea.NmeaDiagnostics
import com.yokuli.anchorwatch.data.nmea.NmeaTransportDiagnostics
import com.yokuli.anchorwatch.data.nmea.NmeaEndpointPreflight
import com.yokuli.anchorwatch.data.nmea.NmeaFieldObservation
import com.yokuli.anchorwatch.data.nmea.NmeaFieldRepository
import com.yokuli.anchorwatch.data.nmea.Protocol
import com.yokuli.anchorwatch.data.nmea.output.NmeaOutputEndpointPolicy
import com.yokuli.anchorwatch.data.preferences.AppSettings
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.data.sharing.NmeaSharingServer
import com.yokuli.anchorwatch.data.sharing.NmeaSharingStatus
import com.yokuli.anchorwatch.data.sharing.LocalNmeaServerSettings
import com.yokuli.anchorwatch.data.sharing.LocalNmeaServerSettingsRepository
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
import com.yokuli.anchorwatch.domain.model.AnchorOriginMode
import com.yokuli.anchorwatch.domain.model.AnchorPlacementMode
import com.yokuli.anchorwatch.domain.model.AnchorRangeMode
import com.yokuli.anchorwatch.domain.model.AnchorSafetyPreset
import com.yokuli.anchorwatch.domain.model.AlarmSnapshot
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.domain.anchor.AnchorDepthSource
import com.yokuli.anchorwatch.domain.anchor.AnchorCentreRecalculationResult
import com.yokuli.anchorwatch.domain.anchor.AnchorCentreRecalculationStatus
import com.yokuli.anchorwatch.domain.anchor.AnchorCentreRecalculator
import com.yokuli.anchorwatch.domain.report.TripReport
import com.yokuli.anchorwatch.domain.report.TripReportEngine
import com.yokuli.anchorwatch.domain.report.AnchorReport
import com.yokuli.anchorwatch.domain.report.AnchorReportEngine
import com.yokuli.anchorwatch.location.GlobalMockLocationManager
import com.yokuli.anchorwatch.location.DemoGpsStatus
import com.yokuli.anchorwatch.location.DemoLocationRepository
import com.yokuli.anchorwatch.location.GpsSourceSafety
import com.yokuli.anchorwatch.location.MockGpsState
import com.yokuli.anchorwatch.location.MockGpsStatus
import com.yokuli.anchorwatch.location.NmeaSourceAvailability
import com.yokuli.anchorwatch.location.NmeaSourceSelectionPolicy
import com.yokuli.anchorwatch.location.NewAnchorPositionSourcePolicy
import com.yokuli.anchorwatch.location.SystemLocationRepository
import com.yokuli.anchorwatch.location.AcceptedPositionRepository
import com.yokuli.anchorwatch.location.AcceptedPositionState
import com.yokuli.anchorwatch.location.PhoneHeadingRepository
import com.yokuli.anchorwatch.location.PhoneHeadingSample
import com.yokuli.anchorwatch.location.vessel.DeviceBowAxis
import com.yokuli.anchorwatch.location.vessel.PhoneSensorCapabilities
import com.yokuli.anchorwatch.location.vessel.PhoneVesselAttitudeRepository
import com.yokuli.anchorwatch.location.vessel.PhoneHeadingAlignmentPolicy
import com.yokuli.anchorwatch.location.vessel.PhoneVesselMountState
import com.yokuli.anchorwatch.location.vessel.VesselMountCalibration
import com.yokuli.anchorwatch.location.vessel.VesselMountCalibrationRepository
import com.yokuli.anchorwatch.location.vessel.PhoneVesselOutputReadinessPolicy
import com.yokuli.anchorwatch.runtime.nmea.NmeaManualDisconnectRepository
import com.yokuli.anchorwatch.runtime.sharing.LocalNmeaServerRuntime
import com.yokuli.anchorwatch.runtime.sharing.LocalNmeaServerRuntimeStatus
import com.yokuli.anchorwatch.service.AnchorForegroundService
import com.yokuli.anchorwatch.runtime.RuntimeDiagnostics
import com.yokuli.anchorwatch.runtime.RuntimeDiagnosticsRepository
import com.yokuli.anchorwatch.runtime.RuntimeOwner
import com.yokuli.anchorwatch.runtime.RuntimeRequirement
import com.yokuli.anchorwatch.runtime.RuntimeResourceManager
import com.yokuli.anchorwatch.runtime.RuntimeResourceSnapshot
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
import com.yokuli.anchorwatch.data.vessel.VesselDataHub
import com.yokuli.anchorwatch.data.vessel.VesselDataSettings
import com.yokuli.anchorwatch.data.vessel.VesselSettingsRepository
import com.yokuli.anchorwatch.data.vessel.NmeaDeviceOutputSettings
import com.yokuli.anchorwatch.data.vessel.NmeaOutputTransportMode
import com.yokuli.anchorwatch.data.vessel.NmeaOutputPreset
import com.yokuli.anchorwatch.data.vessel.withPreset
import com.yokuli.anchorwatch.data.vessel.anyStreamSelected
import com.yokuli.anchorwatch.domain.vessel.NmeaOutputPurpose
import com.yokuli.anchorwatch.domain.vessel.PublicationPolicy
import com.yokuli.anchorwatch.domain.vessel.VesselSourcePreference
import com.yokuli.anchorwatch.data.vessel.OutputSettingsRepository
import com.yokuli.anchorwatch.data.trip.TripReplayLoader
import com.yokuli.anchorwatch.data.trip.TripReplayData
import com.yokuli.anchorwatch.data.trip.TripDashboardRepository
import com.yokuli.anchorwatch.data.trip.TripDashboard
import com.yokuli.anchorwatch.domain.vessel.VesselDataSnapshot
import com.yokuli.anchorwatch.runtime.output.PhonePositionNmeaOutputRuntime
import com.yokuli.anchorwatch.runtime.output.PhonePositionOutputStatus
import com.yokuli.anchorwatch.runtime.condition.ConditionRuntime
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

enum class ConnectionAttemptState { IDLE, TESTING, WARNING, FAILED }
data class ConnectionAttempt(val state:ConnectionAttemptState=ConnectionAttemptState.IDLE,val message:String="")
data class CentreRecalculationUiState(val sessionId:Long?=null,val sessionActive:Boolean=false,val loading:Boolean=false,val result:AnchorCentreRecalculationResult?=null)
const val CORRECTED_SONAR_HISTORY_ID = -1L

data class AnchorSetupDraft(
    val referenceKey:String="none",
    val referenceAlarmRadiusMeters:Double?=null,
    val referenceWaterDepthMeters:Double?=null,
    val referenceRodeMeters:Double?=null,
    val estimate:Boolean=false,
    val knownMethod:String=AnchorCenterSource.CURRENT_POSITION.name,
    val manualCoordinate:String="",
    val mapLatitude:Double?=null,
    val mapLongitude:Double?=null,
    val rangeMode:String=AnchorRangeMode.BASIC.name,
    val safetyPreset:String=AnchorSafetyPreset.BALANCED.name,
    val depthSource:String=AnchorDepthSource.MANUAL.name,
    val depth:String="",
    val rode:String="40",
    val bowHeight:String="",
    val boatLength:String="",
    val alarmRadius:String="",
    val depthGuard:Boolean=false,
    val shallowDepth:String="",
    val deepGuard:Boolean=false,
    val deepDepth:String="",
    val windGuard:Boolean=false,
    val windWarning:String="",
    val windAlarm:String="",
    val windShift:Boolean=false,
    val windShiftDegrees:String="",
)

data class MainUiState(
    val fix:NavigationFix?=null,
    val nmeaFix:NavigationFix?=null,
    val nmeaConnectionStartedElapsed:Long?=null,
    val systemFix:NavigationFix?=null,
    val connection:NmeaConnectionState=NmeaConnectionState.DISCONNECTED,
    val connectionAttempt:ConnectionAttempt=ConnectionAttempt(),
    val diagnostics:NmeaDiagnostics=NmeaDiagnostics(),
    val nmeaTransportDiagnostics:NmeaTransportDiagnostics=NmeaTransportDiagnostics(),
    val nmeaInstruments:NmeaInstrumentState=NmeaInstrumentState(),
    val settings:AppSettings=AppSettings(),
    val settingsReady:Boolean=false,
    val sessions:List<AnchorSessionEntity> = emptyList(),
    val active:AnchorSessionEntity?=null,
    val points:List<TrackPointEntity> = emptyList(),
    val follow:Boolean=true,
    val page:Int=0,
    val anchorSection:Int=0,
    val sailSection:Int=0,
    val dataSection:Int=0,
    val mockGps:MockGpsStatus=MockGpsStatus(),
    val proxyFeedback:String?=null,
    val demoGps:DemoGpsStatus=DemoGpsStatus(),
    val alarmSnapshot:AlarmSnapshot=AlarmSnapshot(),
    val rangeEditorRequested:Boolean=false,
    val eventsBySession:Map<Long,List<AlarmEventEntity>> = emptyMap(),
    val positionHealth:com.yokuli.anchorwatch.domain.model.PositionHealth=com.yokuli.anchorwatch.domain.model.PositionHealth.GPS_LOST,
    val nmeaSharing:NmeaSharingStatus=NmeaSharingStatus(),
    val localNmeaServerSettings:LocalNmeaServerSettings=LocalNmeaServerSettings(),
    val localNmeaServerRuntime:LocalNmeaServerRuntimeStatus=LocalNmeaServerRuntimeStatus(),
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
    val dismissedRuntimeFeedbackId:Long=0L,
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
    val centreRecalculation:CentreRecalculationUiState=CentreRecalculationUiState(),
    val vesselData:VesselDataSnapshot=VesselDataSnapshot(),
    val nmeaFields:List<NmeaFieldObservation> = emptyList(),
    val vesselSettings:VesselDataSettings=VesselDataSettings(),
    val outputSettings:NmeaDeviceOutputSettings=NmeaDeviceOutputSettings(),
    val phonePositionOutputStatus:PhonePositionOutputStatus=PhonePositionOutputStatus(),
    val tripSessions:List<TripSessionEntity> = emptyList(),
    val activeTrip:TripSessionEntity? = null,
    val tripDashboards:List<TripDashboard> = emptyList(),
    val phoneSensorCapabilities:PhoneSensorCapabilities=PhoneSensorCapabilities(),
    val vesselMountCalibration:VesselMountCalibration=VesselMountCalibration(),
    val phoneVesselMountState:PhoneVesselMountState=PhoneVesselMountState.UNCALIBRATED,
    val vesselCalibrationFeedback:String?=null,
    val runtimeResources:RuntimeResourceSnapshot=RuntimeResourceSnapshot(),
    val anchorSetupDraft:AnchorSetupDraft?=null,
)

private data class PositionSources(val selected:NavigationFix?,val nmea:NavigationFix?,val system:NavigationFix?,val settings:AppSettings)
private data class AvailablePositions(val nmea:NavigationFix?,val system:NavigationFix?,val demo:NavigationFix?,val demoStatus:DemoGpsStatus)
private data class PositionRoutingSnapshot(
    val positions:AvailablePositions,
    val settings:AppSettings,
    val active:AnchorSessionEntity?,
    val connection:NmeaConnectionState,
    val connectionStartedElapsed:Long?,
)
data class AnchorWatchInput(val placement:AnchorPlacementMode,val rangeMode:AnchorRangeMode,val safetyPreset:AnchorSafetyPreset,val depthMeters:Double?,val rodeMeters:Double,val bowHeightMeters:Double,val boatLengthMeters:Double?,val alarmRadiusMeters:Double,val positionSource:GpsDataSource=GpsDataSource.SYSTEM,val centerSource:AnchorCenterSource=AnchorCenterSource.CURRENT_POSITION,val usePhoneHeading:Boolean=true,val depthSource:AnchorDepthSource=AnchorDepthSource.MANUAL,val conditions:ConditionGuardConfig=ConditionGuardConfig(),val originMode:AnchorOriginMode=AnchorOriginMode.CURRENT_ACCEPTED_POSITION)

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
    private val localNmeaServerSettingsRepository:LocalNmeaServerSettingsRepository,
    private val localNmeaServerRuntime:LocalNmeaServerRuntime,
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
    private val anchorageApproachRepository:AnchorageApproachRepository,
    private val anchorageSpotRepository:com.yokuli.anchorwatch.data.anchorage.AnchorageSpotRepository,
    private val anchorageQrImageGenerator:AnchorageQrImageGenerator,
    private val vesselDataHub:VesselDataHub,
    private val nmeaFieldRepository:NmeaFieldRepository,
    private val vesselSettingsRepository:VesselSettingsRepository,
    private val outputSettingsRepository:OutputSettingsRepository,
    private val phonePositionNmeaOutputRuntime:PhonePositionNmeaOutputRuntime,
    private val tripDao:TripDao,
    private val runtimeResources:RuntimeResourceManager,
    private val vesselAttitudeRepository:PhoneVesselAttitudeRepository,
    private val vesselMountCalibrationRepository:VesselMountCalibrationRepository,
    private val nmeaManualDisconnectRepository:NmeaManualDisconnectRepository,
    private val tripExportManager:TripExportManager,
    private val tripReplayLoader:TripReplayLoader,
    private val tripDashboardRepository:TripDashboardRepository,
    private val tripReportEngine:TripReportEngine,
    private val anchorReportEngine:AnchorReportEngine,
    private val savedStateHandle:SavedStateHandle,
):AndroidViewModel(app){
    private val draftGson=Gson()
    private val restoredAnchorSetupDraft=savedStateHandle.get<String>(ANCHOR_SETUP_DRAFT_KEY)?.let{json->runCatching{draftGson.fromJson(json,AnchorSetupDraft::class.java)}.getOrNull()}
    private val _ui=MutableStateFlow(MainUiState(anchorSetupDraft=restoredAnchorSetupDraft));val ui=_ui.asStateFlow()
    private var pointsJob:Job?=null
    private var observedSessionId:Long?=null
    private var sonarSamplesJob:Job?=null
    private var observedSonarSurveyId:Long?=null
    private val anchorageNearbyTracker=AnchorageNearbyEpisodeTracker()
    private val nmeaCourseTrustGate=NmeaCourseTrustGate()
    private var selectedApproachClusterId:String?=null
    private var selectedApproachMemberIds:Set<Long> = emptySet()
    private var gisApproachTarget:AnchorageCluster?=null
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
        val positionRouting=combine(available,prefs.settings,dao.sessions(),nav.connectionState,nav.connectionStartedElapsed){positions,settings,sessions,connection,connectionStarted->
            PositionRoutingSnapshot(positions,settings,sessions.firstOrNull{it.active},connection,connectionStarted)
        }
        viewModelScope.launch{
            positionRouting.collect{routing->
                val positions=routing.positions;val settings=routing.settings;val active=routing.active
                val lockedSource=active?.positionSource?.let{runCatching{GpsDataSource.valueOf(it)}.getOrNull()}
                val source=lockedSource?:NewAnchorPositionSourcePolicy.resolve(settings.gpsDataSource,settings.demoMode)
                if(active!=null)acceptedPosition.lockSource(active.id,source)else{acceptedPosition.unlockSource(null);acceptedPosition.selectSource(source)}
                val raw=when(source){GpsDataSource.NMEA->positions.nmea;GpsDataSource.SYSTEM->positions.system;GpsDataSource.DEMO->positions.demo.takeIf{positions.demoStatus.running}}
                raw?.let{acceptedPosition.submit(source,it,nav.connectionGeneration().takeIf{source==GpsDataSource.NMEA})}
            }
        }
        viewModelScope.launch{
            positionRouting.collect{routing->
                val lockedSource=routing.active?.positionSource?.let{runCatching{GpsDataSource.valueOf(it)}.getOrNull()}
                val effectiveSource=lockedSource?:NewAnchorPositionSourcePolicy.resolve(routing.settings.gpsDataSource,routing.settings.demoMode)
                systemLocation.setAppEnabled(effectiveSource in setOf(GpsDataSource.SYSTEM,GpsDataSource.DEMO))
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
        viewModelScope.launch{nav.instruments.collect{value->_ui.update{it.copy(nmeaInstruments=value)}}}
        viewModelScope.launch{nav.transportDiagnostics.collect{value->_ui.update{it.copy(nmeaTransportDiagnostics=value)}}}
        viewModelScope.launch{vesselDataHub.snapshot.collect{value->_ui.update{it.copy(vesselData=value)}}}
        viewModelScope.launch{nmeaFieldRepository.fields.collect{value->_ui.update{it.copy(nmeaFields=value)}}}
        viewModelScope.launch{vesselSettingsRepository.settings.collect{value->_ui.update{it.copy(vesselSettings=value)}}}
        viewModelScope.launch{outputSettingsRepository.settings.collect{value->_ui.update{it.copy(outputSettings=value)}}}
        viewModelScope.launch{phonePositionNmeaOutputRuntime.status.collect{value->_ui.update{it.copy(phonePositionOutputStatus=value)}}}
        viewModelScope.launch{tripDao.sessions().collect{value->_ui.update{it.copy(tripSessions=value,activeTrip=value.firstOrNull{trip->trip.active})}}}
        viewModelScope.launch{tripDashboardRepository.decoded.collect{value->_ui.update{it.copy(tripDashboards=value.filter{dashboard->dashboard.preset==com.yokuli.anchorwatch.domain.vessel.TripInstrumentPreset.CUSTOM})}}}
        _ui.update{it.copy(phoneSensorCapabilities=vesselAttitudeRepository.capabilities)}
        viewModelScope.launch{vesselMountCalibrationRepository.calibration.collect{value->_ui.update{it.copy(vesselMountCalibration=value)}}}
        viewModelScope.launch{vesselAttitudeRepository.mountState.collect{value->_ui.update{it.copy(phoneVesselMountState=value)}}}
        viewModelScope.launch{conditionRuntime.state.collect{value->_ui.update{it.copy(conditions=value)}}}
        viewModelScope.launch{anchorageApproachRepository.anchorages.collect{value->_ui.update{it.copy(savedAnchorages=value)}}}
        viewModelScope.launch{anchorageApproachRepository.clusters.collect{clusters->
            if(selectedApproachClusterId!=null&&gisApproachTarget==null){
                val resolved=com.yokuli.anchorwatch.domain.anchorage.AnchorageClusterIdentityResolver.resolve(selectedApproachClusterId,selectedApproachMemberIds,clusters)
                selectedApproachClusterId=resolved?.id
                selectedApproachMemberIds=resolved?.savedAnchorageIds?.toSet().orEmpty()
                if(resolved==null)phoneHeadingRepository.setApproachDemand(false)
            }
            _ui.update{it.copy(anchorageClusters=clusters)}
            refreshAnchorageApproach()
        }}
        viewModelScope.launch{mockManager.status.collect{status->_ui.update{current->val defaultInactive=status.state==MockGpsState.INACTIVE&&status.message=="Android GPS is using the normal system source.";current.copy(mockGps=status,proxyFeedback=if(defaultInactive)current.proxyFeedback?:status.message else status.message)}}}
        viewModelScope.launch{alarmUi.snapshot.collect{snapshot->_ui.update{it.copy(alarmSnapshot=snapshot)}}}
        viewModelScope.launch{sharingServer.status.collect{status->_ui.update{it.copy(nmeaSharing=status)}}}
        viewModelScope.launch{localNmeaServerSettingsRepository.settings.collect{value->
            _ui.update{it.copy(localNmeaServerSettings=value)}
            // START_STICKY normally recreates the foreground service itself.
            // If the user later reopens the Activity after an OEM/force-stop
            // boundary, reclaim the still-explicit same-boot lease here while
            // a visible Activity is allowed to start the service.
            if(value.serverRequested&&!localNmeaServerRuntime.enabled)ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.REFRESH_LOCAL_NMEA_SERVER))
        }}
        viewModelScope.launch{localNmeaServerRuntime.status.collect{value->_ui.update{it.copy(localNmeaServerRuntime=value)}}}
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
        viewModelScope.launch{runtimeResources.state.collect{value->_ui.update{it.copy(runtimeResources=value)}}}
        viewModelScope.launch{incidentLogger.recent.collect{value->_ui.update{it.copy(incidents=value)}}}
        viewModelScope.launch{supportBundleManager.state.collect{value->_ui.update{it.copy(supportBundle=value)}}}
        viewModelScope.launch{offlineMapRepository.state.collect{value->_ui.update{it.copy(offlineMap=value)}}}
        viewModelScope.launch{combine(acceptedPosition.state.map{it.acceptedFix}.distinctUntilChanged(),prefs.settings.map{it.showLinzDepthReference}.distinctUntilChanged()){fix,enabled->fix to enabled}.conflate().collect{(fix,enabled)->delay(2_000);if(enabled&&fix?.valid==true)linzDepthRepository.refresh(fix.latitude,fix.longitude)}}
        viewModelScope.launch{while(true){refreshDepthUi();refreshWatchSafety();refreshAnchorageApproach();delay(1_000)}}
        viewModelScope.launch{while(true){refreshStorageHealth();delay(30_000)}}
        incidentLogger.record(
            "app",
            "UI_STARTED",
            details=mapOf(
                "versionName" to BuildConfig.VERSION_NAME,
                "versionCode" to BuildConfig.VERSION_CODE,
                "gitSha" to BuildConfig.BUILD_GIT_SHA,
                "gitBranch" to BuildConfig.BUILD_GIT_BRANCH,
                "gitDirty" to BuildConfig.BUILD_GIT_DIRTY,
                "buildTimestampUtc" to BuildConfig.BUILD_TIMESTAMP_UTC,
                "buildChannel" to BuildConfig.BUILD_CHANNEL,
                "buildInCi" to BuildConfig.BUILD_IN_CI,
                "databaseSchemaVersion" to BuildConfig.DATABASE_SCHEMA_VERSION,
            ),
        )
    }

    private companion object {
        const val UI_POSITION_FRAME_MILLIS=250L
        const val MAX_ACTIVE_TRAIL_POINTS=4_800
        const val PHONE_HEADING_ALIGNMENT_FRESH_MILLIS=2_000L
        const val NMEA_HEADING_ALIGNMENT_FRESH_MILLIS=3_000L
        const val ANCHOR_SETUP_DRAFT_KEY="anchor_setup_draft_v1"
    }

    private fun refreshWatchSafety(){
        val state=_ui.value
        val nowElapsed=android.os.SystemClock.elapsedRealtime()
        val lockedSource=state.active?.positionSource?.let{runCatching{GpsDataSource.valueOf(it)}.getOrNull()}
        val effectiveSource=lockedSource?:NewAnchorPositionSourcePolicy.resolve(state.settings.gpsDataSource,state.settings.demoMode)
        val effectiveFix=when(effectiveSource){GpsDataSource.NMEA->state.nmeaFix;GpsDataSource.SYSTEM->state.systemFix;GpsDataSource.DEMO->if(state.active==null)state.systemFix else state.fix}
        val report=WatchPreflightEvaluator.evaluate(WatchSafetyInput(
            nowElapsed=nowElapsed,nowWall=System.currentTimeMillis(),settings=state.settings.copy(gpsDataSource=effectiveSource),
            selectedFix=effectiveFix,nmeaConnection=state.connection,device=safetyProbe.snapshot(),sonar=state.sonarRecorder,nmeaConnectionStartedElapsedRealtime=state.nmeaConnectionStartedElapsed,
        ))
        _ui.update{it.copy(watchSafety=report)}
    }

    private fun refreshAnchorageApproach(nowElapsed:Long=android.os.SystemClock.elapsedRealtime()){
        val state=_ui.value
        if(state.active!=null&&selectedApproachClusterId!=null){selectedApproachClusterId=null;selectedApproachMemberIds=emptySet();gisApproachTarget=null;phoneHeadingRepository.setApproachDemand(false)}
        val accepted=state.fix?.takeIf{
            it.valid&&state.positionHealth!=com.yokuli.anchorwatch.domain.model.PositionHealth.GPS_LOST
        }
        // HDT/HDG/VHW are independent instrument sentences. They must update
        // approach guidance even when no GGA/RMC sentence arrives to create a
        // new NavigationFix.
        val physicalNmeaHeading=state.vesselData.headingTrueDegrees.takeIf{it.sourceClass==com.yokuli.anchorwatch.domain.vessel.VesselSourceClass.BOAT_NMEA&&it.value!=null}?.let{it.value!! to (it.receivedElapsedRealtime?:0L)}
        val physicalNmeaHeadingFresh=physicalNmeaHeading?.second?.let{
            nowElapsed-it in 0L..ApproachDirectionPolicy.FRESH_MILLIS
        }==true
        val trustedNmeaCourse=state.trustedNmeaCourse?.takeIf{it.isFresh(nowElapsed)}
        // Physical HDT/HDG is an instrument stream and remains usable when the
        // same server has no GPS fix. Trusted COG still has its own fresh-fix
        // and speed gates, so this does not turn stale position into heading.
        val vesselHeadingAvailable=ApproachDirectionPolicy.vesselModeAvailable(
            connection=state.connection,
            physicalHeadingFresh=physicalNmeaHeadingFresh,
            trustedCourseAvailable=trustedNmeaCourse!=null,
        )
        val headingMode=if(state.approachHeadingMode==ApproachHeadingMode.VESSEL&&!vesselHeadingAvailable)ApproachHeadingMode.PHONE else state.approachHeadingMode
        val approach=AnchorageApproachEngine.evaluate(
            clusters=availableApproachClusters(),
            selectedClusterId=selectedApproachClusterId,
            positionLatitude=accepted?.latitude,
            positionLongitude=accepted?.longitude,
        ){bearing->
            ApproachDirectionPolicy.resolve(
                nowElapsed=nowElapsed,
                targetBearingDegrees=bearing,
                nmeaTrueHeadingDegrees=physicalNmeaHeading?.first,
                nmeaHeadingReceivedElapsed=physicalNmeaHeading?.second,
                cogTrueDegrees=trustedNmeaCourse?.trueDegrees,
                sogKnots=trustedNmeaCourse?.sogKnots,
                cogReceivedElapsed=trustedNmeaCourse?.receivedElapsedRealtime,
                // Approach guidance is presentation, not persisted centre-learning
                // evidence. It must follow the live rotation vector while the phone
                // is moving; the integrity-gated value remains exclusive to the
                // accepted-position/estimator path.
                phoneTrueHeadingDegrees=state.phoneHeading.liveTrueHeadingDegrees,
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
            val recorder=state.sonarRecorder;val age=recorder.lastDepthReceivedElapsedRealtime?.let{(nowElapsed-it).coerceAtLeast(0L)};val hold=com.yokuli.anchorwatch.domain.sonar.SonarDepthHoldPolicy.evaluate(recorder.lastDepthMeters!=null,age?:Long.MAX_VALUE,recorder.depthTravelledMeters).state
            val inspection=if(state.settings.showPersonalMapReference)state.fix?.let{state.sonarGrid.inspect(it.latitude,it.longitude)}else null
            val selectedSurvey=state.selectedSonarSurveyId?.takeIf{it!=CORRECTED_SONAR_HISTORY_ID}?.let{id->state.sonarSurveys.firstOrNull{it.id==id}}
            state.copy(depthUi=DepthUiState(
                liveDepthMeters=recorder.lastDepthMeters,liveDepthReference=recorder.lastDepthReference,liveDepthAgeMillis=age,liveDepthHoldState=hold,
                correctedDepthMeters=recorder.lastDepthMeters.takeIf{recorder.lastDepthIsChartDatum},linz=state.linzDepth,
                personalMapDepthMeters=inspection?.depthMeters,personalMapMeasured=inspection?.measured,personalMapSamples=inspection?.sampleCount,personalMapUncertaintyMeters=inspection?.uncertaintyMeters,
                personalSurveyName=selectedSurvey?.name,personalSurveyStartedAt=selectedSurvey?.startedAt,
            ))
        }
    }

    fun validateProfile(profile:ConnectionProfile)=endpointPreflight.validate(profile,_ui.value.settings.nmeaSharingEnabled,_ui.value.settings.nmeaSharingPort)

    fun saveAndConnect(profile:ConnectionProfile)=viewModelScope.launch{
        if(_ui.value.connection !in setOf(NmeaConnectionState.DISCONNECTED,NmeaConnectionState.ERROR)){
            _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.WARNING,"NMEA input is already running. Stop it before replacing the RX endpoint."))}
            return@launch
        }
        endpointPreflight.validate(profile,_ui.value.settings.nmeaSharingEnabled,_ui.value.settings.nmeaSharingPort)?.let{message->_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,message))};return@launch}
        // RX and TX are separate products. An incomplete or stopped output
        // route must never block, mutate or be saved by an input connection.
        // Do not preflight with a disposable socket and then reconnect. Many
        // marine gateways allow only one reader or release the previous client
        // slowly, which made the test socket consume the stream while the real
        // App socket appeared quiet. The long-lived RX transport is the only
        // endpoint connection; traffic/fix health continues asynchronously.
        _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.TESTING,"Connecting to NMEA input…"))}
        val previous=_ui.value.settings
        nmeaManualDisconnectRepository.clear()
        nav.clearUserDisconnectLatch()
        if(!nav.connect(profile)){
            _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"The NMEA input connection could not be started. Stop the previous input generation before trying again."))}
            return@launch
        }
        val generation=nav.connectionGeneration()
        // TCP open and NMEA traffic are separate states. Persist the formal RX
        // endpoint immediately; CONNECTED_NO_DATA remains a successful live
        // connection and the normal state/health cards continue observing it.
        prefs.save(previous.copy(profile=profile))
        observeNmeaConnectionOutcome(generation)
    }

    private suspend fun observeNmeaConnectionOutcome(generation:Long){
        val outcome=withTimeoutOrNull(7_000L){
            combine(nav.connectionState,nav.transportDiagnostics){connection,diagnostics->connection to diagnostics}.first{(connection,diagnostics)->
                diagnostics.connectionGeneration>=generation&&(
                    connection in setOf(NmeaConnectionState.CONNECTED,NmeaConnectionState.CONNECTED_NO_DATA,NmeaConnectionState.CONNECTED_NO_FIX,NmeaConnectionState.STALE,NmeaConnectionState.ERROR)||
                    (connection==NmeaConnectionState.RECONNECTING&&diagnostics.lastDisconnectReason!=null)
                )
            }
        }
        when{
            outcome==null->_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.WARNING,"The RX connection is still opening. Do not tap Connect again; live transport diagnostics will keep updating."))}
            outcome.first in setOf(NmeaConnectionState.CONNECTED,NmeaConnectionState.CONNECTED_NO_DATA,NmeaConnectionState.CONNECTED_NO_FIX,NmeaConnectionState.STALE)->_ui.update{it.copy(connectionAttempt=ConnectionAttempt())}
            outcome.first==NmeaConnectionState.RECONNECTING->_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.WARNING,"${outcome.second.lastFailureCategory?:"NMEA connection failed"}: ${outcome.second.lastDisconnectReason?:"unknown transport error"}. A single protected retry is scheduled; do not reconnect repeatedly."))}
            else->_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"${outcome.second.lastFailureCategory?:"NMEA connection failed"}: ${outcome.second.lastDisconnectReason?:"unknown transport error"}"))}
        }
    }

    fun disconnect(){
        if(_ui.value.active?.paused==false&&_ui.value.active?.positionSource==GpsDataSource.NMEA.name){
            _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"This active anchor session is locked to NMEA. Pause the watch before disconnecting, or lift the anchor to end the session."))}
            return
        }
        if(runtimeResources.snapshot().needsNmeaTransport){
            val owners=runtimeResources.snapshot().nmeaOwners.joinToString{it.name.replace('_',' ')}
            _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"NMEA is still required by ${owners.ifBlank{"a background feature"}}. Review and stop those features before disconnecting."))}
            return
        }
        // The dependency review above is the safety gate. Once it passes, this
        // explicit user action must clear every stale owner latch and close RX;
        // otherwise an old background claim can make Disconnect appear broken.
        viewModelScope.launch{nmeaManualDisconnectRepository.suppress();nav.disconnectAll()}
    }
    fun reconnectNmea()=viewModelScope.launch{
        val state=_ui.value.connection
        if(state in setOf(NmeaConnectionState.CONNECTING,NmeaConnectionState.RECONNECTING))return@launch
        nmeaManualDisconnectRepository.clear()
        nav.clearUserDisconnectLatch()
        if(!nav.reconnect(_ui.value.settings.profile)){
            _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.WARNING,"Reconnect is already running or is inside the 15-second server-protection cooldown."))}
            return@launch
        }
        val generation=nav.connectionGeneration()
        _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.TESTING,"Closing the previous generation and opening one protected RX socket…"))}
        observeNmeaConnectionOutcome(generation)
    }
    fun stopActiveWatchAndDisconnect(){
        _ui.update{it.copy(connectionAttempt=ConnectionAttempt())}
        ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.STOP_WATCH_AND_DISCONNECT))
    }
    fun stopNmeaDependenciesAndDisconnect(){
        _ui.update{it.copy(connectionAttempt=ConnectionAttempt())}
        ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.STOP_NMEA_DEPENDENCIES_AND_DISCONNECT))
    }
    fun continueTripWithPhoneAndDisconnect(){
        _ui.update{it.copy(connectionAttempt=ConnectionAttempt())}
        ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.CONTINUE_TRIP_WITH_PHONE_AND_DISCONNECT))
    }
    fun clearConnectionAttempt()=_ui.update{it.copy(connectionAttempt=ConnectionAttempt())}
    fun dismissRuntimeFeedback()=_ui.update{state->state.copy(dismissedRuntimeFeedbackId=state.runtimeDiagnostics.lastUserFeedback?.id?:state.dismissedRuntimeFeedbackId)}
    fun updateSettings(settings:AppSettings){
        val current=_ui.value
        var safe=if(current.activeSonarSurvey!=null&&settings.sounderOffsetMeters!=current.settings.sounderOffsetMeters){
            viewModelScope.launch{incidentLogger.record("sonar","OFFSET_CHANGE_REJECTED_ACTIVE_SURVEY")}
            settings.copy(sounderOffsetMeters=current.settings.sounderOffsetMeters)
        }else settings
        val proxyActive=GpsSourceSafety.requiresStopAction(current.settings.mockEnabled,current.mockGps.state)
        if(proxyActive&&(safe.enhancedMock!=current.settings.enhancedMock||safe.mockHz!=current.settings.mockHz)){
            viewModelScope.launch{incidentLogger.record("gps_proxy","LIVE_CONFIGURATION_CHANGE_REJECTED")}
            safe=safe.copy(enhancedMock=current.settings.enhancedMock,mockHz=current.settings.mockHz)
        }
        if(safe.keepWifiAwake!=current.settings.keepWifiAwake)runtimeResources.updateKeepWifiAwake(safe.keepWifiAwake)
        _ui.update{it.copy(settings=safe)};viewModelScope.launch{prefs.save(safe)}
    }
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
            // Import owns files, not map visibility. A first import remains
            // unselected until the user chooses it in Map -> Layers; replacing
            // an already selected chart preserves that explicit preference.
            prefs.save(current.copy(offlineMapName=result.info.name,offlineMapAttribution=result.info.attribution))
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
    fun clearIncidentLog()=viewModelScope.launch{
        if(_ui.value.supportBundle.running){
            incidentLogger.record("storage","INCIDENT_LOG_CLEAR_REJECTED_SUPPORT_EXPORT")
            _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Wait for the support bundle export to finish before clearing its incident evidence."))}
            return@launch
        }
        storageHealthRepository.clearIncidentLog();refreshStorageHealth()
    }
    fun clearRebuildableCaches()=viewModelScope.launch{
        val state=_ui.value
        if(state.activeSonarSurvey!=null||state.backup.running||state.supportBundle.running){
            incidentLogger.record("storage","REBUILDABLE_CACHE_CLEAR_REJECTED",details=mapOf("sonarActive" to (state.activeSonarSurvey!=null),"backupRunning" to state.backup.running,"supportExportRunning" to state.supportBundle.running))
            return@launch
        }
        storageHealthRepository.clearRebuildableCaches();incidentLogger.record("storage","REBUILDABLE_CACHES_CLEARED");refreshStorageHealth()
    }
    fun refreshStorage()=refreshStorageHealth()
    fun confirmAlarmAudible(){val current=_ui.value.settings;updateSettings(current.copy(alarmAudibleConfirmedAt=System.currentTimeMillis()));incidentLogger.record("alarm","AUDIBLE_TEST_CONFIRMED")}
    fun setNmeaSharing(enabled:Boolean,port:Int){
        val safePort=port.takeIf{it in 1024..65535}
        if(safePort==null){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"NMEA Sharing port must be between 1024 and 65535."))};return}
        viewModelScope.launch{
            val current=_ui.value.localNmeaServerSettings
            localNmeaServerSettingsRepository.saveConfiguration(current.copy(port=safePort,configured=true,serverRequested=false))
            if(enabled)localNmeaServerSettingsRepository.requestStart() else localNmeaServerSettingsRepository.requestStop()
            ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.REFRESH_LOCAL_NMEA_SERVER))
        }
    }

    fun saveLocalNmeaServerConfiguration(port:Int,includePressure:Boolean,includeDerivedWind:Boolean)=viewModelScope.launch{
        if(_ui.value.localNmeaServerSettings.serverRequested){
            _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Stop the Phone NMEA service before changing its listening port or feed."))}
            return@launch
        }
        if(port !in 1024..65535){
            _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Phone NMEA service port must be between 1024 and 65535."))}
            return@launch
        }
        localNmeaServerSettingsRepository.saveConfiguration(LocalNmeaServerSettings(port,includePressure,includeDerivedWind,configured=true,serverRequested=false))
        _ui.update{it.copy(connectionAttempt=ConnectionAttempt())}
    }

    fun startLocalNmeaServer()=viewModelScope.launch{
        val state=_ui.value
        fun fail(message:String){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,message))}}
        if(!state.localNmeaServerSettings.configured||state.localNmeaServerSettings.port !in 1024..65535){
            fail("Save a valid Phone NMEA service listening port first.")
            return@launch
        }
        localNmeaServerSettingsRepository.requestStart()
        // Reflect the lease synchronously so one physical tap cannot enqueue
        // several foreground-service starts while DataStore/Flow catches up.
        _ui.update{it.copy(localNmeaServerSettings=it.localNmeaServerSettings.copy(serverRequested=true),connectionAttempt=ConnectionAttempt())}
        ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.REFRESH_LOCAL_NMEA_SERVER))
    }

    fun stopLocalNmeaServer()=viewModelScope.launch{
        localNmeaServerSettingsRepository.requestStop();_ui.update{it.copy(localNmeaServerSettings=it.localNmeaServerSettings.copy(serverRequested=false),connectionAttempt=ConnectionAttempt())}
        ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.REFRESH_LOCAL_NMEA_SERVER))
    }

    /** Emergency master stop. Product-specific Stop buttons remain independent,
     * while this action guarantees that "stop all sharing" revokes both live
     * leases before one foreground command performs both hard-stop paths. */
    fun stopAllNmeaSharing()=viewModelScope.launch{
        outputSettingsRepository.requestStop()
        localNmeaServerSettingsRepository.requestStop()
        _ui.update{it.copy(
            outputSettings=it.outputSettings.copy(publicationEnabled=false),
            localNmeaServerSettings=it.localNmeaServerSettings.copy(serverRequested=false),
            connectionAttempt=ConnectionAttempt(),
        )}
        ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.STOP_ALL_NMEA_SHARING))
    }
    fun deleteHistorySession(session:AnchorSessionEntity){if(session.active)return;viewModelScope.launch{dao.deleteCompletedSession(session.id)}}
    fun setMapType(mapType:Int){val value=mapType.takeIf{it in 1..3}?:1;val updated=_ui.value.settings.copy(mapType=value);_ui.update{it.copy(settings=updated)};viewModelScope.launch{prefs.save(updated)}}
    fun setGpsDataSource(source:GpsDataSource)=switchGpsDataSource(source)
    fun switchGpsDataSource(source:GpsDataSource)=viewModelScope.launch{
        // Source consent changes only on this explicit user action. Connection
        // and accepted-fix collectors are observational and never persist a
        // different GPS source behind the user's back.
        val current=_ui.value.settings
        if(current.demoMode){
            if(source!=GpsDataSource.DEMO)_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Demo mode owns the App GPS source. Lift the current anchor and disable Demo mode before choosing System or NMEA."))}
            return@launch
        }
        val activeSession=_ui.value.active
        val lockedSource=activeSession?.positionSource?.let{runCatching{GpsDataSource.valueOf(it)}.getOrNull()}
        if(!GpsSourceSafety.allowsSessionSource(activeSession!=null,activeSession?.paused==true,lockedSource,source)){
            activeSession?.let{dao.insertEvent(AlarmEventEntity(sessionId=it.id,timestamp=System.currentTimeMillis(),type="WATCH_GPS_SOURCE_CHANGE_REJECTED_ACTIVE_SESSION",detail="LOCKED=${it.positionSource};REQUESTED=${source.name}"))}
            _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Pause the active anchor watch before changing between System and NMEA GPS. Demo sessions remain locked until Lift anchor."))}
            return@launch
        }
        if(activeSession!=null&&lockedSource!=source){
            if(source==GpsDataSource.NMEA){
                val availability=NmeaSourceSelectionPolicy.availability(_ui.value.connection,_ui.value.nmeaFix,_ui.value.nmeaConnectionStartedElapsed,android.os.SystemClock.elapsedRealtime(),current.gpsLossSeconds*1000L)
                if(availability!=NmeaSourceAvailability.AVAILABLE){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Connect the NMEA source and wait for a fresh valid position before selecting NMEA GPS."))};return@launch}
                if(!NmeaSourceSelectionPolicy.isUsablePosition(_ui.value.connection,_ui.value.nmeaFix,_ui.value.nmeaConnectionStartedElapsed,android.os.SystemClock.elapsedRealtime(),current.gpsLossSeconds*1000L)){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Wait for NMEA fix quality or HDOP to recover before selecting NMEA GPS."))};return@launch}
            }
            if(source==GpsDataSource.SYSTEM&&GpsSourceSafety.blocksSystemGps(current.mockEnabled,mockManager.status.value.state)){
                _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Disable the global NMEA GPS proxy first. While Android mock mode is active, System GPS is not an independent source."))};return@launch
            }
            _ui.update{it.copy(connectionAttempt=ConnectionAttempt())}
            ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.SWITCH_WATCH_GPS_SOURCE).putExtra("source",source.name))
            return@launch
        }
        if(source==current.gpsDataSource)return@launch
        if(source==GpsDataSource.NMEA){
            val availability=NmeaSourceSelectionPolicy.availability(_ui.value.connection,_ui.value.nmeaFix,_ui.value.nmeaConnectionStartedElapsed,android.os.SystemClock.elapsedRealtime(),current.gpsLossSeconds*1000L)
            if(availability!=NmeaSourceAvailability.AVAILABLE){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Connect the NMEA source and wait for a fresh valid position before selecting NMEA GPS."))};return@launch}
            if(!NmeaSourceSelectionPolicy.isUsablePosition(_ui.value.connection,_ui.value.nmeaFix,_ui.value.nmeaConnectionStartedElapsed,android.os.SystemClock.elapsedRealtime(),current.gpsLossSeconds*1000L)){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Wait for NMEA fix quality or HDOP to recover before selecting NMEA GPS."))};return@launch}
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
        if(_ui.value.activeTrip!=null){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"End the current Trip Watch session before changing Demo mode."))};return@launch}
        if(_ui.value.activeSonarSurvey!=null){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Stop and save the current sonar survey before changing Demo mode."))};return@launch}
        if(enabled&&GpsSourceSafety.blocksSystemGps(current.mockEnabled,mockManager.status.value.state)){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Disable the global NMEA GPS proxy before enabling Demo mode. Demo needs an independent System GPS origin."))};return@launch}
        demoLocation.stop()
        prefs.save(current.copy(demoMode=enabled,gpsDataSource=if(enabled)GpsDataSource.DEMO else GpsDataSource.SYSTEM,mockEnabled=false))
        _ui.update{it.copy(connectionAttempt=ConnectionAttempt())}
    }
    fun updateVesselDataSettings(value:VesselDataSettings)=viewModelScope.launch{vesselSettingsRepository.save(value)}
    fun createTripDashboard(title:String)=viewModelScope.launch{tripDashboardRepository.create(title)}
    fun saveTripDashboard(value:TripDashboard)=viewModelScope.launch{tripDashboardRepository.save(value)}
    fun deleteTripDashboard(id:String)=viewModelScope.launch{tripDashboardRepository.delete(id)}
    fun reorderTripDashboards(ids:List<String>)=viewModelScope.launch{tripDashboardRepository.reorder(ids)}
    fun setTripLiveDisplayActive(active:Boolean){runtimeResources.set(RuntimeOwner.VESSEL_HUB_UI,if(active)RuntimeRequirement(needsSystemLocation=true,needsPhoneMotion=true,needsPhoneHeading=true,needsPhonePressure=true)else null)}
    fun confirmTripAttitudeFrame(axis:DeviceBowAxis)=viewModelScope.launch{
        if(_ui.value.activeTrip?.paused==true){_ui.update{it.copy(vesselCalibrationFeedback="Resume the trip before confirming a new attitude segment.")};return@launch}
        runtimeResources.set(RuntimeOwner.VESSEL_HUB_UI,RuntimeRequirement(needsSystemLocation=true,needsPhoneMotion=true,needsPhoneHeading=true,needsPhonePressure=true))
        delay(500)
        val saved=vesselAttitudeRepository.calibrate(axis)
        if(saved){
            vesselAttitudeRepository.setMounted(true)
            _ui.update{it.copy(vesselCalibrationFeedback="Trip attitude frame confirmed.")}
            if(_ui.value.activeTrip!=null)ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.CONFIRM_TRIP_ATTITUDE_FRAME))
        }else _ui.update{it.copy(vesselCalibrationFeedback="No rotation-vector sample is available on this phone.")}
        // TripWatchPage owns the preview lease while visible; a running Trip
        // takes over through its own runtime owner. Do not tear down the shared
        // sensor preview between confirmation and the Start command.
    }
    /** Source-compatible entry point retained for old UI/tests. */
    fun calibrateVesselMount(axis:DeviceBowAxis)=confirmTripAttitudeFrame(axis)
    fun setPhoneVesselMounted(mounted:Boolean)=viewModelScope.launch{
        if(mounted&&_ui.value.vesselMountCalibration.calibratedAt<=0L){
            _ui.update{it.copy(vesselCalibrationFeedback="Confirm the phone-to-vessel attitude frame during Trip Watch first.")}
            return@launch
        }
        if(mounted&&!_ui.value.vesselMountCalibration.attitudeFrameConfirmed){
            _ui.update{it.copy(vesselCalibrationFeedback="The previous attitude segment was invalidated. Place the phone flat relative to the vessel and confirm a new segment.")}
            return@launch
        }
        vesselAttitudeRepository.setMounted(mounted)
        _ui.update{it.copy(vesselCalibrationFeedback=if(mounted)"Trip attitude capture resumed." else "Trip attitude capture paused. Heading, GPS and pressure continue.")}
    }
    fun alignPhoneHeadingToBow()=viewModelScope.launch{
        val state=_ui.value
        val now=android.os.SystemClock.elapsedRealtime()
        val phoneFresh=state.phoneHeading.receivedElapsedRealtime?.let{now-it in 0L..PHONE_HEADING_ALIGNMENT_FRESH_MILLIS}==true
        val phoneAvailable=state.phoneHeading.liveTrueHeadingDegrees!=null||state.phoneHeading.liveMagneticHeadingDegrees!=null
        if(!phoneFresh||!phoneAvailable){
            _ui.update{it.copy(vesselCalibrationFeedback=com.yokuli.anchorwatch.localization.localized(it.settings.appLanguage,"No fresh phone compass reading is available. Keep this page open, move away from magnetic interference, then try again.","当前没有新鲜的手机罗盘数据。请保持此页打开、远离磁场干扰后再试。"))}
            return@launch
        }
        vesselAttitudeRepository.alignHeading(0.0)
        _ui.update{it.copy(vesselCalibrationFeedback=com.yokuli.anchorwatch.localization.localized(it.settings.appLanguage,"Aligned now. The top of the phone is the vessel bow direction; you can repeat this at any time.","已重新对齐。现在手机顶部方向就是船艏方向；以后可随时再次操作。"))}
    }
    fun alignPhoneHeadingToNmea()=viewModelScope.launch{
        val state=_ui.value
        val now=android.os.SystemClock.elapsedRealtime()
        val phoneFresh=state.phoneHeading.receivedElapsedRealtime?.let{now-it in 0L..PHONE_HEADING_ALIGNMENT_FRESH_MILLIS}==true
        val nmeaTrue=state.nmeaInstruments.headingTrue?.takeIf{now-it.second in 0L..NMEA_HEADING_ALIGNMENT_FRESH_MILLIS}?.first
        val nmeaMagnetic=state.nmeaInstruments.headingMagnetic?.takeIf{now-it.second in 0L..NMEA_HEADING_ALIGNMENT_FRESH_MILLIS}?.first
        val match=if(phoneFresh)PhoneHeadingAlignmentPolicy.matchLiveReference(
            phoneTrueDegrees=state.phoneHeading.liveTrueHeadingDegrees,
            phoneMagneticDegrees=state.phoneHeading.liveMagneticHeadingDegrees,
            vesselTrueDegrees=nmeaTrue,
            vesselMagneticDegrees=nmeaMagnetic,
        )else null
        if(match==null){
            _ui.update{it.copy(vesselCalibrationFeedback=com.yokuli.anchorwatch.localization.localized(it.settings.appLanguage,"A fresh Phone and NMEA heading with the same north reference is required. Nothing was changed.","需要同时取得采用相同北向基准的新鲜手机艏向与 NMEA 艏向；本次没有修改。"))}
            return@launch
        }
        vesselAttitudeRepository.alignHeading(match.offsetDegrees)
        val reference=if(match.reference==com.yokuli.anchorwatch.location.vessel.PhoneHeadingAlignmentReference.TRUE_NORTH)"true" else "magnetic"
        _ui.update{it.copy(vesselCalibrationFeedback=com.yokuli.anchorwatch.localization.localized(it.settings.appLanguage,"Aligned to the live NMEA $reference heading. You can realign again at any time.","已按实时 NMEA ${if(reference=="true")"真北" else "磁北"}艏向重新对齐；以后可随时再次操作。"))}
    }
    /** Source-compatible entry point for older call sites and backup tooling. */
    fun setPhoneHeadingAlignment(offsetDegrees:Double)=viewModelScope.launch{vesselAttitudeRepository.alignHeading(offsetDegrees)}
    fun clearVesselCalibrationFeedback()=_ui.update{it.copy(vesselCalibrationFeedback=null)}
    fun setNmeaOutputEndpoint(mode:NmeaOutputTransportMode,host:String,port:Int)=viewModelScope.launch{
        val current=_ui.value.outputSettings
        val input=_ui.value.settings.profile
        if(current.publicationEnabled){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Stop NMEA output before changing its destination."))};return@launch}
        if(mode==NmeaOutputTransportMode.TCP_SERVER){
            _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Use Phone NMEA service to host a TCP server. Phone/App boat output only writes locally-owned data into the boat network."))}
            return@launch
        }
        if(mode!=NmeaOutputTransportMode.TCP_SERVER&&(host.isBlank()||port !in 1..65535)){
            _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"This NMEA output destination needs a valid host and port from 1 to 65535."))}
            return@launch
        }
        val proposed=when(mode){
            NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->NmeaOutputEndpointPolicy.tcpDestination(current,input,input.host,input.port)
            NmeaOutputTransportMode.DEDICATED_TCP->NmeaOutputEndpointPolicy.tcpDestination(current,input,host,port)
            NmeaOutputTransportMode.TCP_SERVER->current
            NmeaOutputTransportMode.UDP_UNICAST,NmeaOutputTransportMode.UDP_BROADCAST->current.copy(transportMode=mode,outputHost=host.trim(),outputPort=port)
        }
        outputSettingsRepository.saveConfiguration(proposed.copy(transportConfigured=true,publicationEnabled=false))
        _ui.update{it.copy(connectionAttempt=ConnectionAttempt())}
    }
    fun setNmeaPhonePositionPublishing(enabled:Boolean)=viewModelScope.launch{
        updateNmeaOutputStreams{current->current.copy(
            phonePositionEnabled=enabled,
            positionPolicy=if(enabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF,
        )}
    }
    fun setNmeaPhoneHeadingPublishing(enabled:Boolean)=viewModelScope.launch{
        updateNmeaOutputStreams{it.copy(phoneHeadingEnabled=enabled,headingPolicy=if(enabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF)}
    }
    fun setNmeaPhoneRateOfTurnPublishing(enabled:Boolean)=viewModelScope.launch{
        updateNmeaOutputStreams{it.copy(phoneMotionEnabled=enabled||it.phoneAttitudeEnabled,phoneRateOfTurnEnabled=enabled,rateOfTurnPolicy=if(enabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF,motionPolicy=if(enabled||it.phoneAttitudeEnabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF)}
    }
    fun setNmeaPhoneAttitudePublishing(enabled:Boolean)=viewModelScope.launch{
        updateNmeaOutputStreams{it.copy(phoneMotionEnabled=enabled||it.phoneRateOfTurnEnabled,phoneAttitudeEnabled=enabled,attitudePolicy=if(enabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF,motionPolicy=if(enabled||it.phoneRateOfTurnEnabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF)}
    }
    fun setNmeaPhonePressurePublishing(enabled:Boolean)=viewModelScope.launch{
        updateNmeaOutputStreams{it.copy(phonePressureEnabled=enabled,includePressure=enabled,pressurePolicy=if(enabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF)}
    }
    fun setNmeaDerivedWindPublishing(enabled:Boolean)=viewModelScope.launch{
        updateNmeaOutputStreams{it.copy(includeDerivedWind=enabled,derivedWindPolicy=if(enabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF)}
    }
    fun setNmeaOutputPreset(preset:NmeaOutputPreset)=viewModelScope.launch{
        updateNmeaOutputStreams{it.withPreset(preset)}
    }
    private suspend fun updateNmeaOutputStreams(transform:(NmeaDeviceOutputSettings)->NmeaDeviceOutputSettings){
        val current=_ui.value.outputSettings
        val updated=transform(current)
        outputSettingsRepository.saveConfiguration(updated)
        _ui.update{it.copy(outputSettings=updated.copy(publicationEnabled=current.publicationEnabled),connectionAttempt=ConnectionAttempt())}
        // A running publisher observes the same repository Flow, but an
        // explicit refresh also serializes this user action through the
        // foreground command actor before any subsequent Start/Stop action.
        if(current.publicationEnabled)ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.REFRESH_PHONE_SENSOR_OUTPUT))
    }
    fun startNmeaOutput()=viewModelScope.launch{
        val state=_ui.value
        // Destination changes only transport ownership. Every route publishes
        // the same Phone/App-owned feed and can never republish Boat input.
        val value=NmeaOutputEndpointPolicy.automatic(state.outputSettings,state.settings.profile).copy(
            purpose=NmeaOutputPurpose.BOAT_BUS_INJECTION,
            autoStartOutput=false,
        )
        fun fail(message:String){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,message))}}
        if(!isOutputDestinationReady(value,state)){fail(outputDestinationError(value));return@launch}
        if(value!=state.outputSettings)outputSettingsRepository.saveConfiguration(value.copy(publicationEnabled=false))
        outputSettingsRepository.requestStart()
        _ui.update{it.copy(outputSettings=it.outputSettings.copy(publicationEnabled=true),connectionAttempt=ConnectionAttempt())}
        ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.REFRESH_PHONE_SENSOR_OUTPUT))
    }
    fun stopNmeaOutput()=viewModelScope.launch{
        outputSettingsRepository.requestStop();_ui.update{it.copy(outputSettings=it.outputSettings.copy(publicationEnabled=false),connectionAttempt=ConnectionAttempt())}
        // The foreground coordinator is the only production lifecycle owner.
        // Its command actor performs the hard stop and invalidates queued bytes.
        ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.REFRESH_PHONE_SENSOR_OUTPUT))
    }
    private fun isOutputDestinationReady(value:NmeaDeviceOutputSettings,state:MainUiState):Boolean{
        val effective=NmeaOutputEndpointPolicy.automatic(value,state.settings.profile)
        return effective.anyStreamSelected&&effective.transportConfigured&&when(effective.transportMode){
            NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->false
            NmeaOutputTransportMode.TCP_SERVER->false
            NmeaOutputTransportMode.DEDICATED_TCP->effective.outputHost.isNotBlank()&&effective.outputPort in 1..65535&&!NmeaOutputEndpointPolicy.opensSecondTransportOnInputEndpoint(effective,state.settings.profile)
            NmeaOutputTransportMode.UDP_UNICAST,NmeaOutputTransportMode.UDP_BROADCAST->effective.outputHost.isNotBlank()&&effective.outputPort in 1..65535
        }
    }
    private fun outputDestinationError(value:NmeaDeviceOutputSettings):String{
        val effective=NmeaOutputEndpointPolicy.automatic(value,_ui.value.settings.profile)
        return if(!effective.anyStreamSelected)"Select at least one Phone/App output stream before starting." else if(!effective.transportConfigured)"Choose an NMEA output destination before enabling a stream." else when(effective.transportMode){
            NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->"Same-socket output is disabled because an optional blocked write could stop the safety-owned NMEA input. Choose a separate TX port or UDP destination."
            NmeaOutputTransportMode.DEDICATED_TCP->if(NmeaOutputEndpointPolicy.opensSecondTransportOnInputEndpoint(effective,_ui.value.settings.profile))"Use a separate TX port. Same-socket output is disabled so an optional output stall can never close the safety-owned input." else "Enter a valid TCP output host and port first."
            NmeaOutputTransportMode.TCP_SERVER->"Use the separate Phone NMEA service page to host a TCP listener."
            NmeaOutputTransportMode.UDP_UNICAST,NmeaOutputTransportMode.UDP_BROADCAST->"Enter a valid UDP output host and port first."
        }
    }
    fun testNmeaDeviceOutput(result:(Boolean)->Unit)=viewModelScope.launch{
        val state=_ui.value
        val profile=state.settings.profile;val settings=NmeaOutputEndpointPolicy.automatic(state.outputSettings,profile)
        if(settings.transportMode==NmeaOutputTransportMode.TCP_SERVER){
            _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"A phone-hosted listener belongs to Phone NMEA service, not Phone/App boat output."))};result(false);return@launch
        }
        val success=withContext(Dispatchers.IO){phonePositionNmeaOutputRuntime.testOutput(settings,profile)}
        result(success)
    }
    fun testKnownGoodHdgOutput(result:(Boolean)->Unit)=viewModelScope.launch{
        val state=_ui.value
        val profile=state.settings.profile;val settings=NmeaOutputEndpointPolicy.automatic(state.outputSettings,profile)
        if(settings.transportMode==NmeaOutputTransportMode.TCP_SERVER){result(false);return@launch}
        val success=withContext(Dispatchers.IO){phonePositionNmeaOutputRuntime.testKnownGoodHdg(settings,profile)}
        result(success)
    }
    fun updateDemoConfiguration(scenario:com.yokuli.anchorwatch.domain.model.DemoScenario?=null,speed:Int?=null)=viewModelScope.launch{
        val current=_ui.value.settings
        if(_ui.value.active!=null){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Lift the current anchor session before changing the Demo trajectory."))};return@launch}
        if(_ui.value.activeTrip!=null){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"End the current Trip Watch session before changing the Demo trajectory."))};return@launch}
        if(_ui.value.activeSonarSurvey!=null){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Stop and save the sonar survey before changing the Demo trajectory."))};return@launch}
        if(!current.demoMode)return@launch
        prefs.save(current.copy(demoScenario=scenario?:current.demoScenario,demoSpeedMultiplier=speed?:current.demoSpeedMultiplier))
    }
    fun onPermissionsChanged(){systemLocation.refreshPermission()}
    fun setAnchorSetupGpsPreview(enabled:Boolean)=systemLocation.setPreviewEnabled(enabled)
    fun saveAnchorSetupDraft(value:AnchorSetupDraft){
        savedStateHandle[ANCHOR_SETUP_DRAFT_KEY]=draftGson.toJson(value)
        _ui.update{it.copy(anchorSetupDraft=value)}
    }
    fun clearAnchorSetupDraft(){
        savedStateHandle.remove<String>(ANCHOR_SETUP_DRAFT_KEY)
        _ui.update{it.copy(anchorSetupDraft=null)}
    }

    fun clearDiagnostics()=nav.clearDiagnostics()
    fun arm(lat:Double,lon:Double,input:AnchorWatchInput){
        val intent=Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.ARM)
            .putExtra("lat",lat).putExtra("lon",lon).putExtra("rode",input.rodeMeters).putExtra("depth",input.depthMeters?:Double.NaN).putExtra("bowHeight",input.bowHeightMeters).putExtra("boatLength",input.boatLengthMeters?:Double.NaN)
            .putExtra("antennaOffset",if(input.positionSource==GpsDataSource.NMEA)_ui.value.settings.nmeaGpsAntennaToBowMeters else 0.0)
            .putExtra("warning",maxOf(input.alarmRadiusMeters*.8,input.alarmRadiusMeters-10).coerceAtMost(input.alarmRadiusMeters-.1)).putExtra("alarm",input.alarmRadiusMeters).putExtra("placement",input.placement.name).putExtra("rangeMode",input.rangeMode.name).putExtra("safetyPreset",input.safetyPreset.name).putExtra("positionSource",input.positionSource.name).putExtra("centerSource",input.centerSource.name).putExtra("usePhoneHeading",true)
            .putExtra("depthSource",input.depthSource.name)
            .putExtra("originMode",input.originMode.name)
            .putExtra("depthGuard",input.conditions.depthGuardEnabled).putExtra("shallowDepth",input.conditions.shallowDepthAlarmMeters?:Double.NaN).putExtra("deepDepth",input.conditions.deepDepthAlarmMeters?:Double.NaN).putExtra("windGuard",input.conditions.windGuardEnabled).putExtra("windWarning",input.conditions.windWarningKnots?:Double.NaN).putExtra("windAlarm",input.conditions.windAlarmKnots?:Double.NaN).putExtra("windShift",input.conditions.windShiftEnabled).putExtra("windShiftDegrees",input.conditions.windShiftThresholdDegrees?:Double.NaN).putExtra("apparentFallback",input.conditions.windAllowApparentFallback)
        ContextCompat.startForegroundService(app,intent)
    }
    fun updateAnchorSettings(input:AnchorWatchInput){val intent=Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.UPDATE_RADIUS).putExtra("alarm",input.alarmRadiusMeters);ContextCompat.startForegroundService(app,intent)}
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
    fun resetCentreAnalysis(session:AnchorSessionEntity)=ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.RESET_CENTRE_ANALYSIS).putExtra("sessionId",session.id))
    fun recalculateCentreFromTrack(session:AnchorSessionEntity)=viewModelScope.launch{
        _ui.update{it.copy(centreRecalculation=CentreRecalculationUiState(session.id,session.active,loading=true))}
        dao.insertEvent(AlarmEventEntity(sessionId=session.id,timestamp=System.currentTimeMillis(),type="ANCHOR_CENTRE_RECALCULATION_REQUESTED"))
        val points=withContext(Dispatchers.IO){dao.points(session.id).first()}
        val result=withContext(Dispatchers.Default){AnchorCentreRecalculator.analyze(session,points)}
        val eventType=if(result.status==AnchorCentreRecalculationStatus.READY)"ANCHOR_CENTRE_RECALCULATION_READY" else "ANCHOR_CENTRE_RECALCULATION_INSUFFICIENT"
        val candidate=result.candidate
        dao.insertEvent(AlarmEventEntity(sessionId=session.id,timestamp=System.currentTimeMillis(),type=eventType,detail="status=${result.status};oldLat=${session.anchorLatitude};oldLon=${session.anchorLongitude};newLat=${candidate?.latitude};newLon=${candidate?.longitude};shiftMeters=${result.shiftMeters};uncertainty=${candidate?.uncertaintyRadiusMeters};trackDiameter=${candidate?.trackDiameterMeters};fitRadius=${candidate?.fittedRadiusMeters};radialObservable=${candidate?.radialObservable};reason=${candidate?.observabilityReason}"))
        _ui.update{it.copy(centreRecalculation=CentreRecalculationUiState(session.id,session.active,result=result))}
    }
    fun dismissCentreRecalculation()=_ui.update{it.copy(centreRecalculation=CentreRecalculationUiState())}
    fun keepCurrentRecalculatedCentre(){val value=_ui.value.centreRecalculation;val id=value.sessionId?:return;viewModelScope.launch{dao.insertEvent(AlarmEventEntity(sessionId=id,timestamp=System.currentTimeMillis(),type="ANCHOR_CENTRE_RECALCULATION_REJECTED",detail="USER_KEPT_CURRENT"))};dismissCentreRecalculation()}
    fun applyRecalculatedCentre(){val value=_ui.value.centreRecalculation;val result=value.result?:return;val candidate=result.candidate?:return;if(!value.sessionActive||result.status!=AnchorCentreRecalculationStatus.READY)return;ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.APPLY_RECALCULATED_CENTRE).putExtra("sessionId",value.sessionId?:-1L).putExtra("expectedCurrentLatitude",result.currentLatitude).putExtra("expectedCurrentLongitude",result.currentLongitude).putExtra("latitude",candidate.latitude).putExtra("longitude",candidate.longitude).putExtra("uncertainty",candidate.uncertaintyRadiusMeters).putExtra("trackDiameter",candidate.trackDiameterMeters).putExtra("fitRadius",candidate.fittedRadiusMeters?:Double.NaN).putExtra("shift",result.shiftMeters?:Double.NaN));dismissCentreRecalculation()}
    fun saveRecalculatedCentreAsAnchorage(){val value=_ui.value.centreRecalculation;val result=value.result?:return;val candidate=result.candidate?:return;val session=_ui.value.sessions.firstOrNull{it.id==value.sessionId}?:return;viewModelScope.launch{val now=System.currentTimeMillis();try{anchorageApproachRepository.save(SavedAnchorageEntity(name="${if(_ui.value.settings.appLanguage.usesChinese())"轨迹估算" else "Track estimate"} · ${java.text.DateFormat.getDateInstance().format(java.util.Date(session.startedAt))}",latitude=candidate.latitude,longitude=candidate.longitude,createdAt=now,updatedAt=now,preferredAlarmRadiusMeters=session.alarmRadiusMeters,typicalWaterDepthMeters=session.waterDepthMeters?:session.minObservedDepthMeters,typicalRodeLengthMeters=session.rodeLengthMeters,sourceSessionId=session.id,coordinateSource=com.yokuli.anchorwatch.data.anchorage.AnchorageCoordinateSource.ESTIMATED_REGION_CENTRE.name,coordinateUncertaintyMeters=candidate.uncertaintyRadiusMeters));dismissCentreRecalculation()}catch(cancelled:CancellationException){throw cancelled}catch(duplicate:DuplicateAnchorageException){_ui.update{it.copy(centreRecalculation=CentreRecalculationUiState(),anchorageDuplicateExisting=duplicate.existing)}}catch(error:Throwable){_ui.update{it.copy(centreRecalculation=CentreRecalculationUiState(),anchorageOperationError="Could not save the recalculated anchorage. No data was changed.")}}}}
    fun testAlarm()=ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.TEST_ALARM))
    fun stopAlarmTest()=app.startService(Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.STOP_ALARM_TEST))
    fun startSonarSurvey(name:String,tideMode:TideMode,manualTideOffsetMeters:Double,tideStationId:String?=null){
        val intent=Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.START_SONAR_SURVEY).putExtra("name",name).putExtra("tideMode",tideMode.name).putExtra("manualTideOffset",manualTideOffsetMeters).putExtra("tideStationId",tideStationId)
        ContextCompat.startForegroundService(app,intent)
    }
    fun stopSonarSurvey()=app.startService(Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.STOP_SONAR_SURVEY))
    fun startTrip(name:String,phoneMotionEnabled:Boolean,positionPreference:VesselSourcePreference=_ui.value.vesselSettings.positionPreference)=viewModelScope.launch{
        val safePreference=positionPreference.takeUnless{it==VesselSourcePreference.DERIVED}?:VesselSourcePreference.AUTO
        ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.START_TRIP).putExtra("name",name).putExtra("phoneMotionEnabled",phoneMotionEnabled).putExtra("positionPreference",safePreference.name))
    }
    fun pauseTrip()=app.startService(Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.PAUSE_TRIP))
    fun resumeTrip()=ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.RESUME_TRIP))
    fun pauseTripAttitude()=viewModelScope.launch{
        vesselAttitudeRepository.setMounted(false)
        app.startService(Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.PAUSE_TRIP_ATTITUDE))
        _ui.update{it.copy(vesselCalibrationFeedback="Trip attitude capture paused. Heading, GPS and pressure continue.")}
    }
    fun endTrip()=app.startService(Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.END_TRIP))
    fun markTripWaypoint(name:String,note:String,type:String)=ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.MARK_TRIP_WAYPOINT).putExtra("name",name).putExtra("note",note).putExtra("type",type))
    fun deleteTrip(session:TripSessionEntity){if(session.active)return;viewModelScope.launch{tripDao.deleteCompleted(session.id)}}
    suspend fun tripReport(sessionId:Long):TripReport?=tripReportEngine.generate(sessionId)
    suspend fun anchorReport(sessionId:Long):AnchorReport?=anchorReportEngine.generate(sessionId)
    suspend fun tripReplay(sessionId:Long):TripReplayData=tripReplayLoader.load(sessionId)
    fun exportTripCsv(session:TripSessionEntity)=viewModelScope.launch{runCatching{tripExportManager.csv(session)}.onSuccess{shareExport(it,"text/csv")}.onFailure(::exportFailed)}
    fun exportTripGpx(session:TripSessionEntity)=viewModelScope.launch{runCatching{tripExportManager.gpx(session)}.onSuccess{shareExport(it,"application/gpx+xml")}.onFailure(::exportFailed)}
    fun exportTripKml(session:TripSessionEntity)=viewModelScope.launch{runCatching{tripExportManager.kml(session)}.onSuccess{shareExport(it,"application/vnd.google-earth.kml+xml")}.onFailure(::exportFailed)}
    fun exportTripKmz(session:TripSessionEntity)=viewModelScope.launch{runCatching{tripExportManager.kmz(session)}.onSuccess{shareExport(it,"application/vnd.google-earth.kmz")}.onFailure(::exportFailed)}
    fun exportTripEvents(session:TripSessionEntity)=viewModelScope.launch{runCatching{tripExportManager.eventsCsv(session)}.onSuccess{shareExport(it,"text/csv")}.onFailure(::exportFailed)}
    fun exportTripWaypoints(session:TripSessionEntity)=viewModelScope.launch{runCatching{tripExportManager.waypointsCsv(session)}.onSuccess{shareExport(it,"text/csv")}.onFailure(::exportFailed)}
    fun exportTripCustomMetrics(session:TripSessionEntity)=viewModelScope.launch{runCatching{tripExportManager.customMetricsCsv(session)}.onSuccess{shareExport(it,"text/csv")}.onFailure(::exportFailed)}
    fun shareTripLiveSnapshot()=viewModelScope.launch{runCatching{tripExportManager.liveSnapshot(_ui.value.vesselData)}.onSuccess{shareExport(it,"image/png")}.onFailure(::exportFailed)}
    fun shareTripReportSnapshot(session:TripSessionEntity)=viewModelScope.launch{runCatching{tripExportManager.reportSnapshot(session)}.onSuccess{shareExport(it,"image/png")}.onFailure(::exportFailed)}
    fun exportTripAiSource(session:TripSessionEntity)=viewModelScope.launch{runCatching{tripExportManager.aiZip(session)}.onSuccess{shareExport(it,"application/zip")}.onFailure(::exportFailed)}
    fun exportAnchorAiSource(session:AnchorSessionEntity)=viewModelScope.launch{runCatching{tripExportManager.anchorAiZip(session)}.onSuccess{shareExport(it,"application/zip")}.onFailure(::exportFailed)}
    fun renameSonarSurvey(surveyId:Long,name:String)=viewModelScope.launch{sonarRecorder.rename(surveyId,name)}
    fun deleteSonarSurvey(surveyId:Long)=viewModelScope.launch{sonarRecorder.delete(surveyId)}
    fun rebuildSonarSurvey(surveyId:Long)=viewModelScope.launch{sonarRecorder.rebuild(surveyId)}
    fun selectSonarSurvey(surveyId:Long)=observeSonarSamples(surveyId)
    fun selectCorrectedSonarHistory()=observeSonarSamples(CORRECTED_SONAR_HISTORY_ID)
    fun exportSonarCsv(survey:SonarSurveyEntity)=viewModelScope.launch{
        val file=withContext(Dispatchers.IO){java.io.File(app.cacheDir,"sonar-${survey.id}.csv").also{target->target.bufferedWriter().use{writer->
            writer.appendLine("timestamp,latitude,longitude,raw_depth_m,measured_depth_m,normalized_depth_m,reference,sentence,nmea_offset_m,gps_source,position_provider,position_accuracy_m,hdop,sog_knots,position_age_ms,fix_trust,disposition,usable,position_correction,base_grid_x,base_grid_y,survey_id,tide_mode,tide_height_m,tide_station_id,tide_station_distance_m,tide_year,tide_method,tide_source,tide_source_updated_at,tide_status,depth_held,depth_age_ms,depth_source_elapsed_realtime")
            var afterTimestamp=Long.MIN_VALUE;var afterId=Long.MIN_VALUE
            while(true){val page=sonarDao.samplesPage(survey.id,afterTimestamp,afterId,1_000);if(page.isEmpty())break;page.forEach{sample->writer.appendLine("${sample.timestamp},${sample.latitude},${sample.longitude},${sample.rawDepthMeters},${sample.measuredDepthMeters},${sample.normalizedDepthMeters?:""},${sample.depthReference},${sample.sentenceType},${sample.nmeaOffsetMeters?:""},${sample.gpsSource},${sample.positionProvider},${sample.horizontalAccuracyMeters?:""},${sample.hdop?:""},${sample.sogKnots?:""},${sample.positionAgeMillis},${sample.fixTrust},${sample.disposition},${sample.usable},${sample.positionCorrectionMethod},${sample.baseGridX},${sample.baseGridY},${survey.id},${sample.tideCorrectionMode},${sample.tideHeightMetersApplied?:""},${sample.tideStationId?:""},${sample.tideStationDistanceMeters?:""},${sample.tidePredictionYear?:""},${sample.tideCorrectionMethod?:""},${sample.tideSource?:""},${sample.tideSourceUpdatedAt?:""},${sample.tideCorrectionStatus},${sample.depthHeld},${sample.depthAgeMillis},${sample.depthSourceElapsedRealtime?:""}")};val last=page.last();afterTimestamp=last.timestamp;afterId=last.id}
        }}}
        shareExport(file,"text/csv")
    }
    fun startGpsProxy(){val state=_ui.value;val availability=NmeaSourceSelectionPolicy.availability(state.connection,state.nmeaFix,state.nmeaConnectionStartedElapsed,android.os.SystemClock.elapsedRealtime(),state.settings.gpsLossSeconds*1_000L);val problem=when{state.settings.gpsDataSource!=GpsDataSource.NMEA->"Select NMEA GPS before enabling the global proxy.";availability!=NmeaSourceAvailability.AVAILABLE->"Connect the NMEA server and wait for a fresh valid position before enabling the global proxy.";!NmeaSourceSelectionPolicy.isUsablePosition(state.connection,state.nmeaFix,state.nmeaConnectionStartedElapsed,android.os.SystemClock.elapsedRealtime(),state.settings.gpsLossSeconds*1_000L)->"The current NMEA position quality is not acceptable for the global proxy.";else->null};if(problem!=null){_ui.update{it.copy(proxyFeedback=problem)};return};_ui.update{it.copy(proxyFeedback="Checking Android mock-location access…")};ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.START_PROXY))}
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
    fun openAnchorageCoordinates(latitude:Double,longitude:Double)=openCoordinatesInGoogleMaps(latitude,longitude)
    fun approachAnchorageSpot(spotId:Long)=viewModelScope.launch{
        val spot=anchorageSpotRepository.get(spotId)?:run{runtimeDiagnostics.recordUserFeedback("Approach unavailable","That saved anchoring spot no longer exists. Refresh the library or restore it from backup.",true);return@launch}
        if(_ui.value.anchorageClusters.any{spot.id in it.savedAnchorageIds}){approachSavedAnchorage(spot.id);return@launch}
        val radius=maxOf(40.0,spot.preferredAlarmRadiusMeters?.takeIf{it.isFinite()&&it>0}?:0.0,spot.coordinateUncertaintyMeters?.takeIf{it.isFinite()&&it>0}?:0.0)
        val target=AnchorageCluster("spot:${spot.id}",spot.latitude,spot.longitude,radius,emptyList(),spot.name,1,spot.typicalWaterDepthMeters,spot.typicalWaterDepthMeters,spot.typicalRodeLengthMeters,spot.typicalRodeLengthMeters,spot.preferredAlarmRadiusMeters,spot.preferredAlarmRadiusMeters,spot.lastVisitedAt,spot.preferredAlarmRadiusMeters==null,spot.coordinateSource!="CONFIRMED_ANCHOR")
        gisApproachTarget=target;approachAnchorage(target.id)
    }
    fun approachSavedAnchorage(savedAnchorageId:Long){
        val cluster=_ui.value.anchorageClusters.firstOrNull{savedAnchorageId in it.savedAnchorageIds}?:run{runtimeDiagnostics.recordUserFeedback("Approach unavailable","The selected saved anchorage is not present in the current spatial index. Open its details and verify the saved coordinates.",true);return}
        approachAnchorage(cluster.id)
    }
    fun approachAnchorage(clusterId:String){
        if(availableApproachClusters().none{it.id==clusterId}){runtimeDiagnostics.recordUserFeedback("Approach unavailable","The selected anchorage target could not be resolved. The current Anchor Watch state was not changed.",true);return}
        if(_ui.value.active!=null){
            _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Lift anchor before starting saved-anchorage approach guidance. The active alarm session remains unchanged."))}
            return
        }
        if(_ui.value.settings.anchorageApproachDisclaimerAccepted)startAnchorageApproach(clusterId)
        else _ui.update{it.copy(page=0,approachDisclaimerTargetId=clusterId)}
    }
    fun confirmAnchorageApproachDisclaimer(){
        val target=_ui.value.approachDisclaimerTargetId?:return
        if(_ui.value.active!=null){
            _ui.update{it.copy(approachDisclaimerTargetId=null,connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Lift anchor before starting saved-anchorage approach guidance. The active alarm session remains unchanged."))}
            return
        }
        val updated=_ui.value.settings.copy(anchorageApproachDisclaimerAccepted=true)
        _ui.update{it.copy(settings=updated,approachDisclaimerTargetId=null)}
        viewModelScope.launch{prefs.save(updated)}
        startAnchorageApproach(target)
    }
    fun dismissAnchorageApproachDisclaimer()=_ui.update{it.copy(approachDisclaimerTargetId=null)}
    private fun startAnchorageApproach(clusterId:String){
        if(_ui.value.active!=null){runtimeDiagnostics.recordUserFeedback("Approach not started","Lift the active anchor before starting saved-anchorage approach guidance.",true);return}
        val target=availableApproachClusters().firstOrNull{it.id==clusterId}?:run{runtimeDiagnostics.recordUserFeedback("Approach unavailable","The selected target is no longer available.",true);return}
        selectedApproachClusterId=target.id
        selectedApproachMemberIds=target.savedAnchorageIds.toSet()
        phoneHeadingRepository.setApproachDemand(true)
        anchorageNearbyTracker.dismiss(_ui.value.nearbyAnchoragePrompt.map{it.cluster.id})
        _ui.update{it.copy(page=0,anchorSection=0,approachDisclaimerTargetId=null,nearbyAnchoragePrompt=emptyList(),approachHeadingMode=if(it.vesselApproachHeadingAvailable)ApproachHeadingMode.VESSEL else ApproachHeadingMode.PHONE)}
        refreshAnchorageApproach()
    }
    fun setApproachHeadingMode(mode:ApproachHeadingMode){
        val state=_ui.value
        if(selectedApproachClusterId==null)return
        if(mode==ApproachHeadingMode.VESSEL&&!state.vesselApproachHeadingAvailable)return
        _ui.update{it.copy(approachHeadingMode=mode)}
        refreshAnchorageApproach()
    }
    fun cancelAnchorageApproach(){selectedApproachClusterId=null;selectedApproachMemberIds=emptySet();gisApproachTarget=null;phoneHeadingRepository.setApproachDemand(false);refreshAnchorageApproach()}
    private fun availableApproachClusters()=_ui.value.anchorageClusters+listOfNotNull(gisApproachTarget).filter{target->_ui.value.anchorageClusters.none{it.id==target.id}}
    fun setPhoneHeadingDisplayActive(active:Boolean){phoneHeadingRepository.setDisplayDemand(active)}
    fun setMapHeadingDisplayActive(active:Boolean)=setPhoneHeadingDisplayActive(active)
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
    fun rememberAnchorSection(index:Int)=_ui.update{it.copy(anchorSection=index.coerceIn(0,2))}
    fun rememberSailSection(index:Int)=_ui.update{it.copy(sailSection=index.coerceIn(0,1))}
    fun openDataSection(index:Int)=_ui.update{it.copy(page=2,dataSection=index.coerceIn(0,3),sonarGridChangedCells=emptySet())}
    fun rememberDataSection(index:Int)=_ui.update{it.copy(dataSection=index.coerceIn(0,3))}
    fun follow(value:Boolean)=_ui.update{it.copy(follow=value)}
    fun requestRangeEditor()=_ui.update{it.copy(page=0,rangeEditorRequested=true)}
    fun consumeRangeEditorRequest()=_ui.update{it.copy(rangeEditorRequested=false)}
    fun loadHistoryEvents(sessionId:Long)=viewModelScope.launch{val events=dao.recentEvents(sessionId,30);_ui.update{it.copy(eventsBySession=mapOf(sessionId to events))}}
    fun saveAnchorage(value:SavedAnchorageEntity)=viewModelScope.launch{
        val proposed=value.copy(updatedAt=System.currentTimeMillis())
        try{anchorageApproachRepository.save(proposed)}catch(cancelled:CancellationException){throw cancelled}catch(duplicate:DuplicateAnchorageException){
            _ui.update{it.copy(anchorageDuplicateExisting=duplicate.existing)}
        }catch(error:Throwable){
            android.util.Log.e("AnchorLibrary","Failed to save anchorage",error)
            _ui.update{it.copy(anchorageOperationError="Could not save the anchorage. No data was changed.")}
        }
    }
    fun dismissAnchorageDuplicate()=_ui.update{it.copy(anchorageDuplicateExisting=null)}
    fun deleteAnchorage(id:Long)=viewModelScope.launch{
        try{
            anchorageApproachRepository.delete(id,_ui.value.active?.anchoragePlaceId)
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
            appendLine("<gpx version=\"1.1\" creator=\"Boat Watch\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
            if(events.isNotEmpty())appendLine("<metadata><desc>${xmlEscape(events.joinToString(" | "){event->"${java.time.Instant.ofEpochMilli(event.timestamp)} ${event.type} ${event.detail}"})}</desc></metadata>")
            appendLine("<wpt lat=\"${session.anchorLatitude}\" lon=\"${session.anchorLongitude}\"><name>Active anchor</name></wpt>")
            appendLine("<trk><name>Anchor session ${session.id}</name><trkseg>")
            points.forEach{appendLine("<trkpt lat=\"${it.latitude}\" lon=\"${it.longitude}\"><time>${java.time.Instant.ofEpochMilli(it.timestamp)}</time></trkpt>")}
            appendLine("</trkseg></trk>");appendLine("</gpx>")
        })
        shareExport(file,"application/gpx+xml")
    }
    private fun shareExport(file:java.io.File,mime:String){val uri=androidx.core.content.FileProvider.getUriForFile(app,"${app.packageName}.files",file);val intent=Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK);runCatching{app.startActivity(Intent.createChooser(intent,"Export anchor session").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}.onFailure{_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"No app is available to receive the export."))}}}
    private fun exportFailed(error:Throwable){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,error.message?:"Could not create the export."))}}
    private fun xmlEscape(value:String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    override fun onCleared(){runtimeResources.release(RuntimeOwner.VESSEL_HUB_UI);phoneHeadingRepository.setApproachDemand(false);phoneHeadingRepository.setDisplayDemand(false);systemLocation.setAppEnabled(false);super.onCleared()}
}
