package com.yokuli.anchorwatch

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yokuli.anchorwatch.data.NavigationRepository
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
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.location.GlobalMockLocationManager
import com.yokuli.anchorwatch.location.GpsSourceSafety
import com.yokuli.anchorwatch.location.MockGpsState
import com.yokuli.anchorwatch.location.MockGpsStatus
import com.yokuli.anchorwatch.location.SystemLocationRepository
import com.yokuli.anchorwatch.service.AnchorForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
)

private data class PositionSources(val selected:NavigationFix?,val nmea:NavigationFix?,val system:NavigationFix?,val settings:AppSettings)
data class AnchorWatchInput(val placement:AnchorPlacementMode,val rangeMode:AnchorRangeMode,val safetyPreset:AnchorSafetyPreset,val depthMeters:Double?,val rodeMeters:Double,val boatLengthMeters:Double?,val alarmRadiusMeters:Double)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val app:Application,
    private val nav:NavigationRepository,
    private val dao:AnchorDao,
    private val prefs:SettingsRepository,
    private val mockManager:GlobalMockLocationManager,
    private val systemLocation:SystemLocationRepository,
    private val endpointPreflight:NmeaEndpointPreflight,
):AndroidViewModel(app){
    private val _ui=MutableStateFlow(MainUiState());val ui=_ui.asStateFlow()
    private var pointsJob:Job?=null
    private val estimator=AnchorCenterEstimator()

    init{
        val sources=combine(nav.fix,systemLocation.fix,prefs.settings){nmea,system,settings->
            PositionSources(if(settings.gpsDataSource==GpsDataSource.NMEA)nmea else system,nmea,system,settings)
        }
        viewModelScope.launch{
            combine(sources,nav.connectionState,nav.diagnostics,dao.sessions()){position,connection,diagnostics,sessions->
                arrayOf(position,connection,diagnostics,sessions)
            }.collect{values->
                val position=values[0] as PositionSources
                @Suppress("UNCHECKED_CAST") val sessions=values[3] as List<AnchorSessionEntity>
                val active=sessions.firstOrNull{it.active}
                _ui.update{it.copy(fix=position.selected,nmeaFix=position.nmea,systemFix=position.system,connection=values[1] as NmeaConnectionState,diagnostics=values[2] as NmeaDiagnostics,settings=position.settings,sessions=sessions,active=active)}
                observePoints(active)
            }
        }
        viewModelScope.launch{prefs.settings.map{it.gpsDataSource}.distinctUntilChanged().collect{systemLocation.setAppEnabled(it==GpsDataSource.SYSTEM)}}
        viewModelScope.launch{mockManager.status.collect{status->_ui.update{it.copy(mockGps=status)}}}
    }

    private fun observePoints(session:AnchorSessionEntity?){
        if(pointsJob?.isActive==true&&_ui.value.active?.id==session?.id)return
        pointsJob?.cancel()
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
        prefs.save(_ui.value.settings.copy(profile=profile))
        nav.connect(profile)
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
    fun updateSettings(settings:AppSettings)=viewModelScope.launch{prefs.save(settings)}
    fun setGpsDataSource(source:GpsDataSource)=switchGpsDataSource(source)
    fun switchGpsDataSource(source:GpsDataSource)=viewModelScope.launch{
        val current=_ui.value.settings
        if(source==current.gpsDataSource)return@launch
        if(source==GpsDataSource.SYSTEM&&GpsSourceSafety.blocksSystemGps(current.mockEnabled,mockManager.status.value.state)){
            _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Disable the global NMEA GPS proxy first. While Android mock mode is active, System GPS is not an independent source."))};return@launch
        }
        val active=_ui.value.active
        if(active==null||active.paused){prefs.save(current.copy(gpsDataSource=source,mockEnabled=false));active?.let{dao.insertEvent(com.yokuli.anchorwatch.data.database.AlarmEventEntity(sessionId=it.id,timestamp=System.currentTimeMillis(),type="WATCH_GPS_SOURCE_CHANGED",detail="${current.gpsDataSource.name}_TO_${source.name}_WHILE_PAUSED"))};_ui.update{it.copy(connectionAttempt=ConnectionAttempt())};return@launch}
        if(source==GpsDataSource.SYSTEM&&ContextCompat.checkSelfPermission(app,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){_ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.FAILED,"Precise location permission is required before switching to System GPS."))};return@launch}
        _ui.update{it.copy(connectionAttempt=ConnectionAttempt(ConnectionAttemptState.TESTING,"Verifying a fresh ${if(source==GpsDataSource.NMEA)"NMEA" else "non-mock System"} GPS position before switching…"))}
        ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(if(source==GpsDataSource.NMEA)AnchorForegroundService.SWITCH_WATCH_SOURCE_NMEA else AnchorForegroundService.SWITCH_WATCH_SOURCE_SYSTEM))
        val switched=withTimeoutOrNull(12_000){prefs.settings.first{it.gpsDataSource==source}}
        _ui.update{it.copy(connectionAttempt=if(switched!=null)ConnectionAttempt()else ConnectionAttempt(ConnectionAttemptState.FAILED,"GPS source was not changed because no fresh ${if(source==GpsDataSource.NMEA)"NMEA" else "System"} position was available."))}
    }
    fun onPermissionsChanged(){systemLocation.refreshPermission()}

    fun clearDiagnostics()=nav.clearDiagnostics()
    fun arm(lat:Double,lon:Double,input:AnchorWatchInput){
        val intent=Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.ARM)
            .putExtra("lat",lat).putExtra("lon",lon).putExtra("rode",input.rodeMeters).putExtra("depth",input.depthMeters?:Double.NaN).putExtra("boatLength",input.boatLengthMeters?:Double.NaN)
            .putExtra("warning",maxOf(input.alarmRadiusMeters*.8,input.alarmRadiusMeters-10).coerceAtMost(input.alarmRadiusMeters-.1)).putExtra("alarm",input.alarmRadiusMeters).putExtra("placement",input.placement.name).putExtra("rangeMode",input.rangeMode.name).putExtra("safetyPreset",input.safetyPreset.name)
        ContextCompat.startForegroundService(app,intent)
    }
    fun updateAnchorSettings(input:AnchorWatchInput){val intent=Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.UPDATE_RADIUS).putExtra("rode",input.rodeMeters).putExtra("depth",input.depthMeters?:Double.NaN).putExtra("boatLength",input.boatLengthMeters?:Double.NaN).putExtra("alarm",input.alarmRadiusMeters).putExtra("rangeMode",input.rangeMode.name).putExtra("safetyPreset",input.safetyPreset.name);ContextCompat.startForegroundService(app,intent)}
    fun pauseWatch()=app.startService(Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.PAUSE_WATCH))
    fun resumeWatch()=ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.RESUME_WATCH))
    fun liftAnchor()=ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.LIFT_ANCHOR))
    fun stop()=pauseWatch()
    fun acknowledge()=app.startService(Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.ACK))
    fun startGpsProxy(){if(_ui.value.settings.gpsDataSource==GpsDataSource.NMEA)ContextCompat.startForegroundService(app,Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.START_PROXY))}
    fun stopGpsProxy()=app.startService(Intent(app,AnchorForegroundService::class.java).setAction(AnchorForegroundService.STOP_PROXY))
    fun openDeveloperOptions(){runCatching{app.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}.onFailure{app.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}}
    fun openBatteryOptimization(){app.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    fun page(index:Int)=_ui.update{it.copy(page=index)}
    fun follow(value:Boolean)=_ui.update{it.copy(follow=value)}
    fun exportCsv(session:AnchorSessionEntity){viewModelScope.launch{val points=dao.points(session.id).first();val file=java.io.File(app.cacheDir,"anchor-${session.id}.csv");file.writeText(buildString{appendLine("timestamp,latitude,longitude,distance_from_anchor_m,sog_knots,cog_deg,heading_deg,hdop");points.forEach{appendLine("${it.timestamp},${it.latitude},${it.longitude},${it.distanceFromAnchor},${it.sog?:""},${it.cog?:""},${it.heading?:""},${it.hdop?:""}")}})}}
    override fun onCleared(){systemLocation.setAppEnabled(false);super.onCleared()}
}
