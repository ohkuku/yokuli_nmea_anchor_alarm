package com.yokuli.anchorwatch.service

import android.app.*
import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.net.wifi.WifiManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yokuli.anchorwatch.MainActivity
import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.data.database.*
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.domain.anchor.*
import com.yokuli.anchorwatch.domain.model.*
import com.yokuli.anchorwatch.location.*
import com.yokuli.anchorwatch.localization.localized
import com.yokuli.anchorwatch.localization.usesChinese
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@AndroidEntryPoint
class AnchorForegroundService:Service(){
 @Inject lateinit var navigation:NavigationRepository;@Inject lateinit var dao:AnchorDao;@Inject lateinit var preferences:SettingsRepository;@Inject lateinit var mockGps:GlobalMockLocationManager;@Inject lateinit var systemLocation:SystemLocationRepository;@Inject lateinit var demoLocation:DemoLocationRepository
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);private val commandMutex=Mutex();private val proxyMutex=Mutex();private val stateReady=CompletableDeferred<Unit>();private var wake:PowerManager.WakeLock?=null;private var wifi:WifiManager.WifiLock?=null;private var alarmPlayer:MediaPlayer?=null;private var engine=AlarmEngine();private var session:AnchorSessionEntity?=null;private var lastSnapshot:AlarmSnapshot?=null;private var lastTrack=0L;private var proxyPolicy:MockGpsPolicy?=null;private var lowBatteryReported=false;private var lastReportedAlarm:AlarmType?=null;private var nmeaLossAnnounced=false;private var currentGpsSource=GpsDataSource.SYSTEM;private var alarmSnoozeMinutes=5;private var restoredDemoElapsed=0L;private val centerEstimator=AnchorCenterEstimator();private val backdownEstimator=BackdownCenterEstimator();private val centerPoints=mutableListOf<AnchorCenterEstimator.Point>();private val backdownSamples=mutableListOf<BackdownCenterEstimator.Sample>();private var lastCenterUpdate=0L;@Volatile private var armPending=false;@Volatile private var appLanguage=AppLanguage.SYSTEM

 private data class SourcedFix(val source:GpsDataSource,val fix:NavigationFix)
 private data class ArmRequest(val config:AnchorConfig,val placement:AnchorPlacementMode,val rangeMode:AnchorRangeMode,val safetyPreset:AnchorSafetyPreset,val boatLength:Double?)

 override fun onCreate(){
  super.onCreate();channels();ServiceCompat.startForeground(this,ONGOING,notification(l("Starting safety monitor…","正在启动安全监控…"),false),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
  scope.launch{commandMutex.withLock{try{restoreState()}finally{stateReady.complete(Unit)}}}
  scope.launch{combine(preferences.settings.map{it.gpsDataSource}.distinctUntilChanged(),navigation.fix,systemLocation.fix){source,nmea,system->currentGpsSource=source;when(source){GpsDataSource.NMEA->nmea?.let{SourcedFix(source,it)};GpsDataSource.SYSTEM->system?.let{SourcedFix(source,it)};GpsDataSource.DEMO->null}}.filterNotNull().collect{value->commandMutex.withLock{handleFix(value.fix,value.source)}}}
  scope.launch{navigation.connectionState.collect{state->commandMutex.withLock{handleNmeaState(state)}}}
  scope.launch{preferences.settings.map{it.alarmSnoozeMinutes}.distinctUntilChanged().collect{alarmSnoozeMinutes=it}}
  scope.launch{preferences.settings.map{it.appLanguage}.distinctUntilChanged().collect{appLanguage=it;channels();refreshNotification()}}
  scope.launch{preferences.settings.map{it.demoScenario to it.demoSpeedMultiplier}.distinctUntilChanged().collect{(scenario,speed)->commandMutex.withLock{session?.takeIf{currentGpsSource==GpsDataSource.DEMO}?.let{demoLocation.reconfigure(scenario,it.alarmRadiusMeters,speed)}}}}
  scope.launch{while(isActive){delay(1000);commandMutex.withLock{watchdog()}}}
  scope.launch{while(isActive){delay(30_000);batteryWatchdog()}}
 }

 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  when(intent?.action){
   ARM->{armPending=true;val c=AnchorConfig(intent.getDoubleExtra("lat",0.0),intent.getDoubleExtra("lon",0.0),intent.getDoubleExtra("rode",0.0),intent.getDoubleExtra("depth",Double.NaN).takeUnless{it.isNaN()},warningRadiusMeters=intent.getDoubleExtra("warning",40.0),alarmRadiusMeters=intent.getDoubleExtra("alarm",50.0));val request=ArmRequest(c,enumExtra(intent,"placement",AnchorPlacementMode.CENTER_DROP),enumExtra(intent,"rangeMode",AnchorRangeMode.BASIC),enumExtra(intent,"safetyPreset",AnchorSafetyPreset.BALANCED),intent.getDoubleExtra("boatLength",Double.NaN).takeUnless{it.isNaN()});launchCommand{try{arm(request)}finally{armPending=false;releaseIfIdle()}}}
   ACK,SNOOZE->launchCommand{snoozeAlarm()}
   STOP_WATCH,PAUSE_WATCH->launchCommand{pauseWatch()}
   RESUME_WATCH->launchCommand{resumeWatch()}
   LIFT_ANCHOR->launchCommand{liftAnchor()}
   UPDATE_RADIUS->launchCommand{updateWatchSettings(intent)}
   STOP_WATCH_AND_DISCONNECT->launchCommand{stopWatchAndDisconnect()}
   SWITCH_WATCH_TO_SYSTEM->launchCommand{switchWatchToSystem(true)}
   SWITCH_WATCH_SOURCE_SYSTEM->launchCommand{switchWatchToSystem(false)}
   SWITCH_WATCH_SOURCE_NMEA->launchCommand{switchWatchToNmea()}
   START_PROXY->launchCommand{startProxy()}
   STOP_PROXY->launchCommand{stopProxy(l("Android GPS proxy stopped by user.","用户已关闭 Android GPS 代理。"))}
   DISABLE_DEMO_TO_SYSTEM->launchCommand{switchWatchToSystem(false,true)}
  }
  return START_STICKY
 }

 private fun launchCommand(action:suspend ()->Unit){scope.launch{stateReady.await();commandMutex.withLock{action()}}}

 private suspend fun restoreState(){
  val settings=preferences.settings.first();currentGpsSource=settings.gpsDataSource;alarmSnoozeMinutes=settings.alarmSnoozeMinutes;appLanguage=settings.appLanguage;channels();session=dao.active();session?.let{active->
   engine=alarmEngine(settings);val points=dao.points(active.id).first();restoredDemoElapsed=(if(active.paused)points.lastOrNull()?.timestamp?.minus(active.startedAt) else System.currentTimeMillis()-active.startedAt)?.coerceAtLeast(0L)?:0L
   if(active.centerStatus==AnchorCenterStatus.LEARNING.name){backdownSamples.addAll(points.map{BackdownCenterEstimator.Sample(it.latitude,it.longitude,it.timestamp,it.hdop)});lastSnapshot=engine.learn(active.learningConfig(),SystemClock.elapsedRealtime())}
   else{centerPoints.addAll(points.filter{active.centerResolvedAt==null||it.timestamp>=active.centerResolvedAt}.map{AnchorCenterEstimator.Point(it.latitude,it.longitude)});lastSnapshot=engine.arm(active.config(),SystemClock.elapsedRealtime())}
   if(!active.paused)locks(settings.keepWifiAwake)
  }
  if(session?.paused==false){when(settings.gpsDataSource){GpsDataSource.NMEA->navigation.acquireBackgroundConnection(settings.profile);GpsDataSource.SYSTEM->enableSystemGps();GpsDataSource.DEMO->{enableSystemGps();session?.let{active->if(!demoLocation.status.value.running)demoLocation.start(active.learningReferenceLatitude?:active.anchorLatitude,active.learningReferenceLongitude?:active.anchorLongitude,runCatching{AnchorPlacementMode.valueOf(active.placementMode)}.getOrDefault(AnchorPlacementMode.CENTER_DROP),settings.demoScenario,active.alarmRadiusMeters,settings.demoSpeedMultiplier,initialElapsedMillis=restoredDemoElapsed)else demoLocation.resume()}}}}
  else if(session?.paused==true&&settings.gpsDataSource==GpsDataSource.DEMO){session?.let{active->if(!demoLocation.status.value.running)demoLocation.start(active.learningReferenceLatitude?:active.anchorLatitude,active.learningReferenceLongitude?:active.anchorLongitude,runCatching{AnchorPlacementMode.valueOf(active.placementMode)}.getOrDefault(AnchorPlacementMode.CENTER_DROP),settings.demoScenario,active.alarmRadiusMeters,settings.demoSpeedMultiplier,initialElapsedMillis=restoredDemoElapsed);demoLocation.pause()}}
  if(settings.mockEnabled&&settings.gpsDataSource==GpsDataSource.NMEA){navigation.acquireBackgroundConnection(settings.profile);locks(settings.keepWifiAwake);startProxy()}
  else if(settings.mockEnabled){preferences.setMockEnabled(false);mockGps.stop("A non-NMEA App GPS source is selected — global NMEA proxy disabled.")}
  handleNmeaState(navigation.connectionState.value)
  refreshNotification()
 }

 private suspend fun arm(request:ArmRequest){
  if(session!=null){notifySeparate("Anchor session already open","Pause, resume or lift the current anchor before starting another session.",true);return}
  val c=request.config
  val settings=preferences.settings.first();val now=SystemClock.elapsedRealtime()
  val sourceReady=when(settings.gpsDataSource){GpsDataSource.NMEA->navigation.connectionState.value==NmeaConnectionState.CONNECTED;GpsDataSource.SYSTEM,GpsDataSource.DEMO->enableSystemGps()}
  val latestFix=when(settings.gpsDataSource){GpsDataSource.NMEA->navigation.fix.value;GpsDataSource.SYSTEM,GpsDataSource.DEMO->systemLocation.fix.value}
  val lastFix=if(settings.gpsDataSource==GpsDataSource.NMEA)navigation.diagnostics.value.lastFixElapsed else latestFix?.receivedElapsedRealtime
  val freshFix=sourceReady&&latestFix?.valid==true&&lastFix!=null&&now-lastFix<settings.gpsLossSeconds*1000L
  if(!freshFix){val label=when(settings.gpsDataSource){GpsDataSource.NMEA->"NMEA";GpsDataSource.SYSTEM->"system";GpsDataSource.DEMO->"system origin for Demo"};notifySeparate("Anchor watch not started","A live, current $label GPS fix is required. Existing connections were left unchanged.",false);refreshNotification();releaseIfIdle();return}
  val wallNow=System.currentTimeMillis();val learning=request.placement==AnchorPlacementMode.BACKDOWN
  val entity=AnchorSessionEntity(startedAt=wallNow,anchorLatitude=c.latitude,anchorLongitude=c.longitude,rodeLengthMeters=c.rodeLengthMeters,waterDepthMeters=c.waterDepthMeters,bowRollerHeightMeters=c.bowRollerHeightMeters,gpsAntennaOffsetMeters=c.gpsAntennaOffsetMeters,expectedSwingRadiusMeters=c.alarmRadiusMeters,warningRadiusMeters=c.warningRadiusMeters,alarmRadiusMeters=c.alarmRadiusMeters,placementMode=request.placement.name,centerStatus=if(learning)AnchorCenterStatus.LEARNING.name else AnchorCenterStatus.RESOLVED.name,centerResolvedAt=if(learning)null else wallNow,centerConfidence=if(learning)Confidence.LOW.name else Confidence.HIGH.name,centerSampleCount=if(learning)0 else 1,boatLengthMeters=request.boatLength,rangeMode=request.rangeMode.name,safetyPreset=request.safetyPreset.name,learningReferenceLatitude=if(learning)c.latitude else null,learningReferenceLongitude=if(learning)c.longitude else null,provisionalAnchorLatitude=if(learning)c.latitude else null,provisionalAnchorLongitude=if(learning)c.longitude else null,provisionalRadiusMeters=if(learning)minOf(c.alarmRadiusMeters,maxOf(15.0,c.alarmRadiusMeters*.3)) else null)
  session=entity.copy(id=dao.insertSession(entity));dao.insertEvent(AlarmEventEntity(sessionId=session!!.id,timestamp=wallNow,type=if(learning)"SESSION_STARTED_CENTER_LEARNING" else "SESSION_STARTED"));centerPoints.clear();backdownSamples.clear();silence();lastReportedAlarm=null;engine=alarmEngine(settings);lastSnapshot=if(learning)engine.learn(c,now)else engine.arm(c,now);locks(settings.keepWifiAwake);if(settings.gpsDataSource==GpsDataSource.NMEA)navigation.acquireBackgroundConnection(settings.profile);val initialFix=if(settings.gpsDataSource==GpsDataSource.DEMO)demoLocation.start(c.latitude,c.longitude,request.placement,settings.demoScenario,c.alarmRadiusMeters,settings.demoSpeedMultiplier,now)?:latestFix!! else latestFix!!;updateAlarm(engine.onFix(initialFix,now))
 }

 private suspend fun startProxy()=proxyMutex.withLock{
  val settings=preferences.settings.first();if(settings.gpsDataSource!=GpsDataSource.NMEA){preferences.setMockEnabled(false);proxyPolicy=null;mockGps.stop("Select NMEA GPS before enabling the global proxy.");refreshNotification();releaseIfIdle();return@withLock};navigation.acquireBackgroundConnection(settings.profile);locks(settings.keepWifiAwake);proxyPolicy=MockGpsPolicy(settings.gpsLossSeconds*1000L,settings.mockHz).apply{start(SystemClock.elapsedRealtime())}
  if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){preferences.setMockEnabled(false);proxyPolicy=null;mockGps.stop("GPS proxy was not enabled. Fine location permission is required; Android GPS is using its normal source.");notifySeparate("Android GPS proxy not active","Grant Fine location permission before enabling GPS proxy.",true);refreshNotification();releaseIfIdle();return@withLock}
  val foregroundReady=runCatching{ServiceCompat.startForeground(this,ONGOING,notification("Starting NMEA → Android GPS…",false),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)}.isSuccess
  if(!foregroundReady){preferences.setMockEnabled(false);proxyPolicy=null;mockGps.stop("GPS proxy was not enabled. Android did not allow a location foreground service; Android GPS is using its normal source.");notifySeparate("Android GPS proxy not active","Open the app and grant location permission before enabling GPS proxy.",true);refreshNotification();releaseIfIdle();return@withLock}
  val result=mockGps.start(settings.enhancedMock)
  val enabled=result.state==MockGpsState.ACTIVE
  preferences.setMockEnabled(enabled)
  if(!enabled)proxyPolicy=null
  if(enabled)logEvent("GPS_PROXY_STARTED",result.message) else notifySeparate("Android GPS proxy not active",result.message,true)
  refreshNotification();releaseIfIdle()
 }

 private suspend fun stopProxy(message:String)=proxyMutex.withLock{mockGps.stop(message);preferences.setMockEnabled(false);proxyPolicy=null;notifySeparate("Android GPS restored",message,false);refreshNotification();releaseIfIdle()}

 private suspend fun switchWatchToSystem(disconnectNmea:Boolean,disableDemoMode:Boolean=false){
  val active=session;val settings=preferences.settings.first();val previousSource=settings.gpsDataSource
  if(previousSource==GpsDataSource.SYSTEM){if(disableDemoMode)preferences.save(settings.copy(demoMode=false));if(disconnectNmea)navigation.disconnectAll();refreshNotification();return}
  if(previousSource==GpsDataSource.NMEA&&GpsSourceSafety.blocksSystemGps(settings.mockEnabled,mockGps.status.value.state)){notifySeparate("GPS source not changed","Disable the global NMEA GPS proxy before selecting System GPS. Android mock mode replaces the system fused location source.",true);refreshNotification();return}
  if(active==null||active.paused){currentGpsSource=GpsDataSource.SYSTEM;if(previousSource==GpsDataSource.DEMO)demoLocation.stop();preferences.save(settings.copy(gpsDataSource=GpsDataSource.SYSTEM,mockEnabled=false,demoMode=if(disableDemoMode)false else settings.demoMode));if(previousSource==GpsDataSource.NMEA){if(disconnectNmea)navigation.disconnectAll()else navigation.releaseBackgroundConnection()};refreshNotification();releaseIfIdle();return}
  if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
   notifySeparate("GPS source not changed","Grant precise location permission before switching an active anchor watch to System GPS.",true);refreshNotification();return
  }
  if(!enableSystemGps()){notifySeparate("GPS source not changed","Android did not allow System GPS monitoring. Anchor watch is still using ${previousSource.name}.",true);return}
  val lossMillis=settings.gpsLossSeconds*1000L
  val systemFix=withTimeoutOrNull(10_000){systemLocation.fix.filterNotNull().filter{it.valid&&SystemClock.elapsedRealtime()-it.receivedElapsedRealtime<lossMillis}.first()}
  if(systemFix==null){
   systemLocation.setBackgroundEnabled(false)
   notifySeparate("GPS source not changed","No fresh System GPS fix was available. Anchor watch is still using ${previousSource.name}.",true);refreshNotification();return
  }
  currentGpsSource=GpsDataSource.SYSTEM;nmeaLossAnnounced=false
  updateAlarm(engine.onFix(systemFix,SystemClock.elapsedRealtime()))
  dao.insertEvent(AlarmEventEntity(sessionId=active.id,timestamp=System.currentTimeMillis(),type="WATCH_GPS_SOURCE_CHANGED",detail="${previousSource.name}_TO_SYSTEM"))
  preferences.save(settings.copy(gpsDataSource=GpsDataSource.SYSTEM,mockEnabled=false,demoMode=if(disableDemoMode)false else settings.demoMode))
  if(previousSource==GpsDataSource.NMEA){if(disconnectNmea)navigation.disconnectAll()else navigation.releaseBackgroundConnection()}
  if(previousSource==GpsDataSource.DEMO)demoLocation.stop()
  notifySeparate("Anchor watch switched to System GPS",if(previousSource==GpsDataSource.DEMO)"Demo stopped only after a fresh System GPS position was acquired." else if(disconnectNmea)"NMEA was disconnected only after a fresh System GPS position was acquired." else "A fresh, non-mock System GPS position was acquired before the watch switched.",false)
  refreshNotification()
 }

 private suspend fun switchWatchToNmea(){
  val active=session;val settings=preferences.settings.first()
  if(settings.gpsDataSource==GpsDataSource.NMEA){refreshNotification();return}
  val previousSource=settings.gpsDataSource
  if(active==null||active.paused){currentGpsSource=GpsDataSource.NMEA;if(previousSource==GpsDataSource.DEMO)demoLocation.stop();preferences.save(settings.copy(gpsDataSource=GpsDataSource.NMEA));systemLocation.setBackgroundEnabled(false);refreshNotification();releaseIfIdle();return}
  navigation.acquireBackgroundConnection(settings.profile)
  val lossMillis=settings.gpsLossSeconds*1000L
  val nmeaFix=withTimeoutOrNull(10_000){
   navigation.connectionState.first{it==NmeaConnectionState.CONNECTED}
   navigation.fix.filterNotNull().filter{it.valid&&SystemClock.elapsedRealtime()-it.receivedElapsedRealtime<lossMillis}.first()
  }
  if(nmeaFix==null){navigation.releaseBackgroundConnection();notifySeparate("GPS source not changed","No fresh NMEA position was available. Anchor watch is still using ${previousSource.name}.",true);refreshNotification();return}
  currentGpsSource=GpsDataSource.NMEA;nmeaLossAnnounced=false
  updateAlarm(engine.onFix(nmeaFix));systemLocation.setBackgroundEnabled(false)
  if(previousSource==GpsDataSource.DEMO)demoLocation.stop()
  dao.insertEvent(AlarmEventEntity(sessionId=active.id,timestamp=System.currentTimeMillis(),type="WATCH_GPS_SOURCE_CHANGED",detail="${previousSource.name}_TO_NMEA"))
  preferences.save(settings.copy(gpsDataSource=GpsDataSource.NMEA))
  notifySeparate("Anchor watch switched to NMEA GPS","A fresh NMEA position was verified before the watch switched.",false);refreshNotification()
 }

 private suspend fun stopWatchAndDisconnect(){
  val wasActive=session!=null
  pauseWatch()
  navigation.disconnectAll()
  if(wasActive)notifySeparate("Anchor watch paused","This anchor session and its centre were kept; NMEA was disconnected.",false)
 }

 private suspend fun handleNmeaState(state:NmeaConnectionState){
  val watchingNmea=session?.paused==false&&currentGpsSource==GpsDataSource.NMEA
  val lost=state==NmeaConnectionState.ERROR||state==NmeaConnectionState.DISCONNECTED||state==NmeaConnectionState.RECONNECTING
  if(watchingNmea&&lost&&!nmeaLossAnnounced){
   nmeaLossAnnounced=true
   logEvent("NMEA_CONNECTION_LOST",state.name)
   notifySeparate("NMEA connection lost","Anchor watch is still active and reconnecting. A GPS-data-loss alarm will follow if valid NMEA positions do not return.",true)
  }
  if(!watchingNmea)nmeaLossAnnounced=false
  refreshNotification()
 }

 private suspend fun handleFix(fix:NavigationFix,source:GpsDataSource){
  if(source==GpsDataSource.NMEA&&fix.valid&&nmeaLossAnnounced&&session?.paused==false){
   nmeaLossAnnounced=false
   logEvent("NMEA_CONNECTION_RESTORED","")
   notifySeparate("NMEA GPS restored","Valid NMEA positions are flowing again; anchor watch remained active.",false)
  }
  val activeSession=session
  if(activeSession!=null&&!activeSession.paused){
   val snapshot=engine.onFix(fix);var refined=false
   if(fix.valid&&System.currentTimeMillis()-lastTrack>=1000){
    lastTrack=System.currentTimeMillis();dao.insertPoint(TrackPointEntity(sessionId=activeSession.id,timestamp=lastTrack,latitude=fix.latitude,longitude=fix.longitude,distanceFromAnchor=snapshot.distanceMeters?:0.0,sog=fix.sogKnots,cog=fix.cogTrueDegrees,heading=fix.headingTrueDegrees,hdop=fix.hdop))
    if(activeSession.centerStatus==AnchorCenterStatus.LEARNING.name){
     backdownSamples+=BackdownCenterEstimator.Sample(fix.latitude,fix.longitude,lastTrack,fix.hdop)
     val estimate=backdownEstimator.provisionalEstimate(backdownSamples)
     if(estimate!=null){
      val learning=session?.takeIf{it.centerStatus==AnchorCenterStatus.LEARNING.name}
      if(learning!=null){val updated=learning.copy(provisionalAnchorLatitude=estimate.latitude,provisionalAnchorLongitude=estimate.longitude,provisionalRadiusMeters=estimate.uncertaintyRadiusMeters,centerConfidence=estimate.confidence.name,centerSampleCount=estimate.sampleCount);session=updated;dao.updateSession(updated)}
     }
     if(estimate?.confidence==Confidence.HIGH)refined=resolveBackdownCenter(estimate,fix)
    }else if(activeSession.placementMode==AnchorPlacementMode.BACKDOWN.name){
     centerPoints+=AnchorCenterEstimator.Point(fix.latitude,fix.longitude);refined=refineEstimatedCenter(fix)
    }
   }
   if(!refined)updateAlarm(snapshot)
  }
  if(mockGps.status.value.state==MockGpsState.ACTIVE&&fix.valid){val now=SystemClock.elapsedRealtime();if(proxyPolicy?.onValidFix(now)==true){val result=mockGps.publish(fix);if(result.isFailure){logEvent("MOCK_GPS_FAILED",result.exceptionOrNull()?.message?:"");stopProxy("NMEA injection failed — Android GPS restored.")}}}
 }

 private suspend fun resolveBackdownCenter(estimate:BackdownAnchorEstimate,fix:NavigationFix):Boolean{
  val current=session?.takeIf{it.centerStatus==AnchorCenterStatus.LEARNING.name}?:return false
  val wallNow=System.currentTimeMillis();val now=SystemClock.elapsedRealtime()
  val updated=current.copy(anchorLatitude=estimate.latitude,anchorLongitude=estimate.longitude,centerStatus=AnchorCenterStatus.RESOLVED.name,centerResolvedAt=wallNow,centerConfidence=estimate.confidence.name,centerSampleCount=estimate.sampleCount,provisionalAnchorLatitude=null,provisionalAnchorLongitude=null,provisionalRadiusMeters=null,alarmSnoozedUntil=null)
  session=updated;dao.updateSession(updated);centerPoints.clear();centerPoints+=AnchorCenterEstimator.Point(fix.latitude,fix.longitude)
  engine=alarmEngine(preferences.settings.first());engine.arm(updated.config(),now);updateAlarm(engine.onFix(fix))
  dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=wallNow,type="ANCHOR_CENTER_RESOLVED",detail="${estimate.confidence}:${estimate.distanceMeters.toInt()}m:${estimate.sampleCount}"))
  notifySeparate("Anchor centre resolved","The back-down track now has enough high-confidence data. Radius monitoring is fully armed.",false)
  return true
 }

 private suspend fun refineEstimatedCenter(fix:NavigationFix):Boolean{
  val current=session?:return false;val now=SystemClock.elapsedRealtime();if(current.placementMode!=AnchorPlacementMode.BACKDOWN.name||current.centerStatus!=AnchorCenterStatus.RESOLVED.name||centerPoints.size<30||now-lastCenterUpdate<10_000)return false
  lastCenterUpdate=now
  val estimate=centerEstimator.estimate(centerPoints,null)?.takeIf{it.confidence==Confidence.HIGH}?:return false
  val shift=AnchorGeometry.distanceMeters(current.anchorLatitude,current.anchorLongitude,estimate.latitude,estimate.longitude)
  if(shift<1.0||shift>minOf(15.0,maxOf(5.0,current.alarmRadiusMeters*.25)))return false
  val updated=current.copy(anchorLatitude=estimate.latitude,anchorLongitude=estimate.longitude,centerConfidence=estimate.confidence.name,centerSampleCount=estimate.sampleCount)
  session=updated;dao.updateSession(updated);val settings=preferences.settings.first();engine=alarmEngine(settings);engine.arm(updated.config(),now);updateAlarm(engine.onFix(fix));dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=System.currentTimeMillis(),type="ANCHOR_CENTER_REFINED",detail="${estimate.confidence}:${estimate.radiusMeters.toInt()}m"))
  return true
 }

 private suspend fun watchdog(){
  val now=SystemClock.elapsedRealtime();if(session?.paused==false&&currentGpsSource==GpsDataSource.DEMO)demoLocation.tick(now)?.let{handleFix(it,GpsDataSource.DEMO)};session?.takeIf{!it.paused}?.let{updateAlarm(engine.tick(now))}
  if(mockGps.status.value.state==MockGpsState.ACTIVE&&proxyPolicy?.isStale(now)==true){mockGps.stale();proxyPolicy=null;preferences.setMockEnabled(false);notifySeparate("NMEA GPS lost","Android GPS restored to its normal source.",true);logEvent("GPS_PROXY_STALE","");releaseIfIdle()}
  refreshNotification()
 }

 private suspend fun batteryWatchdog(){
  if(session?.paused!=false&&mockGps.status.value.state!=MockGpsState.ACTIVE)return
  val percent=getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
  if(percent in 0..15&&!lowBatteryReported){lowBatteryReported=true;notifySeparate("Low battery: $percent%","Connect this monitoring device to reliable power.",true);logEvent("LOW_BATTERY","$percent")}
  if(percent>20)lowBatteryReported=false
 }

 private suspend fun updateAlarm(snapshot:AlarmSnapshot){
  val now=System.currentTimeMillis();var active=session
  val settled=active
  if(snapshot.state!=AlarmState.ALARM&&snapshot.state!=AlarmState.WARNING&&snapshot.state!=AlarmState.ACKNOWLEDGED&&settled?.alarmSnoozedUntil!=null){val updated=settled.copy(alarmSnoozedUntil=null);active=updated;session=updated;dao.updateSession(updated)}
  val expired=active
  if(expired?.alarmSnoozedUntil?.let{it<=now}==true){val updated=expired.copy(alarmSnoozedUntil=null);active=updated;session=updated;dao.updateSession(updated)}
  lastSnapshot=snapshot;val critical=snapshot.state==AlarmState.ALARM
  if(AlarmReminderPolicy.shouldSound(snapshot,active?.paused?:true,active?.alarmSnoozedUntil,now))sound() else silence()
  if(critical&&snapshot.type!=lastReportedAlarm){lastReportedAlarm=snapshot.type;logEvent("ALARM_TRIGGERED",snapshot.type?.name?:"")}
  if(!critical&&snapshot.state!=AlarmState.ACKNOWLEDGED)lastReportedAlarm=null
  refreshNotification(snapshot)
 }
 private fun refreshNotification(snapshot:AlarmSnapshot?=lastSnapshot){
  val proxy=mockGps.status.value;val active=session;val now=System.currentTimeMillis();val snoozed=AlarmReminderPolicy.isSnoozed(active?.alarmSnoozedUntil,now);val alarmCondition=active?.paused==false&&snapshot?.type!=null&&(snapshot.state==AlarmState.ALARM||snapshot.state==AlarmState.ACKNOWLEDGED);val remaining=active?.alarmSnoozedUntil?.let{((it-now+59_999)/60_000).coerceAtLeast(1)}
  val base=when{
   snapshot?.type==AlarmType.GPS_DATA_LOST&&active?.paused==false->l("GPS DATA LOST","GPS 数据丢失")
   snapshot?.type==AlarmType.ANCHOR_RADIUS_EXCEEDED&&active?.paused==false->l("ANCHOR ALARM ${snapshot.distanceMeters?.toInt()} m","锚警：距离 ${snapshot.distanceMeters?.toInt()} 米")
   active?.paused==true&&proxy.state==MockGpsState.ACTIVE->l("Anchor session paused • NMEA GPS proxy active","锚泊监控已暂停 · NMEA GPS 代理运行中")
   active?.paused==true->l("Anchor session paused","锚泊监控已暂停")
   active?.centerStatus==AnchorCenterStatus.LEARNING.name->l("Watch active • temporary boundary armed • ${backdownSamples.size} fixes","锚警监控中 · 临时边界已布防 · ${backdownSamples.size} 个定位点")
   active!=null&&currentGpsSource==GpsDataSource.NMEA&&nmeaLossAnnounced->l("Watch active • NMEA connection lost • reconnecting","锚警监控中 · NMEA 连接丢失 · 正在重连")
   active!=null&&currentGpsSource==GpsDataSource.SYSTEM->l("Watch ${snapshot?.distanceMeters?.toInt()?:"--"} m • SYSTEM GPS","锚警 ${snapshot?.distanceMeters?.toInt()?:"--"} 米 · 系统 GPS")
   active!=null&&currentGpsSource==GpsDataSource.DEMO->l("Watch ${snapshot?.distanceMeters?.toInt()?:"--"} m • DEMO ${demoLocation.status.value.scenario.name}","锚警 ${snapshot?.distanceMeters?.toInt()?:"--"} 米 · 演示 ${demoLocation.status.value.scenario.name}")
   active!=null->l("Watch ${snapshot?.distanceMeters?.toInt()?:"--"} m • NMEA ${navigation.connectionState.value.name}","锚警 ${snapshot?.distanceMeters?.toInt()?:"--"} 米 · NMEA ${navigation.connectionState.value.name}")
   proxy.state==MockGpsState.ACTIVE->l("NMEA → Android GPS active • ${proxy.publishedFixes} fixes","NMEA → Android GPS 已开启 · ${proxy.publishedFixes} 个定位点")
   else->l("Safety monitor idle","安全监控待命")
  }
  val text=if(snoozed&&alarmCondition)l("$base • snoozed, remind in ${remaining}m","$base · 已暂停响铃，${remaining} 分钟后再次提醒") else base
  getSystemService(NotificationManager::class.java).notify(ONGOING,notification(text,alarmCondition,snoozed))
 }
 private fun notification(text:String,alarm:Boolean,silent:Boolean=false):Notification{val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT);val snooze=PendingIntent.getService(this,1,Intent(this,AnchorForegroundService::class.java).setAction(SNOOZE),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT);return NotificationCompat.Builder(this,if(alarm)ALARM_CH else STATUS_CH).setSmallIcon(android.R.drawable.ic_dialog_map).setContentTitle(if(alarm)l("Anchor by Yokuli alarm","Yokuli 锚警") else if(mockGps.status.value.state==MockGpsState.ACTIVE)l("NMEA GPS Proxy","NMEA GPS 代理") else l("Anchor by Yokuli active","Yokuli 锚警运行中")).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(silent).setPriority(if(alarm)NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW).setCategory(if(alarm)NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_SERVICE).apply{if(alarm)addAction(0,l("SNOOZE ${alarmSnoozeMinutes} MIN","${alarmSnoozeMinutes} 分钟后提醒"),snooze)}.build()}
 private fun notifySeparate(title:String,text:String,high:Boolean){getSystemService(NotificationManager::class.java).notify(EVENT,NotificationCompat.Builder(this,if(high)ALARM_CH else STATUS_CH).setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(serviceMessage(title)).setContentText(serviceMessage(text)).setAutoCancel(true).setPriority(if(high)NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT).build())}

 private fun sound(){if(alarmPlayer?.isPlaying==true)return;runCatching{alarmPlayer=MediaPlayer().apply{setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());setDataSource(this@AnchorForegroundService,android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI);isLooping=true;prepare();start()};getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createWaveform(longArrayOf(0,800,400),0))}}
 private fun silence(){alarmPlayer?.release();alarmPlayer=null;getSystemService(Vibrator::class.java).cancel()}
 private suspend fun snoozeAlarm(){
  val current=session?:return
  if(current.paused||lastSnapshot?.type==null)return
  val minutes=preferences.settings.first().alarmSnoozeMinutes
  val until=AlarmReminderPolicy.snoozeUntil(System.currentTimeMillis(),minutes)
  val updated=current.copy(alarmSnoozedUntil=until);dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=System.currentTimeMillis(),type="ALARM_SNOOZED",detail="${minutes}m"));session=updated;dao.updateSession(updated);lastSnapshot=engine.acknowledge();silence();refreshNotification()
 }
 private fun locks(keepWifi:Boolean){if(wake==null)wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"anchorwatch:monitor").apply{setReferenceCounted(false);acquire()};if(keepWifi&&wifi==null)wifi=getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"anchorwatch:nmea").apply{setReferenceCounted(false);acquire()}}
 private suspend fun pauseWatch(){
  val current=session?:return
  if(current.paused)return
  val updated=current.copy(paused=true,alarmSnoozedUntil=null);session=updated;dao.updateSession(updated);dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=System.currentTimeMillis(),type="SESSION_PAUSED"))
  if(currentGpsSource==GpsDataSource.DEMO)demoLocation.pause()
  engine.stop();lastSnapshot=AlarmSnapshot(AlarmState.STOPPED);lastReportedAlarm=null;nmeaLossAnnounced=false;silence();getSystemService(NotificationManager::class.java).cancel(EVENT);refreshNotification();releaseIfIdle()
 }
 private suspend fun resumeWatch(){
  val current=(session?:dao.active())?:return
  if(!current.paused){session=current;refreshNotification();return}
  val settings=preferences.settings.first();locks(settings.keepWifiAwake)
  val lossMillis=settings.gpsLossSeconds*1000L
  val fix=when(settings.gpsDataSource){
   GpsDataSource.NMEA->{navigation.acquireBackgroundConnection(settings.profile);withTimeoutOrNull(10_000){navigation.connectionState.first{it==NmeaConnectionState.CONNECTED};navigation.fix.filterNotNull().filter{it.valid&&SystemClock.elapsedRealtime()-it.receivedElapsedRealtime<lossMillis}.first()}}
   GpsDataSource.SYSTEM->{if(!enableSystemGps())null else withTimeoutOrNull(10_000){systemLocation.fix.filterNotNull().filter{it.valid&&SystemClock.elapsedRealtime()-it.receivedElapsedRealtime<lossMillis}.first()}}
   GpsDataSource.DEMO->{if(!enableSystemGps())null else demoLocation.resume()?:demoLocation.start(current.learningReferenceLatitude?:current.anchorLatitude,current.learningReferenceLongitude?:current.anchorLongitude,runCatching{AnchorPlacementMode.valueOf(current.placementMode)}.getOrDefault(AnchorPlacementMode.CENTER_DROP),settings.demoScenario,current.alarmRadiusMeters,settings.demoSpeedMultiplier)}
  }
  if(fix==null){notifySeparate("Anchor watch remains paused","A fresh ${when(settings.gpsDataSource){GpsDataSource.NMEA->"NMEA";GpsDataSource.SYSTEM->"System";GpsDataSource.DEMO->"Demo"}} GPS position is required before resuming.",true);session=current;releaseIfIdle();return}
  val resumedAt=SystemClock.elapsedRealtime();val updated=current.copy(paused=false,alarmSnoozedUntil=null);session=updated;dao.updateSession(updated);engine=alarmEngine(settings);lastSnapshot=if(updated.centerStatus==AnchorCenterStatus.LEARNING.name)engine.learn(updated.learningConfig(),resumedAt)else engine.arm(updated.config(),resumedAt);updateAlarm(engine.onFix(fix,resumedAt));dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=System.currentTimeMillis(),type="SESSION_RESUMED"));notifySeparate("Anchor watch resumed","The existing anchor centre, track and alarm range were preserved.",false)
 }
 private suspend fun liftAnchor(){
  val current=session?:dao.active()?:return;val now=System.currentTimeMillis();dao.insertEvent(AlarmEventEntity(sessionId=current.id,timestamp=now,type="ANCHOR_LIFTED"));dao.updateSession(current.copy(active=false,paused=false,endedAt=now,alarmSnoozedUntil=null));if(currentGpsSource==GpsDataSource.DEMO)demoLocation.stop();session=null;centerPoints.clear();backdownSamples.clear();engine.stop();lastSnapshot=null;lastReportedAlarm=null;nmeaLossAnnounced=false;silence();getSystemService(NotificationManager::class.java).cancel(EVENT);refreshNotification();releaseIfIdle()
 }
 private suspend fun updateWatchSettings(intent:Intent){
  val current=session?:return;val wasAlarm=lastSnapshot?.state==AlarmState.ALARM;val alarm=intent.getDoubleExtra("alarm",current.alarmRadiusMeters).takeIf{it>0}?:return;val warning=maxOf(alarm*.8,alarm-10).coerceAtMost(alarm-.1)
  val settings=preferences.settings.first();val snoozedUntil=if(wasAlarm||lastSnapshot?.state==AlarmState.ACKNOWLEDGED)AlarmReminderPolicy.snoozeUntil(System.currentTimeMillis(),settings.alarmSnoozeMinutes)else null
  val updated=current.copy(alarmRadiusMeters=alarm,warningRadiusMeters=warning,expectedSwingRadiusMeters=alarm,waterDepthMeters=intent.getDoubleExtra("depth",Double.NaN).takeUnless{it.isNaN()},rodeLengthMeters=intent.getDoubleExtra("rode",current.rodeLengthMeters),boatLengthMeters=intent.getDoubleExtra("boatLength",Double.NaN).takeUnless{it.isNaN()},rangeMode=enumExtra(intent,"rangeMode",runCatching{AnchorRangeMode.valueOf(current.rangeMode)}.getOrDefault(AnchorRangeMode.BASIC)).name,safetyPreset=enumExtra(intent,"safetyPreset",runCatching{AnchorSafetyPreset.valueOf(current.safetyPreset)}.getOrDefault(AnchorSafetyPreset.BALANCED)).name,alarmSnoozedUntil=snoozedUntil)
  if(currentGpsSource==GpsDataSource.DEMO)demoLocation.reconfigure(settings.demoScenario,alarm,settings.demoSpeedMultiplier)
  session=updated;dao.updateSession(updated);silence();lastReportedAlarm=null
  if(!updated.paused){val resetAt=SystemClock.elapsedRealtime();engine=alarmEngine(settings);lastSnapshot=if(updated.centerStatus==AnchorCenterStatus.LEARNING.name)engine.learn(updated.learningConfig(),resetAt)else engine.arm(updated.config(),resetAt);val fix=when(settings.gpsDataSource){GpsDataSource.NMEA->navigation.fix.value;GpsDataSource.SYSTEM->systemLocation.fix.value;GpsDataSource.DEMO->demoLocation.fix.value};val lastFix=if(settings.gpsDataSource==GpsDataSource.NMEA)navigation.diagnostics.value.lastFixElapsed else fix?.receivedElapsedRealtime;if(fix?.valid==true&&lastFix!=null&&resetAt-lastFix<settings.gpsLossSeconds*1000L)updateAlarm(engine.onFix(fix,resetAt))}
  dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=System.currentTimeMillis(),type="ALARM_RANGE_CHANGED",detail="${current.alarmRadiusMeters.toInt()}m_TO_${alarm.toInt()}m"));if(wasAlarm&&lastSnapshot?.state!=AlarmState.ALARM)dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=System.currentTimeMillis(),type="ALARM_CLEARED_BY_RANGE_CHANGE",detail="${alarm.toInt()}m"));notifySeparate("Anchor range updated","Alarm radius is now ${alarm.toInt()} m for this session.",false);refreshNotification();releaseIfIdle()
 }
 private fun enableSystemGps():Boolean{if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return false;val ready=runCatching{ServiceCompat.startForeground(this,ONGOING,notification("System GPS anchor monitoring…",false),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)}.isSuccess;if(ready)systemLocation.setBackgroundEnabled(true);return ready}
 private fun releaseIfIdle(){if(session?.paused!=false&&mockGps.status.value.state!=MockGpsState.ACTIVE&&!armPending){wake?.takeIf{it.isHeld}?.release();wifi?.takeIf{it.isHeld}?.release();wake=null;wifi=null;navigation.releaseBackgroundConnection();systemLocation.setBackgroundEnabled(false);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}}
 private suspend fun logEvent(type:String,detail:String){session?.let{dao.insertEvent(AlarmEventEntity(sessionId=it.id,timestamp=System.currentTimeMillis(),type=type,detail=detail))}}
 private fun alarmEngine(settings:com.yokuli.anchorwatch.data.preferences.AppSettings)=AlarmEngine(settings.alarmPersistenceSeconds*1000L,gpsLossMillis=settings.gpsLossSeconds*1000L)
 private fun cleanup(){silence();wake?.takeIf{it.isHeld}?.release();wifi?.takeIf{it.isHeld}?.release();wake=null;wifi=null}
 override fun onDestroy(){scope.cancel();navigation.releaseBackgroundConnection();systemLocation.setBackgroundEnabled(false);if(mockGps.status.value.state==MockGpsState.ACTIVE||mockGps.status.value.state==MockGpsState.STARTING)runBlocking(Dispatchers.IO){withTimeoutOrNull(2000){mockGps.stop()}};cleanup();super.onDestroy()};override fun onBind(intent:Intent?)=null
 private fun channels(){getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(NotificationChannel(STATUS_CH,l("Anchor and GPS status","锚警与 GPS 状态"),NotificationManager.IMPORTANCE_LOW),NotificationChannel(ALARM_CH,l("Anchor alarms with snooze","带稍后提醒的锚警"),NotificationManager.IMPORTANCE_HIGH).apply{enableVibration(true)}))}
 private fun l(english:String,chinese:String)=localized(appLanguage,english,chinese)
 private fun serviceMessage(message:String):String{
  if(!appLanguage.usesChinese())return message
  return when{
   message=="Anchor session already open"->"已有锚泊会话"
   message=="Pause, resume or lift the current anchor before starting another session."->"开始新会话前，请暂停、继续或结束当前锚泊。"
   message=="Anchor watch not started"->"锚警未启动"
   message.startsWith("A live, current ")->"需要实时且新鲜的 GPS 定位才能启动锚警；现有连接没有改变。"
   message=="Android GPS proxy not active"->"Android GPS 代理未开启"
   message.startsWith("Grant Fine location permission")->"开启 GPS 代理前请授予精确位置权限。"
   message.startsWith("Open the app and grant location permission")->"请打开应用并授予位置权限，然后再开启 GPS 代理。"
   message=="Android GPS restored"->"Android GPS 已恢复"
   message=="GPS source not changed"->"GPS 数据源未切换"
   message.startsWith("Disable the global NMEA GPS proxy")->"选择系统 GPS 前请关闭全局 NMEA GPS 代理。"
   message.startsWith("Grant precise location permission")->"当前锚警切换到系统 GPS 前请授予精确位置权限。"
   message.startsWith("Android did not allow System GPS monitoring")->"Android 不允许系统 GPS 监控；锚警仍使用原数据源。"
   message.startsWith("No fresh System GPS fix")->"没有可用的新鲜系统 GPS 定位；锚警仍使用原数据源。"
   message=="Anchor watch switched to System GPS"->"锚警已切换到系统 GPS"
   message.startsWith("Demo stopped only after")->"获取新的系统 GPS 位置后，演示模式才安全停止。"
   message.startsWith("NMEA was disconnected only after")->"获取新的系统 GPS 位置后，NMEA 才安全断开。"
   message.startsWith("A fresh, non-mock System GPS")->"确认新的非模拟系统 GPS 位置后，锚警已安全切换。"
   message.startsWith("No fresh NMEA position")->"没有可用的新鲜 NMEA 位置；锚警仍使用原数据源。"
   message=="Anchor watch switched to NMEA GPS"->"锚警已切换到 NMEA GPS"
   message=="A fresh NMEA position was verified before the watch switched."->"确认新的 NMEA 位置后，锚警才完成切换。"
   message=="Anchor watch paused"->"锚警已暂停"
   message=="This anchor session and its centre were kept; NMEA was disconnected."->"本次锚泊会话及其中心已保留，NMEA 已断开。"
   message=="NMEA connection lost"->"NMEA 连接丢失"
   message.startsWith("Anchor watch is still active and reconnecting")->"锚警仍在运行并尝试重连；如果有效 NMEA 位置没有恢复，随后将触发 GPS 数据丢失报警。"
   message=="NMEA GPS restored"->"NMEA GPS 已恢复"
   message.startsWith("Valid NMEA positions are flowing again")->"有效 NMEA 位置已恢复，锚警始终保持运行。"
   message=="Anchor centre resolved"->"锚点中心已确定"
   message.startsWith("The back-down track now has enough")->"倒车轨迹已有足够的高置信度数据，范围监控现已完全布防。"
   message=="NMEA GPS lost"->"NMEA GPS 丢失"
   message.startsWith("Android GPS restored to its normal source")->"Android GPS 已恢复到正常系统数据源。"
   message.startsWith("Low battery:")->message.replace("Low battery:","电量低：")
   message=="Connect this monitoring device to reliable power."->"请将监控设备接入可靠电源。"
   message=="Anchor watch remains paused"->"锚警仍处于暂停状态"
   message.startsWith("A fresh ")&&message.endsWith(" GPS position is required before resuming.")->"继续锚警前需要新的有效 GPS 位置。"
   message=="Anchor watch resumed"->"锚警已继续"
   message=="The existing anchor centre, track and alarm range were preserved."->"原锚点中心、轨迹和报警范围均已保留。"
   message=="Anchor range updated"->"锚警范围已更新"
   message.startsWith("Alarm radius is now ")->message.replace("Alarm radius is now ","本次会话的报警半径现为 ").replace(" m for this session."," 米。")
   else->message
  }
 }
 private fun AnchorSessionEntity.config()=AnchorConfig(anchorLatitude,anchorLongitude,rodeLengthMeters,waterDepthMeters,bowRollerHeightMeters,gpsAntennaOffsetMeters,warningRadiusMeters,alarmRadiusMeters)
 private fun AnchorSessionEntity.learningConfig()=AnchorConfig(learningReferenceLatitude?:anchorLatitude,learningReferenceLongitude?:anchorLongitude,rodeLengthMeters,waterDepthMeters,bowRollerHeightMeters,gpsAntennaOffsetMeters,warningRadiusMeters,alarmRadiusMeters)
 private inline fun <reified T:Enum<T>>enumExtra(intent:Intent,key:String,default:T)=runCatching{enumValueOf<T>(intent.getStringExtra(key)?:default.name)}.getOrDefault(default)
 companion object{const val ARM="com.yokuli.anchorwatch.ARM";const val ACK="com.yokuli.anchorwatch.ACK";const val SNOOZE="com.yokuli.anchorwatch.SNOOZE";const val STOP_WATCH="com.yokuli.anchorwatch.STOP_WATCH";const val PAUSE_WATCH="com.yokuli.anchorwatch.PAUSE_WATCH";const val RESUME_WATCH="com.yokuli.anchorwatch.RESUME_WATCH";const val LIFT_ANCHOR="com.yokuli.anchorwatch.LIFT_ANCHOR";const val UPDATE_RADIUS="com.yokuli.anchorwatch.UPDATE_RADIUS";const val STOP_WATCH_AND_DISCONNECT="com.yokuli.anchorwatch.STOP_WATCH_AND_DISCONNECT";const val SWITCH_WATCH_TO_SYSTEM="com.yokuli.anchorwatch.SWITCH_WATCH_TO_SYSTEM";const val SWITCH_WATCH_SOURCE_SYSTEM="com.yokuli.anchorwatch.SWITCH_WATCH_SOURCE_SYSTEM";const val SWITCH_WATCH_SOURCE_NMEA="com.yokuli.anchorwatch.SWITCH_WATCH_SOURCE_NMEA";const val DISABLE_DEMO_TO_SYSTEM="com.yokuli.anchorwatch.DISABLE_DEMO_TO_SYSTEM";const val START_PROXY="com.yokuli.anchorwatch.START_PROXY";const val STOP_PROXY="com.yokuli.anchorwatch.STOP_PROXY";const val STATUS_CH="anchor_status";const val ALARM_CH="anchor_alarm";const val ONGOING=42;const val EVENT=43}
}
