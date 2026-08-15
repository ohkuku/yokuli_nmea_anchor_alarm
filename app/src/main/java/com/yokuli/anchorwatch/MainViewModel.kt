package com.yokuli.anchorwatch

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.data.AlarmUiRepository
import com.yokuli.anchorwatch.data.database.AnchorDao
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.TrackPointEntity
import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.nmea.NmeaDiagnostics
import com.yokuli.anchorwatch.data.nmea.NmeaEndpointPreflight
import com.yokuli.anchorwatch.data.preferences.AppSettings
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.domain.anchor.AnchorCenterEstimator
import com.yokuli.anchorwatch.domain.model.AnchorEstimate
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
import com.yokuli.anchorwatch.service.AnchorForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

enum class ConnectionAttemptState { IDLE, TESTING, FAILED }
data class ConnectionAttempt(val state:ConnectionAttemptState=ConnectionAttemptState.IDLE,val message:String="")

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
)

private data class PositionSources(val selected:NavigationFix?,val nmea:NavigationFix?,val system:NavigationFix?,val settings:AppSettings)
private data class AvailablePositions(val nmea:NavigationFix?,val system:NavigationFix?,val demo:NavigationFix?,val demoStatus:DemoGpsStatus)
data class AnchorWatchInput(val placement:AnchorPlacementMode,val rangeMode:AnchorRangeMode,val safetyPreset:AnchorSafetyPreset,val depthMeters:Double?,val rodeMeters:Double,val bowHeightMeters:Double,val boatLengthMeters:Double?,val alarmRadiusMeters:Double)

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
):AndroidViewModel(app){
    private val _ui=MutableStateFlow(MainUiState());val ui=_ui.asStateFlow()
    private var pointsJob:Job?=null
    private var observedSessionId:Long?=null
    private val estimator=AnchorCenterEstimator()

    init{
        val available=combine(nav.fix,systemLocation.fix,demoLocation.fix,demoLocation.status){nmea,system,demo,status->AvailablePositions(nmea,system,demo,status)}
        val sources=combine(available,prefs.settings){positions,settings->
            val selected=when(settings.gpsDataSource){GpsDataSource.NMEA->positions.nmea;GpsDataSource.SYSTEM->positions.system;GpsDataSource.DEMO->if(positions.demoStatus.running)positions.demo else positions.system}
            PositionSources(selected,positions.nmea,positions.system,settings) to positions.demoStatus
        }
        viewModelScope.launch{
            combine(sources,nav.connectionState,nav.connectionStartedElapsed,nav.diagnostics,dao.sessions()){sourceAndDemo,connection,connectionStarted,diagnostics,sessions->
                arrayOf(sourceAndDemo,connection,connectionStarted,diagnostics,sessions)
            }.collect{values->
                @Suppress("UNCHECKED_CAST") val sourceAndDemo=values[0] as Pair<PositionSources,DemoGpsStatus>
                val position=sourceAndDemo.first
                @Suppress("UNCHECKED_CAST") val sessions=values[4] as List<AnchorSessionEntity>
                val active=sessions.firstOrNull{it.active}
                _ui.update{it.copy(fix=position.selected,nmeaFix=position.nmea,nmeaConnectionStartedElapsed=values[2] as Long?,systemFix=position.system,connection=values[1] as NmeaConnectionState,diagnostics=values[3] as NmeaDiagnostics,settings=position.settings,sessions=sessions,active=active,demoGps=sourceAndDemo.second)}
                observePoints(active)
            }
        }
        viewModelScope.launch{prefs.settings.map{it.gpsDataSource}.distinctUntilChanged().collect{systemLocation.setAppEnabled(it==GpsDataSource.SYSTEM||it==GpsDataSource.DEMO)}}
        viewModelScope.launch{mockManager.status.collect{status->_ui.update{current->val defaultInactive=status.state==MockGpsState.INACTIVE&&status.message=="Android GPS is using the normal system source.";current.copy(mockGps=status,proxyFeedback=if(defaultInactive)current.proxyFeedback?:status.message else status.message)}}}
        viewModelScope.launch{alarmUi.snapshot.collect{snapshot->_ui.update{it.copy(alarmSnapshot=snapshot)}}}
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

    fun validateProfile(profile:ConnectionProfile)=endpointPreflight.validate(profile)

    fun saveAndConnect(profile:ConnectionProfile)=viewModelScope.launch{
        if(_ui.value.connection!=NmeaConnectionState.DISCONNECTED||_ui.value.connectionAttempt.state==ConnectionAttemptState.TESTING)return@launch
        endpointPreflight.validate(profile)?.let{message->_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,message))};return@launch}
        _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.TESTING,"Testing the endpoint and waiting for valid NMEA data…"))}
        val result=endpointPreflight.check(profile)
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
        }else if(active?.paused==false&&previous.gpsDataSource!=GpsDataSource.NMEA){
            ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.SWITCH_WATCH_SOURCE_NMEA))
            val switched=withTimeoutOrNull(12_000){prefs.settings.first{it.gpsDataSource==GpsDataSource.NMEA}}
            if(switched==null){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"NMEA connected, but the active anchor watch could not complete a safe GPS handover."))};return@launch}
        }else{
            prefs.save(previous.copy(profile=profile,gpsDataSource=GpsDataSource.NMEA,mockEnabled=false))
        }
        _ui.update{it.copy(connectionAttempt=ConnectionAttempt())}
    }

    fun disconnect(){
        if(_ui.value.active?.paused==false&&_ui.value.settings.gpsDataSource==GpsDataSource.NMEA){
            _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Anchor watch is using NMEA. Choose System GPS or pause the watch before disconnecting."))}
            return
        }
        _ui.update{it.copy(connectionAttempt=ConnectionAttempt())};nav.disconnect()
    }
    fun stopActiveWatchAndDisconnect(){
        _ui.update{it.copy(connectionAttempt=ConnectionAttempt())}
        ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.STOP_WATCH_AND_DISCONNECT))
    }
    fun switchActiveWatchToSystemAndDisconnect()=viewModelScope.launch{
        if(_ui.value.active?.paused!=false||_ui.value.settings.gpsDataSource!=GpsDataSource.NMEA){disconnect();return@launch}
        if(ContextCompat.checkSelfPermission(app,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Precise location permission is required before an active watch can switch to System GPS."))};return@launch
        }
        if(GpsSourceSafety.blocksSystemGps(_ui.value.settings.mockEnabled,_ui.value.mockGps.state)){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Disable the global NMEA GPS proxy before switching an anchor watch to System GPS."))};return@launch}
        _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.TESTING,"Acquiring a fresh System GPS fix before disconnecting NMEA…"))}
        ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.SWITCH_WATCH_TO_SYSTEM))
        val switched=withTimeoutOrNull(12_000){prefs.settings.first{it.gpsDataSource==GpsDataSource.SYSTEM}}
        _ui.update{it.copy(connectionAttempt=if(switched!=null)ConnectionAttempt() else ConnectionAttempt(ConnectionAttemptState.FAILED,"NMEA stayed connected because a fresh System GPS fix was not available."))}
    }
    fun clearConnectionAttempt()=_ui.update{it.copy(connectionAttempt=ConnectionAttempt())}
    fun updateSettings(settings:AppSettings){_ui.update{it.copy(settings=settings)};viewModelScope.launch{prefs.save(settings)}}
    fun setMapType(mapType:Int){val value=mapType.takeIf{it in 1..2}?:1;val updated=_ui.value.settings.copy(mapType=value);_ui.update{it.copy(settings=updated)};viewModelScope.launch{prefs.save(updated)}}
    fun setGpsDataSource(source:GpsDataSource)=switchGpsDataSource(source)
    fun switchGpsDataSource(source:GpsDataSource)=viewModelScope.launch{
        val current=_ui.value.settings
        if(current.demoMode){
            if(source!=GpsDataSource.DEMO)_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Demo mode owns the App GPS source. Lift the current anchor and disable Demo mode before choosing System or NMEA."))}
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
        val active=_ui.value.active
        if(active==null||active.paused){if(current.gpsDataSource==GpsDataSource.DEMO&&source!=GpsDataSource.DEMO)demoLocation.stop();prefs.save(current.copy(gpsDataSource=source,mockEnabled=false));active?.let{dao.insertEvent(com.yokuli.anchorwatch.data.database.AlarmEventEntity(sessionId=it.id,timestamp=System.currentTimeMillis(),type="WATCH_GPS_SOURCE_CHANGED",detail="${current.gpsDataSource.name}_TO_${source.name}_WHILE_PAUSED"))};_ui.update{it.copy(connectionAttempt=ConnectionAttempt())};return@launch}
        if(source==GpsDataSource.DEMO){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Pause or lift the active anchor session before switching a live watch into Demo GPS."))};return@launch}
        if(source==GpsDataSource.SYSTEM&&ContextCompat.checkSelfPermission(app,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Precise location permission is required before switching to System GPS."))};return@launch}
        _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.TESTING,"Verifying a fresh ${if(source==GpsDataSource.NMEA)"NMEA" else "non-mock System"} GPS position before switching…"))}
        ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(if(source==GpsDataSource.NMEA)AnchorForegroundService.SWITCH_WATCH_SOURCE_NMEA else AnchorForegroundService.SWITCH_WATCH_SOURCE_SYSTEM))
        val switched=withTimeoutOrNull(12_000){prefs.settings.first{it.gpsDataSource==source}}
        _ui.update{it.copy(connectionAttempt=if(switched!=null)ConnectionAttempt()else ConnectionAttempt(ConnectionAttemptState.FAILED,"GPS source was not changed because no fresh ${if(source==GpsDataSource.NMEA)"NMEA" else "System"} position was available."))}
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
            .putExtra("warning",maxOf(input.alarmRadiusMeters*.8,input.alarmRadiusMeters-10).coerceAtMost(input.alarmRadiusMeters-.1)).putExtra("alarm",input.alarmRadiusMeters).putExtra("placement",input.placement.name).putExtra("rangeMode",input.rangeMode.name).putExtra("safetyPreset",input.safetyPreset.name)
        ContextCompat.startForegroundService(app,intent)
    }
    fun updateAnchorSettings(input:AnchorWatchInput){val intent=Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.UPDATE_RADIUS).putExtra("alarm",input.alarmRadiusMeters);ContextCompat.startForegroundService(app,intent)}
    fun pauseWatch()=app.startService(Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.PAUSE_WATCH))
    fun resumeWatch()=ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.RESUME_WATCH))
    fun liftAnchor()=ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.LIFT_ANCHOR))
    fun stop()=pauseWatch()
    fun acknowledge()=app.startService(Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.ACK))
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
    fun exportCsv(session:AnchorSessionEntity){viewModelScope.launch{val points=dao.points(session.id).first();val file=java.io.File(app.cacheDir,"anchor-${session.id}.csv");file.writeText(buildString{appendLine("timestamp,latitude,longitude,distance_from_anchor_m,sog_knots,cog_deg,heading_deg,hdop");points.forEach{appendLine("${it.timestamp},${it.latitude},${it.longitude},${it.distanceFromAnchor},${it.sog?:""},${it.cog?:""},${it.heading?:""},${it.hdop?:""}")}})}}
    override fun onCleared(){systemLocation.setAppEnabled(false);super.onCleared()}
}
