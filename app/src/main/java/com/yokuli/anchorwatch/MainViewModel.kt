package com.yokuli.anchorwatch

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.data.AlarmUiRepository
import com.yokuli.anchorwatch.data.database.AnchorDao
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.TrackPointEntity
import com.yokuli.anchorwatch.data.database.AlarmEventEntity
import com.yokuli.anchorwatch.data.database.DepthSampleEntity
import com.yokuli.anchorwatch.data.database.SonarDao
import com.yokuli.anchorwatch.data.database.SonarSurveyEntity
import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.nmea.NmeaDiagnostics
import com.yokuli.anchorwatch.data.nmea.NmeaEndpointPreflight
import com.yokuli.anchorwatch.data.preferences.AppSettings
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.data.sharing.NmeaSharingServer
import com.yokuli.anchorwatch.data.sharing.NmeaSharingStatus
import com.yokuli.anchorwatch.data.sonar.SonarRecorderStatus
import com.yokuli.anchorwatch.data.sonar.SonarSurveyRecorder
import com.yokuli.anchorwatch.domain.sonar.TideMode
import com.yokuli.anchorwatch.domain.sonar.SonarGrid
import com.yokuli.anchorwatch.domain.sonar.SonarGridSample
import com.yokuli.anchorwatch.domain.anchor.AnchorCenterEstimator
import com.yokuli.anchorwatch.domain.model.AnchorEstimate
import com.yokuli.anchorwatch.domain.model.AnchorCenterSource
import com.yokuli.anchorwatch.domain.model.AnchorPlacementMode
import com.yokuli.anchorwatch.domain.model.AnchorRangeMode
import com.yokuli.anchorwatch.domain.model.AnchorSafetyPreset
import com.yokuli.anchorwatch.domain.model.AlarmSnapshot
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
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
import com.yokuli.anchorwatch.service.AnchorForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withTimeoutOrNull
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
    val sessions:List<AnchorSessionEntity> = emptyList(),
    val active:AnchorSessionEntity?=null,
    val points:List<TrackPointEntity> = emptyList(),
    val estimate:AnchorEstimate?=null,
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
    val sonarRecorder:SonarRecorderStatus = SonarRecorderStatus(),
)

private data class PositionSources(val selected:NavigationFix?,val nmea:NavigationFix?,val system:NavigationFix?,val settings:AppSettings)
private data class AvailablePositions(val nmea:NavigationFix?,val system:NavigationFix?,val demo:NavigationFix?,val demoStatus:DemoGpsStatus)
data class AnchorWatchInput(val placement:AnchorPlacementMode,val rangeMode:AnchorRangeMode,val safetyPreset:AnchorSafetyPreset,val depthMeters:Double?,val rodeMeters:Double,val bowHeightMeters:Double,val boatLengthMeters:Double?,val alarmRadiusMeters:Double,val positionSource:GpsDataSource=GpsDataSource.SYSTEM,val centerSource:AnchorCenterSource=AnchorCenterSource.CURRENT_POSITION,val usePhoneHeading:Boolean=false)

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
    private val sonarDao:SonarDao,
    private val sonarRecorder:SonarSurveyRecorder,
):AndroidViewModel(app){
    private val _ui=MutableStateFlow(MainUiState());val ui=_ui.asStateFlow()
    private var pointsJob:Job?=null
    private var observedSessionId:Long?=null
    private var sonarSamplesJob:Job?=null
    private var observedSonarSurveyId:Long?=null
    private val estimator=AnchorCenterEstimator()

    init{
        val available=combine(nav.fix,systemLocation.fix,demoLocation.fix,demoLocation.status){nmea,system,demo,status->AvailablePositions(nmea,system,demo,status)}
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
            combine(sources,nav.connectionState,nav.connectionStartedElapsed,nav.diagnostics,dao.sessions()){sourceAndDemo,connection,connectionStarted,diagnostics,sessions->
                arrayOf(sourceAndDemo,connection,connectionStarted,diagnostics,sessions)
            }.collect{values->
                @Suppress("UNCHECKED_CAST") val sourceAndDemo=values[0] as Pair<PositionSources,DemoGpsStatus>
                val position=sourceAndDemo.first
                @Suppress("UNCHECKED_CAST") val sessions=values[4] as List<AnchorSessionEntity>
                val active=sessions.firstOrNull{it.active}
                val connection=values[1] as NmeaConnectionState
                _ui.update{it.copy(nmeaFix=position.nmea,nmeaConnectionStartedElapsed=values[2] as Long?,systemFix=position.system,connection=connection,diagnostics=values[3] as NmeaDiagnostics,settings=position.settings,sessions=sessions,active=active,demoGps=sourceAndDemo.second)}
                observePoints(active)
            }
        }
        viewModelScope.launch{acceptedPosition.state.collect{accepted->_ui.update{it.copy(fix=accepted.acceptedFix,positionHealth=accepted.health,acceptedPosition=accepted)}}}
        viewModelScope.launch{prefs.settings.map{it.gpsDataSource}.distinctUntilChanged().collect{systemLocation.setAppEnabled(it==GpsDataSource.SYSTEM||it==GpsDataSource.DEMO)}}
        viewModelScope.launch{mockManager.status.collect{status->_ui.update{current->val defaultInactive=status.state==MockGpsState.INACTIVE&&status.message=="Android GPS is using the normal system source.";current.copy(mockGps=status,proxyFeedback=if(defaultInactive)current.proxyFeedback?:status.message else status.message)}}}
        viewModelScope.launch{alarmUi.snapshot.collect{snapshot->_ui.update{it.copy(alarmSnapshot=snapshot)}}}
        viewModelScope.launch{dao.allEvents().collect{events->_ui.update{it.copy(eventsBySession=events.groupBy{event->event.sessionId})}}}
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
        }}
    }

    private fun observePoints(session:AnchorSessionEntity?){
        if(pointsJob?.isActive==true&&observedSessionId==session?.id)return
        pointsJob?.cancel()
        observedSessionId=session?.id
        if(session==null){_ui.update{it.copy(points=emptyList(),estimate=null)};return}
        pointsJob=viewModelScope.launch{dao.points(session.id).collect{points->
            val expected=session.expectedSwingRadiusMeters.takeIf{session.rodeLengthMeters>0}
            val estimate=estimator.estimate(points.filter{it.hdop==null||it.hdop<=5}.map{AnchorCenterEstimator.Point(it.latitude,it.longitude)},expected)
            _ui.update{it.copy(points=points,estimate=estimate)}
        }}
    }

    private fun observeSonarSamples(surveyId:Long?){
        _ui.update{it.copy(selectedSonarSurveyId=surveyId)}
        if(sonarSamplesJob?.isActive==true&&observedSonarSurveyId==surveyId)return
        sonarSamplesJob?.cancel();observedSonarSurveyId=surveyId
        if(surveyId==null){_ui.update{it.copy(sonarSamples=emptyList(),sonarGrid=SonarGrid.build(emptyList()))};return}
        val samplesFlow=if(surveyId==CORRECTED_SONAR_HISTORY_ID)sonarDao.normalizedHistory()else sonarDao.samples(surveyId)
        sonarSamplesJob=viewModelScope.launch{samplesFlow.conflate().collect{samples->val grid=withContext(kotlinx.coroutines.Dispatchers.Default){SonarGrid.build(samples.filter{it.usable}.map{sample->
            val trustWeight=if(sample.fixTrust==com.yokuli.anchorwatch.domain.model.FixTrust.TRUSTED.name)1.0 else .35
            val speedWeight=if((sample.sogKnots?:0.0)>12.0).5 else 1.0
            SonarGridSample(sample.latitude,sample.longitude,sample.normalizedDepthMeters?:sample.measuredDepthMeters,sample.horizontalAccuracyMeters,trustWeight*speedWeight)
        })};_ui.update{it.copy(sonarSamples=samples,sonarGrid=grid)}}}
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
    fun setNmeaSharing(enabled:Boolean,port:Int){
        val safePort=port.takeIf{it in 1024..65535}
        if(safePort==null){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"NMEA Sharing port must be between 1024 and 65535."))};return}
        val settings=_ui.value.settings.copy(nmeaSharingEnabled=enabled,nmeaSharingPort=safePort);_ui.update{it.copy(settings=settings)}
        viewModelScope.launch{prefs.save(settings);if(enabled||sharingServer.status.value.state!=com.yokuli.anchorwatch.data.sharing.SharingServerState.STOPPED)ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.SET_NMEA_SHARING).putExtra("enabled",enabled).putExtra("port",safePort))}
    }
    fun deleteHistorySession(session:AnchorSessionEntity){if(session.active)return;viewModelScope.launch{dao.deleteCompletedSession(session.id)}}
    fun setMapType(mapType:Int){val value=mapType.takeIf{it in 1..2}?:1;val updated=_ui.value.settings.copy(mapType=value);_ui.update{it.copy(settings=updated)};viewModelScope.launch{prefs.save(updated)}}
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
        ContextCompat.startForegroundService(app,intent)
    }
    fun updateAnchorSettings(input:AnchorWatchInput){val intent=Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.UPDATE_RADIUS).putExtra("alarm",input.alarmRadiusMeters);ContextCompat.startForegroundService(app,intent)}
    fun setPhoneHeadingEvidence(enabled:Boolean)=ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.UPDATE_PHONE_HEADING).putExtra("enabled",enabled))
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
    fun startSonarSurvey(name:String,tideMode:TideMode,manualTideOffsetMeters:Double){
        if(_ui.value.settings.demoMode){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Disable Demo mode before recording a personal sonar survey."))};return}
        val intent=Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.START_SONAR_SURVEY).putExtra("name",name).putExtra("tideMode",tideMode.name).putExtra("manualTideOffset",manualTideOffsetMeters)
        ContextCompat.startForegroundService(app,intent)
    }
    fun stopSonarSurvey()=app.startService(Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.STOP_SONAR_SURVEY))
    fun renameSonarSurvey(surveyId:Long,name:String)=viewModelScope.launch{sonarRecorder.rename(surveyId,name)}
    fun deleteSonarSurvey(surveyId:Long)=viewModelScope.launch{sonarRecorder.delete(surveyId)}
    fun rebuildSonarSurvey(surveyId:Long)=viewModelScope.launch{sonarRecorder.rebuild(surveyId)}
    fun selectSonarSurvey(surveyId:Long)=observeSonarSamples(surveyId)
    fun selectCorrectedSonarHistory()=observeSonarSamples(CORRECTED_SONAR_HISTORY_ID)
    fun exportSonarCsv(survey:SonarSurveyEntity)=viewModelScope.launch{
        val samples=sonarDao.samplesNow(survey.id);val file=java.io.File(app.cacheDir,"sonar-${survey.id}.csv")
        file.writeText(buildString{appendLine("timestamp,latitude,longitude,raw_depth_m,measured_depth_m,normalized_depth_m,reference,sentence,nmea_offset_m,gps_source,position_provider,position_accuracy_m,hdop,sog_knots,position_age_ms,fix_trust,disposition,usable,position_correction,base_grid_x,base_grid_y,survey_id,tide_correction")
            samples.forEach{appendLine("${it.timestamp},${it.latitude},${it.longitude},${it.rawDepthMeters},${it.measuredDepthMeters},${it.normalizedDepthMeters?:""},${it.depthReference},${it.sentenceType},${it.nmeaOffsetMeters?:""},${it.gpsSource},${it.positionProvider},${it.horizontalAccuracyMeters?:""},${it.hdop?:""},${it.sogKnots?:""},${it.positionAgeMillis},${it.fixTrust},${it.disposition},${it.usable},${it.positionCorrectionMethod},${it.baseGridX},${it.baseGridY},${survey.id},${survey.tideMode}")}})
        shareExport(file,"text/csv")
    }
    fun startGpsProxy(){val state=_ui.value;val problem=when{state.settings.gpsDataSource!=GpsDataSource.NMEA->"Select NMEA GPS before enabling the global proxy.";state.connection!=NmeaConnectionState.CONNECTED->"Connect to the NMEA source first.";state.nmeaFix?.valid!=true->"The NMEA connection has not supplied a valid position yet.";else->null};if(problem!=null){_ui.update{it.copy(proxyFeedback=problem)};return};_ui.update{it.copy(proxyFeedback="Checking Android mock-location access…")};ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.START_PROXY))}
    fun stopGpsProxy(){_ui.update{it.copy(proxyFeedback=null)};app.startService(Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.STOP_PROXY))}
    fun openDeveloperOptions(){runCatching{app.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}.onFailure{app.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}}
    fun openAlarmNotificationSettings(){val channelReady=android.os.Build.VERSION.SDK_INT>=26&&app.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel(AnchorForegroundService.ALARM_CH)!=null;val intent=when{channelReady->Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,app.packageName).putExtra(android.provider.Settings.EXTRA_CHANNEL_ID,AnchorForegroundService.ALARM_CH);android.os.Build.VERSION.SDK_INT>=26->Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,app.packageName);else->Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${app.packageName}"))};app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    fun openBatteryOptimization(){app.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    fun openAnchorInGoogleMaps(session:AnchorSessionEntity){
        if(session.centerStatus!=com.yokuli.anchorwatch.domain.model.AnchorCenterStatus.RESOLVED.name)return
        val coordinates="${"%.7f".format(java.util.Locale.US,session.anchorLatitude)},${"%.7f".format(java.util.Locale.US,session.anchorLongitude)}"
        val uri=android.net.Uri.parse("https://www.google.com/maps/search/?api=1&query=$coordinates")
        val google=Intent(Intent.ACTION_VIEW,uri).setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val intent=if(google.resolveActivity(app.packageManager)!=null)google else Intent(Intent.ACTION_VIEW,uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching{app.startActivity(intent)}.onFailure{_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"No map application or browser is available to open the anchor position."))}}
    }
    fun page(index:Int)=_ui.update{it.copy(page=index)}
    fun follow(value:Boolean)=_ui.update{it.copy(follow=value)}
    fun requestRangeEditor()=_ui.update{it.copy(page=0,rangeEditorRequested=true)}
    fun consumeRangeEditorRequest()=_ui.update{it.copy(rangeEditorRequested=false)}
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
            appendLine("<gpx version=\"1.1\" creator=\"Anchor by Yokuli\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
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
    override fun onCleared(){systemLocation.setAppEnabled(false);super.onCleared()}
}
