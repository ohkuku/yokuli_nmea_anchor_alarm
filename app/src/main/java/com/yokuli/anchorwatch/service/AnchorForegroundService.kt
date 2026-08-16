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
import com.yokuli.anchorwatch.data.AlarmUiRepository
import com.yokuli.anchorwatch.data.database.*
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.data.sharing.NmeaOutputMux
import com.yokuli.anchorwatch.data.sharing.NmeaSharingServer
import com.yokuli.anchorwatch.data.sharing.NetworkAddressProvider
import com.yokuli.anchorwatch.data.sharing.NmeaSelfLoopPolicy
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
 @Inject lateinit var navigation:NavigationRepository;@Inject lateinit var dao:AnchorDao;@Inject lateinit var preferences:SettingsRepository;@Inject lateinit var mockGps:GlobalMockLocationManager;@Inject lateinit var systemLocation:SystemLocationRepository;@Inject lateinit var demoLocation:DemoLocationRepository;@Inject lateinit var phoneHeading:PhoneHeadingRepository;@Inject lateinit var alarmUi:AlarmUiRepository;@Inject lateinit var sharingServer:NmeaSharingServer;@Inject lateinit var outputMux:NmeaOutputMux;@Inject lateinit var networkAddresses:NetworkAddressProvider
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);private val commandMutex=Mutex();private val proxyMutex=Mutex();private val stateReady=CompletableDeferred<Unit>();private var wake:PowerManager.WakeLock?=null;private var wifi:WifiManager.WifiLock?=null;private var alarmPlayer:MediaPlayer?=null;private var engine=AlarmEngine();private var session:AnchorSessionEntity?=null;private var lastSnapshot:AlarmSnapshot?=null;private var lastTrack=0L;private var proxyPolicy:MockGpsPolicy?=null;private var lowBatteryReported=false;private var lastReportedAlarm:AlarmType?=null;private var nmeaLossAnnounced=false;private var positionDegradedSince:Long?=null;private var positionDegradedReason:String?=null;private var currentGpsSource=GpsDataSource.SYSTEM;private var alarmSnoozeMinutes=5;private var selectedAlarmSound=AlarmSound.SYSTEM_ALARM;private var customAlarmSoundUri:String?=null;private var restoredDemoElapsed=0L;private val backdownEstimator=BackdownCenterEstimator();private val candidateDriftDetector=CandidateDriftDetector();private val positionIntegrity=PositionIntegrityFilter();private val sharingPositionIntegrity=PositionIntegrityFilter(maximumAccuracyMeters=30.0);private val backdownSamples=mutableListOf<BackdownCenterEstimator.Sample>();@Volatile private var armPending=false;@Volatile private var appLanguage=AppLanguage.SYSTEM;@Volatile private var sharingEnabled=false;@Volatile private var sharingSource=GpsDataSource.SYSTEM

 private data class SourcedFix(val source:GpsDataSource,val fix:NavigationFix)
 private data class ArmRequest(val config:AnchorConfig,val placement:AnchorPlacementMode,val rangeMode:AnchorRangeMode,val safetyPreset:AnchorSafetyPreset,val boatLength:Double?,val positionSource:GpsDataSource?,val centerSource:AnchorCenterSource,val usePhoneHeading:Boolean)

 override fun onCreate(){
  super.onCreate();channels();ServiceCompat.startForeground(this,ONGOING,notification(l("Starting safety monitor…","正在启动安全监控…"),false),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
  scope.launch{commandMutex.withLock{try{restoreState()}finally{stateReady.complete(Unit)}}}
  scope.launch{combine(preferences.settings.map{it.gpsDataSource}.distinctUntilChanged(),navigation.fix,systemLocation.fix){source,nmea,system->currentGpsSource=source;when(source){GpsDataSource.NMEA->nmea?.let{SourcedFix(source,it)};GpsDataSource.SYSTEM->system?.let{SourcedFix(source,it)};GpsDataSource.DEMO->null}}.filterNotNull().collect{value->commandMutex.withLock{handleFix(value.fix,value.source)}}}
  scope.launch{navigation.connectionState.collect{state->commandMutex.withLock{handleNmeaState(state)}}}
  scope.launch{preferences.settings.map{it.alarmSnoozeMinutes}.distinctUntilChanged().collect{alarmSnoozeMinutes=it}}
  scope.launch{preferences.settings.map{it.alarmSound to it.customAlarmSoundUri}.distinctUntilChanged().collect{(sound,uri)->selectedAlarmSound=sound;customAlarmSoundUri=uri}}
  scope.launch{preferences.settings.map{it.appLanguage}.distinctUntilChanged().collect{appLanguage=it;channels();refreshNotification()}}
  scope.launch{preferences.settings.map{Triple(it.nmeaSharingEnabled,it.nmeaSharingPort,it.gpsDataSource)}.distinctUntilChanged().collect{(enabled,port,source)->commandMutex.withLock{configureSharing(enabled,port,source)}}}
  scope.launch{navigation.validRawSentences.collect{line->if(sharingEnabled)outputMux.boatSentence(line,sharingSource)?.let(sharingServer::publish)}}
  scope.launch{systemLocation.fix.filterNotNull().collect{fix->if(sharingEnabled&&sharingSource==GpsDataSource.SYSTEM)when(val result=sharingPositionIntegrity.evaluate(fix)){is PositionIntegrityResult.Accepted->result.fixes.filter{!it.wasQuarantined&&it.trust!=FixTrust.QUARANTINED&&it.trust!=FixTrust.REJECTED}.forEach{accepted->outputMux.systemPosition(accepted.fix,SystemClock.elapsedRealtime()).forEach(sharingServer::publish)};else->Unit}}}
  scope.launch{while(isActive){delay(1000);commandMutex.withLock{watchdog()}}}
  scope.launch{while(isActive){delay(30_000);batteryWatchdog()}}
 }

 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  when(intent?.action){
   ARM->{armPending=true;val c=AnchorConfig(intent.getDoubleExtra("lat",0.0),intent.getDoubleExtra("lon",0.0),intent.getDoubleExtra("rode",0.0),intent.getDoubleExtra("depth",Double.NaN).takeUnless{it.isNaN()},bowRollerHeightMeters=intent.getDoubleExtra("bowHeight",0.0),gpsAntennaOffsetMeters=intent.getDoubleExtra("antennaOffset",0.0),warningRadiusMeters=intent.getDoubleExtra("warning",40.0),alarmRadiusMeters=intent.getDoubleExtra("alarm",50.0));val requestedSource=intent.getStringExtra("positionSource")?.let{runCatching{GpsDataSource.valueOf(it)}.getOrNull()};val request=ArmRequest(c,enumExtra(intent,"placement",AnchorPlacementMode.CENTER_DROP),enumExtra(intent,"rangeMode",AnchorRangeMode.BASIC),enumExtra(intent,"safetyPreset",AnchorSafetyPreset.BALANCED),intent.getDoubleExtra("boatLength",Double.NaN).takeUnless{it.isNaN()},requestedSource,enumExtra(intent,"centerSource",AnchorCenterSource.CURRENT_POSITION),intent.getBooleanExtra("usePhoneHeading",false));launchCommand{try{arm(request)}finally{armPending=false;releaseIfIdle()}}}
   ACK,SNOOZE->launchCommand{snoozeAlarm()}
   STOP_WATCH,PAUSE_WATCH->launchCommand{pauseWatch()}
   RESUME_WATCH->launchCommand{resumeWatch()}
   LIFT_ANCHOR->launchCommand{liftAnchor()}
   UPDATE_RADIUS->launchCommand{updateWatchSettings(intent)}
   STOP_WATCH_AND_DISCONNECT->launchCommand{stopWatchAndDisconnect()}
   SWITCH_WATCH_TO_SYSTEM->launchCommand{switchWatchToSystem(true)}
   SWITCH_WATCH_SOURCE_SYSTEM->launchCommand{switchWatchToSystem(false)}
   SWITCH_WATCH_SOURCE_NMEA->launchCommand{switchWatchToNmea()}
   ACCEPT_ESTIMATED_CENTER->launchCommand{acceptEstimatedCenter(intent.getLongExtra("sessionId",-1L),intent.getLongExtra("candidateId",-1L))}
   REJECT_ESTIMATED_CENTER->launchCommand{rejectEstimatedCenter(intent.getLongExtra("sessionId",-1L),intent.getLongExtra("candidateId",-1L))}
   START_PROXY->launchCommand{startProxy()}
   STOP_PROXY->launchCommand{stopProxy(l("Android GPS proxy stopped by user.","用户已关闭 Android GPS 代理。"))}
   TEST_ALARM->launchCommand{testAlarm()}
   STOP_ALARM_TEST->launchCommand{stopAlarmTest()}
   DISABLE_DEMO_TO_SYSTEM->launchCommand{switchWatchToSystem(false,true)}
   SET_NMEA_SHARING->launchCommand{configureSharing(intent.getBooleanExtra("enabled",false),intent.getIntExtra("port",10111),preferences.settings.first().gpsDataSource)}
  }
  return START_STICKY
 }

 private fun launchCommand(action:suspend ()->Unit){scope.launch{stateReady.await();commandMutex.withLock{action()}}}

 private suspend fun restoreState(){
  var settings=preferences.settings.first();session=dao.active();val lockedSource=session?.positionSource?.let{runCatching{GpsDataSource.valueOf(it)}.getOrNull()};if(lockedSource!=null&&lockedSource!=settings.gpsDataSource){settings=settings.copy(gpsDataSource=lockedSource);preferences.save(settings)};currentGpsSource=lockedSource?:settings.gpsDataSource;alarmSnoozeMinutes=settings.alarmSnoozeMinutes;selectedAlarmSound=settings.alarmSound;customAlarmSoundUri=settings.customAlarmSoundUri;appLanguage=settings.appLanguage;channels();positionIntegrity.reset();candidateDriftDetector.reset();if(session==null)alarmUi.clear();session?.let{active->
   engine=alarmEngine(settings);val points=dao.points(active.id).first();val events=dao.events(active.id).first();candidateDriftDetector.restore(events.filter{it.type=="ESTIMATED_CENTER_HISTORY"}.mapNotNull{CandidateCenterObservation.decode(it.detail)},events.any{it.type=="POSSIBLE_ANCHOR_DRAG_TREND"});restoredDemoElapsed=(if(active.paused)points.lastOrNull()?.timestamp?.minus(active.startedAt) else System.currentTimeMillis()-active.startedAt)?.coerceAtLeast(0L)?:0L
   if(active.centerStatus!=AnchorCenterStatus.RESOLVED.name){backdownSamples.addAll(points.filter{it.fixTrust!=FixTrust.REJECTED.name&&it.fixTrust!=FixTrust.QUARANTINED.name}.map{BackdownCenterEstimator.Sample(latitude=it.latitude,longitude=it.longitude,timestamp=it.timestamp,hdop=it.hdop,headingTrueDegrees=it.heading.takeIf{_->it.headingSource==HeadingSource.NMEA_PHYSICAL.name||it.headingSource==HeadingSource.PHONE.name},cogTrueDegrees=it.cog,sogKnots=it.sog,windDirectionTrueDegrees=it.windDirectionTrue,windSpeedKnots=it.windSpeedKnots,apparentWindAngleDegrees=it.apparentWindAngle,trueWindAngleDegrees=it.trueWindAngle,trueWindSpeedKnots=it.trueWindSpeedKnots,apparentWindSpeedKnots=it.apparentWindSpeedKnots,headingSampleSequence=it.headingSampleSequence,windSampleSequence=it.windSampleSequence)});lastSnapshot=engine.learn(active.learningConfig(),SystemClock.elapsedRealtime())}
   else{lastSnapshot=engine.arm(active.config(),SystemClock.elapsedRealtime())}
   if(!active.paused){locks(settings.keepWifiAwake);if(active.usePhoneHeading)phoneHeading.start()}
  }
  if(session?.paused==false){when(settings.gpsDataSource){GpsDataSource.NMEA->navigation.acquireBackgroundConnection(settings.profile);GpsDataSource.SYSTEM->enableSystemGps();GpsDataSource.DEMO->{enableSystemGps();session?.let{active->if(!demoLocation.status.value.running)demoLocation.start(active.learningReferenceLatitude?:active.anchorLatitude,active.learningReferenceLongitude?:active.anchorLongitude,runCatching{AnchorPlacementMode.valueOf(active.placementMode)}.getOrDefault(AnchorPlacementMode.CENTER_DROP),settings.demoScenario,active.alarmRadiusMeters,settings.demoSpeedMultiplier,initialElapsedMillis=restoredDemoElapsed,seed=demoSeed(active))else demoLocation.resume()}}}}
  else if(session?.paused==true&&settings.gpsDataSource==GpsDataSource.DEMO){session?.let{active->if(!demoLocation.status.value.running)demoLocation.start(active.learningReferenceLatitude?:active.anchorLatitude,active.learningReferenceLongitude?:active.anchorLongitude,runCatching{AnchorPlacementMode.valueOf(active.placementMode)}.getOrDefault(AnchorPlacementMode.CENTER_DROP),settings.demoScenario,active.alarmRadiusMeters,settings.demoSpeedMultiplier,initialElapsedMillis=restoredDemoElapsed,seed=demoSeed(active));demoLocation.pause()}}
  if(settings.mockEnabled&&settings.gpsDataSource==GpsDataSource.NMEA){navigation.acquireBackgroundConnection(settings.profile);locks(settings.keepWifiAwake);startProxy()}
  else if(settings.mockEnabled){preferences.setMockEnabled(false);mockGps.stop("A non-NMEA App GPS source is selected — global NMEA proxy disabled.")}
  handleNmeaState(navigation.connectionState.value)
  refreshNotification()
 }

 private suspend fun arm(request:ArmRequest){
  if(session!=null){notifySeparate("Anchor session already open","Pause, resume or lift the current anchor before starting another session.",true);return}
  var settings=preferences.settings.first();val now=SystemClock.elapsedRealtime()
  val positionSource=request.positionSource?:settings.gpsDataSource
  if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){notifySeparate("Anchor watch not started","Notification permission is required so background safety alarms remain visible.",true);return}
  if(settings.demoMode&&positionSource!=GpsDataSource.DEMO){notifySeparate("Anchor watch not started","Demo mode locks the position source to Demo GPS.",true);return}
  if(!settings.demoMode&&positionSource==GpsDataSource.DEMO){notifySeparate("Anchor watch not started","Demo GPS is only available while Developer demo mode is enabled.",true);return}
  if(positionSource==GpsDataSource.SYSTEM&&GpsSourceSafety.blocksSystemGps(settings.mockEnabled,mockGps.status.value.state)){notifySeparate("Anchor watch not started","Phone GPS is not independent while the global NMEA GPS proxy is active. Disable the proxy first.",true);return}
  if(request.config.latitude !in -90.0..90.0||request.config.longitude !in -180.0..180.0||!request.config.latitude.isFinite()||!request.config.longitude.isFinite()){notifySeparate("Anchor watch not started","Enter a valid anchor coordinate.",true);return}
  if(request.placement==AnchorPlacementMode.BACKDOWN||request.rangeMode==AnchorRangeMode.ADVANCED){
   val depth=request.config.waterDepthMeters;val rode=request.config.rodeLengthMeters;val bow=request.config.bowRollerHeightMeters
   if(depth==null||depth<0||bow<=0||rode<=depth+bow||(request.rangeMode==AnchorRangeMode.ADVANCED&&(request.boatLength?:0.0)<=0)){notifySeparate("Anchor watch not started","The selected setup requires valid water depth, rode, bow height and boat length; rode must exceed the total vertical depth.",true);return}
  }
  val sourceReady=when(positionSource){GpsDataSource.NMEA->navigation.connectionState.value==NmeaConnectionState.CONNECTED;GpsDataSource.SYSTEM,GpsDataSource.DEMO->enableSystemGps()}
  val latestFix=when(positionSource){GpsDataSource.NMEA->navigation.fix.value;GpsDataSource.SYSTEM,GpsDataSource.DEMO->systemLocation.fix.value}
  val lastFix=if(positionSource==GpsDataSource.NMEA)navigation.diagnostics.value.lastFixElapsed else latestFix?.receivedElapsedRealtime
  val preciseProvider=positionSource==GpsDataSource.NMEA||latestFix?.positionProvider==PositionProvider.ANDROID_GNSS
  val sourceQualityReady=when(positionSource){GpsDataSource.NMEA->(latestFix?.hdop?:0.0)<=5.0&&(latestFix?.fixQuality?:1)>0;GpsDataSource.SYSTEM,GpsDataSource.DEMO->(latestFix?.horizontalAccuracyMeters?:Double.POSITIVE_INFINITY)<=30.0}
  val freshFix=sourceReady&&latestFix?.valid==true&&preciseProvider&&sourceQualityReady&&lastFix!=null&&now-lastFix<settings.gpsLossSeconds*1000L
  if(!freshFix){val label=when(positionSource){GpsDataSource.NMEA->"NMEA";GpsDataSource.SYSTEM->"system GNSS (not coarse network location)";GpsDataSource.DEMO->"system GNSS origin for Demo"};notifySeparate("Anchor watch not started","A live, current $label GPS fix is required. Existing connections were left unchanged.",false);refreshNotification();releaseIfIdle();return}
  val learning=request.placement==AnchorPlacementMode.BACKDOWN
  val c=if(positionSource==GpsDataSource.DEMO||learning)request.config.copy(latitude=latestFix!!.latitude,longitude=latestFix.longitude,gpsAntennaOffsetMeters=if(positionSource==GpsDataSource.NMEA)request.config.gpsAntennaOffsetMeters else 0.0)else request.config.copy(gpsAntennaOffsetMeters=if(positionSource==GpsDataSource.NMEA)request.config.gpsAntennaOffsetMeters else 0.0)
  val wallNow=System.currentTimeMillis()
  val horizontalRode=AnchorGeometry.expectedRadius(c.rodeLengthMeters,c.waterDepthMeters,c.bowRollerHeightMeters,c.gpsAntennaOffsetMeters)
  val entity=AnchorSessionEntity(startedAt=wallNow,anchorLatitude=c.latitude,anchorLongitude=c.longitude,rodeLengthMeters=c.rodeLengthMeters,waterDepthMeters=c.waterDepthMeters,bowRollerHeightMeters=c.bowRollerHeightMeters,gpsAntennaOffsetMeters=c.gpsAntennaOffsetMeters,expectedSwingRadiusMeters=horizontalRode,warningRadiusMeters=c.warningRadiusMeters,alarmRadiusMeters=c.alarmRadiusMeters,placementMode=request.placement.name,centerStatus=if(learning)AnchorCenterStatus.LEARNING.name else AnchorCenterStatus.RESOLVED.name,centerResolvedAt=if(learning)null else wallNow,centerConfidence=if(learning)Confidence.LOW.name else Confidence.HIGH.name,centerSampleCount=if(learning)0 else 1,boatLengthMeters=request.boatLength,rangeMode=request.rangeMode.name,safetyPreset=request.safetyPreset.name,learningReferenceLatitude=if(learning)c.latitude else null,learningReferenceLongitude=if(learning)c.longitude else null,provisionalAnchorLatitude=if(learning)c.latitude else null,provisionalAnchorLongitude=if(learning)c.longitude else null,provisionalRadiusMeters=if(learning)maxOf(horizontalRode,c.rodeLengthMeters*.85,25.0) else null,positionSource=positionSource.name,anchorPositionMode=if(learning)AnchorPositionMode.ESTIMATE.name else AnchorPositionMode.KNOWN.name,centerSource=if(learning)AnchorCenterSource.UNKNOWN.name else request.centerSource.name,usePhoneHeading=learning&&request.usePhoneHeading,candidateDecision=CandidateDecision.NONE.name)
  settings=settings.copy(gpsDataSource=positionSource);preferences.save(settings);currentGpsSource=positionSource
  session=entity.copy(id=dao.insertSession(entity));dao.insertEvent(AlarmEventEntity(sessionId=session!!.id,timestamp=wallNow,type=if(learning)"SESSION_STARTED_CENTER_LEARNING" else "SESSION_STARTED",detail="SOURCE=${positionSource.name};CENTER=${request.centerSource.name}"));backdownSamples.clear();candidateDriftDetector.reset();positionIntegrity.reset();clearPositionDegraded();silence();lastReportedAlarm=null;engine=alarmEngine(settings);lastSnapshot=if(learning)engine.learn(c,now)else engine.arm(c,now);locks(settings.keepWifiAwake);if(positionSource==GpsDataSource.NMEA)navigation.acquireBackgroundConnection(settings.profile);if(learning&&request.usePhoneHeading)phoneHeading.start();val initialFix=if(positionSource==GpsDataSource.DEMO)demoLocation.start(c.latitude,c.longitude,request.placement,settings.demoScenario,c.alarmRadiusMeters,settings.demoSpeedMultiplier,now,seed=demoSeed(session!!))?:latestFix!! else latestFix!!;handleFix(initialFix,positionSource)
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

 private fun testAlarm(){
  silence();sound();alarmUi.publish(AlarmSnapshot(AlarmState.ALARM,AlarmType.MOCK_GPS_FAILED));notifySeparate("Alarm test","Sound, vibration and in-app alarm UI are active. They will stop automatically after 8 seconds.",true)
  scope.launch{delay(8_000);commandMutex.withLock{stopAlarmTest()}}
 }
 private fun stopAlarmTest(){silence();if(session==null)alarmUi.clear()else lastSnapshot?.let(alarmUi::publish);getSystemService(NotificationManager::class.java).cancel(EVENT);refreshNotification();releaseIfIdle()}

 private suspend fun switchWatchToSystem(disconnectNmea:Boolean,disableDemoMode:Boolean=false){
  val active=session;val settings=preferences.settings.first();val previousSource=settings.gpsDataSource
  if(active!=null&&previousSource==GpsDataSource.DEMO){rejectSourceChange(active,"DEMO_TO_SYSTEM","Demo GPS remains locked for this open anchor session. Lift anchor before leaving Demo mode.");return}
  if(settings.demoMode&&!disableDemoMode){notifySeparate("GPS source not changed","Demo mode locks this App to Demo GPS. Disable Demo mode first.",true);refreshNotification();return}
  if(previousSource==GpsDataSource.SYSTEM){if(disableDemoMode)preferences.save(settings.copy(demoMode=false));if(disconnectNmea)navigation.disconnectAll();refreshNotification();return}
  if(previousSource==GpsDataSource.NMEA&&GpsSourceSafety.blocksSystemGps(settings.mockEnabled,mockGps.status.value.state)){notifySeparate("GPS source not changed","Disable the global NMEA GPS proxy before selecting System GPS. Android mock mode replaces the system fused location source.",true);refreshNotification();return}
  if(!enableSystemGps()){rejectSourceChange(active,"${previousSource.name}_TO_SYSTEM","Precise location permission and an available System GNSS provider are required before switching.");return}
  val started=SystemClock.elapsedRealtime()
  val systemFix=withTimeoutOrNull(12_000){systemLocation.fix.filterNotNull().first{fix->fix.valid&&!fix.isMockLocation&&fix.positionProvider==PositionProvider.ANDROID_GNSS&&(fix.horizontalAccuracyMeters?:Double.POSITIVE_INFINITY)<=30.0&&fix.receivedElapsedRealtime>=started}}
  if(systemFix==null){rejectSourceChange(active,"${previousSource.name}_TO_SYSTEM","NMEA stayed selected because a fresh, precise System GNSS fix was not available.");releaseIfIdle();return}
  if(previousSource==GpsDataSource.DEMO)demoLocation.stop()
  active?.let{current->val updated=current.copy(positionSource=GpsDataSource.SYSTEM.name);session=updated;dao.updateSession(updated);dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=System.currentTimeMillis(),type="WATCH_GPS_SOURCE_CHANGED",detail="${previousSource.name}_TO_SYSTEM"))}
  currentGpsSource=GpsDataSource.SYSTEM;positionIntegrity.reset();clearPositionDegraded();preferences.save(settings.copy(gpsDataSource=GpsDataSource.SYSTEM,mockEnabled=false,demoMode=if(disableDemoMode)false else settings.demoMode))
  if(previousSource==GpsDataSource.NMEA&&!sharingEnabled){if(disconnectNmea)navigation.disconnectAll()else navigation.releaseBackgroundConnection()}
  notifySeparate("GPS source changed","System GNSS is now monitoring the same anchor session; its centre, range and track were preserved.",false);refreshNotification();releaseIfIdle()
 }

 private suspend fun switchWatchToNmea(){
  val active=session;val settings=preferences.settings.first()
  if(settings.demoMode){notifySeparate("GPS source not changed","Demo mode locks this App to Demo GPS. Lift anchor and disable Demo mode first.",true);refreshNotification();return}
  if(settings.gpsDataSource==GpsDataSource.NMEA){refreshNotification();return}
  val previousSource=settings.gpsDataSource
  val availability=NmeaSourceSelectionPolicy.availability(navigation.connectionState.value,navigation.fix.value,navigation.connectionStartedElapsed.value,SystemClock.elapsedRealtime(),settings.gpsLossSeconds*1000L)
  if(availability!=NmeaSourceAvailability.AVAILABLE||!navigation.claimBackgroundConnectionIfConnected()){
   rejectSourceChange(active,"${previousSource.name}_TO_NMEA_${availability.name}","Connect the NMEA source and wait for a fresh valid position before selecting NMEA GPS.");releaseIfIdle();return
  }
  val nmeaFix=navigation.fix.value
  if(nmeaFix==null||!nmeaFix.valid){rejectSourceChange(active,"${previousSource.name}_TO_NMEA_NO_VALID_FIX","NMEA stayed unselected because it did not provide a valid position.");releaseIfIdle();return}
  active?.let{current->val updated=current.copy(positionSource=GpsDataSource.NMEA.name);session=updated;dao.updateSession(updated);dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=System.currentTimeMillis(),type="WATCH_GPS_SOURCE_CHANGED",detail="${previousSource.name}_TO_NMEA"))}
  currentGpsSource=GpsDataSource.NMEA;nmeaLossAnnounced=false;if(previousSource==GpsDataSource.DEMO)demoLocation.stop();positionIntegrity.reset();clearPositionDegraded();preferences.save(settings.copy(gpsDataSource=GpsDataSource.NMEA));if(!sharingEnabled)systemLocation.setBackgroundEnabled(false);notifySeparate("GPS source changed","NMEA GPS is now monitoring the same anchor session; its centre, range and track were preserved.",false);refreshNotification();releaseIfIdle()
 }

 private suspend fun rejectSourceChange(active:AnchorSessionEntity?,detail:String,message:String){
  active?.let{dao.insertEvent(AlarmEventEntity(sessionId=it.id,timestamp=System.currentTimeMillis(),type="WATCH_GPS_SOURCE_CHANGE_REJECTED",detail=detail))}
  notifySeparate("GPS source not changed",message,true);refreshNotification()
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

 private suspend fun handleFix(rawFix:NavigationFix,source:GpsDataSource){
  val locked=session?.positionSource?.let{runCatching{GpsDataSource.valueOf(it)}.getOrNull()}
  if(locked!=null&&locked!=source)return
  phoneHeading.setPosition(rawFix.latitude,rawFix.longitude,rawFix.altitudeMeters,rawFix.timestampUtcMillis)
  val phone=phoneHeading.sample.value
  val usePhone=session?.usePhoneHeading==true&&rawFix.headingTrueDegrees==null
  val fix=if(usePhone)rawFix.copy(headingTrueDegrees=phone.trueHeadingDegrees,headingSource=if(phone.trueHeadingDegrees!=null)HeadingSource.PHONE else HeadingSource.NONE,headingQuality=phone.quality,headingEpoch=phone.epoch,headingSampleSequence=phone.sequence)else rawFix
  when(val decision=positionIntegrity.evaluate(fix)){
   is PositionIntegrityResult.Rejected->{markPositionDegraded(decision.fix.receivedElapsedRealtime,decision.reason);logEvent("GPS_FIX_REJECTED",decision.reason);return}
   is PositionIntegrityResult.Quarantined->{markPositionDegraded(decision.fix.receivedElapsedRealtime,decision.reason);logEvent("GPS_SPIKE_QUARANTINED",decision.reason);return}
   is PositionIntegrityResult.Accepted->decision.fixes.forEach{processAcceptedFix(it,source)}
  }
 }

 private suspend fun processAcceptedFix(accepted:IntegrityAcceptedFix,source:GpsDataSource){
  val fix=accepted.fix
  if(accepted.trust==FixTrust.TRUSTED)clearPositionDegraded() else markPositionDegraded(fix.receivedElapsedRealtime,accepted.reason?:accepted.trust.name)
  if(source==GpsDataSource.NMEA&&nmeaLossAnnounced&&session?.paused==false){nmeaLossAnnounced=false;logEvent("NMEA_CONNECTION_RESTORED","");notifySeparate("NMEA GPS restored","Valid NMEA positions are flowing again; anchor watch remained active.",false)}
  val activeSession=session
  if(activeSession!=null&&!activeSession.paused){
   val snapshot=withPositionQuality(engine.onFix(fix,fix.receivedElapsedRealtime),fix.receivedElapsedRealtime)
   if(snapshot.maxDistanceMeters>activeSession.maxDistanceMeters){val updated=activeSession.copy(maxDistanceMeters=snapshot.maxDistanceMeters);session=updated;dao.updateSession(updated)}
   // Receipt time is monotonic across NMEA devices that repeat or omit UTC and
   // preserves the first quarantined point when a real displacement is released.
   val pointTime=System.currentTimeMillis()-(SystemClock.elapsedRealtime()-fix.receivedElapsedRealtime).coerceAtLeast(0L)
   if(pointTime-lastTrack>=900L||accepted.wasQuarantined){
    lastTrack=pointTime
    dao.insertPoint(TrackPointEntity(sessionId=activeSession.id,timestamp=pointTime,latitude=fix.latitude,longitude=fix.longitude,distanceFromAnchor=snapshot.distanceMeters?:0.0,sog=fix.sogKnots,cog=fix.cogTrueDegrees,heading=fix.headingTrueDegrees,hdop=fix.hdop,windDirectionTrue=fix.windDirectionTrueDegrees,windSpeedKnots=fix.windSpeedKnots,apparentWindAngle=fix.apparentWindAngleDegrees,trueWindAngle=fix.trueWindAngleDegrees,trueWindSpeedKnots=fix.trueWindSpeedKnots,apparentWindSpeedKnots=fix.apparentWindSpeedKnots,headingMeasured=fix.headingTrueDegrees!=null,headingSampleSequence=fix.headingSampleSequence,windSampleSequence=fix.windSampleSequence,positionSource=source.name,positionProvider=fix.positionProvider.name,horizontalAccuracyMeters=fix.horizontalAccuracyMeters,fixTrust=accepted.trust.name,wasQuarantined=accepted.wasQuarantined,quarantineReason=accepted.reason,headingSource=fix.headingSource.name,headingQuality=fix.headingQuality.name,headingEpoch=fix.headingEpoch))
    if(activeSession.anchorPositionMode==AnchorPositionMode.ESTIMATE.name){
     backdownSamples+=BackdownCenterEstimator.Sample(latitude=fix.latitude,longitude=fix.longitude,timestamp=pointTime,hdop=fix.hdop,headingTrueDegrees=fix.headingTrueDegrees,cogTrueDegrees=fix.cogTrueDegrees,sogKnots=fix.sogKnots,windDirectionTrueDegrees=fix.windDirectionTrueDegrees,windSpeedKnots=fix.windSpeedKnots,apparentWindAngleDegrees=fix.apparentWindAngleDegrees,trueWindAngleDegrees=fix.trueWindAngleDegrees,trueWindSpeedKnots=fix.trueWindSpeedKnots,apparentWindSpeedKnots=fix.apparentWindSpeedKnots,headingSampleSequence=fix.headingSampleSequence,windSampleSequence=fix.windSampleSequence)
     updateEstimatedCandidate(backdownEstimator.provisionalEstimate(backdownSamples,activeSession.expectedSwingRadiusMeters))
    }
   }
   updateAlarm(snapshot)
  }
  if(source==GpsDataSource.NMEA&&mockGps.status.value.state==MockGpsState.ACTIVE){val now=SystemClock.elapsedRealtime();if(proxyPolicy?.onValidFix(now)==true){val result=mockGps.publish(fix);if(result.isFailure){logEvent("MOCK_GPS_FAILED",result.exceptionOrNull()?.message?:"");stopProxy("NMEA injection failed — Android GPS restored.")}}}
 }

 private suspend fun updateEstimatedCandidate(estimate:BackdownAnchorEstimate?){
  val current=session?.takeIf{it.anchorPositionMode==AnchorPositionMode.ESTIMATE.name}?:return
  if(estimate==null)return
  if(estimate.confidence==Confidence.HIGH){
   val observation=CandidateCenterObservation(System.currentTimeMillis(),estimate.latitude,estimate.longitude,estimate.uncertaintyRadiusMeters)
   when(candidateDriftDetector.add(observation)){
    CandidateDriftUpdate.IGNORED->Unit
    CandidateDriftUpdate.RECORDED->dao.insertEvent(AlarmEventEntity(sessionId=current.id,timestamp=observation.timestamp,type="ESTIMATED_CENTER_HISTORY",detail=observation.encode()))
    CandidateDriftUpdate.POSSIBLE_DRAG->{dao.insertEvent(AlarmEventEntity(sessionId=current.id,timestamp=observation.timestamp,type="ESTIMATED_CENTER_HISTORY",detail=observation.encode()));dao.insertEvent(AlarmEventEntity(sessionId=current.id,timestamp=observation.timestamp,type="POSSIBLE_ANCHOR_DRAG_TREND",detail="Candidate centre moved persistently in one direction; formal radius alarm remains authoritative."));notifySeparate("Possible slow anchor movement","The estimated centre has moved persistently in one direction. This is an advisory only; check the vessel and the formal alarm boundary.",false)}
   }
  }
  val existingUncertainty=current.provisionalRadiusMeters
  val high=estimate.confidence==Confidence.HIGH
  val firstCandidate=current.candidateId==null
  val qualityNotWorse=(current.candidateRmsErrorMeters==null||estimate.rmsErrorMeters==null||estimate.rmsErrorMeters<=current.candidateRmsErrorMeters*1.10)&&(current.candidateAngularCoverageDegrees==null||estimate.angularCoverageDegrees>=current.candidateAngularCoverageDegrees-10.0)&&(estimate.angularSectorCount>=current.candidateAngularSectorCount-1)&&(!current.candidateTemporalFitConsistent||estimate.temporalFitConsistent)
  val improved=(existingUncertainty==null||estimate.uncertaintyRadiusMeters<=existingUncertainty*.85)&&qualityNotWorse
  val previewImproved=firstCandidate&&(existingUncertainty==null||estimate.uncertaintyRadiusMeters<=existingUncertainty*.97)&&qualityNotWorse
  val makeAvailable=high&&(firstCandidate||improved)
  if(!makeAvailable&&!previewImproved){
   // Evidence can advance while the initial large feasible region remains
   // deliberately unchanged. Do not make a young straight-line fit look precise.
   if(firstCandidate&&estimate.sampleCount>current.centerSampleCount){val progress=current.copy(centerConfidence=estimate.confidence.name,centerSampleCount=estimate.sampleCount);session=progress;dao.updateSession(progress)}
   return
  }
  val candidateId=if(makeAvailable)System.currentTimeMillis() else current.candidateId
  val updated=current.copy(
   centerStatus=if(makeAvailable&&current.centerStatus!=AnchorCenterStatus.RESOLVED.name)AnchorCenterStatus.CANDIDATE_READY.name else current.centerStatus,
   provisionalAnchorLatitude=estimate.latitude,provisionalAnchorLongitude=estimate.longitude,provisionalRadiusMeters=estimate.uncertaintyRadiusMeters,
   centerConfidence=estimate.confidence.name,centerSampleCount=estimate.sampleCount,
   candidateId=candidateId,candidateCreatedAt=if(makeAvailable)System.currentTimeMillis() else current.candidateCreatedAt,
   candidateDecision=if(makeAvailable)CandidateDecision.AVAILABLE.name else current.candidateDecision,
   candidateNotificationShown=if(makeAvailable)true else current.candidateNotificationShown,
   candidateRmsErrorMeters=estimate.rmsErrorMeters,
   candidateAngularCoverageDegrees=estimate.angularCoverageDegrees,
   candidateAngularSectorCount=estimate.angularSectorCount,
   candidateSwingReversalCount=estimate.swingReversalCount,
   candidateTemporalFitConsistent=estimate.temporalFitConsistent,
   candidateEffectiveDurationMillis=estimate.effectiveDurationMillis,
   candidateDirectionEvidenceConsistent=estimate.directionEvidenceConsistent,
  )
  session=updated;dao.updateSession(updated)
  if(makeAvailable){dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=System.currentTimeMillis(),type="ESTIMATED_CENTER_HIGH",detail="candidate=$candidateId;uncertainty=${estimate.uncertaintyRadiusMeters.toInt()}m;rms=${estimate.rmsErrorMeters};coverage=${estimate.angularCoverageDegrees.toInt()};sectors=${estimate.angularSectorCount};reversals=${estimate.swingReversalCount};temporal=${estimate.temporalFitConsistent}"));notifySeparate("Estimated anchor centre ready","A high-confidence candidate is ready. The working alarm circle has not moved; review it in Watch.",false)}
 }

 private suspend fun acceptEstimatedCenter(sessionId:Long,candidateId:Long){
  val current=session
  if(current==null||current.id!=sessionId||current.candidateId!=candidateId||current.candidateDecision!=CandidateDecision.AVAILABLE.name||current.provisionalAnchorLatitude==null||current.provisionalAnchorLongitude==null){logEvent("ANCHOR_CENTER_ACCEPT_REJECTED","STALE_CANDIDATE");return}
  if(lastSnapshot?.state==AlarmState.ALARM){notifySeparate("Estimated centre not applied","Handle or pause the active alarm before changing its centre.",true);return}
  val now=System.currentTimeMillis();val updated=current.copy(anchorLatitude=current.provisionalAnchorLatitude,anchorLongitude=current.provisionalAnchorLongitude,centerStatus=AnchorCenterStatus.RESOLVED.name,centerResolvedAt=now,centerConfidence=Confidence.HIGH.name,centerSource=AnchorCenterSource.ESTIMATED_USER_ACCEPTED.name,anchorPositionMode=AnchorPositionMode.KNOWN.name,candidateDecision=CandidateDecision.ACCEPTED.name,provisionalAnchorLatitude=null,provisionalAnchorLongitude=null,provisionalRadiusMeters=null,usePhoneHeading=false,alarmSnoozedUntil=null)
  session=updated;dao.updateSession(updated);phoneHeading.stop();engine=alarmEngine(preferences.settings.first());lastSnapshot=engine.arm(updated.config(),SystemClock.elapsedRealtime());alarmUi.publish(lastSnapshot!!);silence();dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=now,type="ANCHOR_CENTER_ACCEPTED_BY_USER",detail="candidate=$candidateId;radius=${updated.alarmRadiusMeters}"));refreshNotification()
 }

 private suspend fun rejectEstimatedCenter(sessionId:Long,candidateId:Long){
  val current=session
  if(current==null||current.id!=sessionId||current.candidateId!=candidateId||current.candidateDecision!=CandidateDecision.AVAILABLE.name){logEvent("ANCHOR_CENTER_REJECT_REJECTED","STALE_CANDIDATE");return}
  val updated=current.copy(centerStatus=if(current.centerSource==AnchorCenterSource.ESTIMATED_USER_ACCEPTED.name)AnchorCenterStatus.RESOLVED.name else AnchorCenterStatus.LEARNING.name,candidateDecision=CandidateDecision.REJECTED.name)
  session=updated;dao.updateSession(updated);dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=System.currentTimeMillis(),type="ANCHOR_CENTER_CANDIDATE_REJECTED",detail="candidate=$candidateId"));refreshNotification()
 }

 private suspend fun configureSharing(enabled:Boolean,port:Int,source:GpsDataSource){
  sharingEnabled=enabled;sharingSource=source;sharingPositionIntegrity.reset()
  if(enabled){sharingServer.start(port);val settings=preferences.settings.first();val local=networkAddresses.localAddresses();val literalLoop=NmeaSelfLoopPolicy.isLiteralLoop(settings.profile,true,port,local);val resolvedLoop=if(settings.profile.protocol==com.yokuli.anchorwatch.data.nmea.Protocol.TCP&&settings.profile.port==port)runCatching{val address=java.net.InetAddress.getByName(settings.profile.host);address.isLoopbackAddress||address.isAnyLocalAddress||local.any{it.substringBefore('%')==address.hostAddress?.substringBefore('%')}}.getOrDefault(false)else false;if(!literalLoop&&!resolvedLoop)navigation.acquireBackgroundConnection(settings.profile)else notifySeparate("NMEA input blocked",NmeaSelfLoopPolicy.MESSAGE,true);if(source==GpsDataSource.SYSTEM)enableSystemGps()else if(session?.positionSource!=GpsDataSource.SYSTEM.name)systemLocation.setBackgroundEnabled(false);locks(settings.keepWifiAwake)}
  else{sharingServer.stop();if(currentGpsSource!=GpsDataSource.NMEA&&mockGps.status.value.state!=MockGpsState.ACTIVE)navigation.releaseBackgroundConnection();releaseIfIdle()}
  refreshNotification()
 }

 private suspend fun watchdog(){
  val now=SystemClock.elapsedRealtime();if(session?.paused==false&&currentGpsSource==GpsDataSource.DEMO)demoLocation.tick(now)?.let{handleFix(it,GpsDataSource.DEMO)};session?.takeIf{!it.paused}?.let{updateAlarm(withPositionQuality(engine.tick(now),now))}
  if(mockGps.status.value.state==MockGpsState.ACTIVE&&proxyPolicy?.isStale(now)==true){mockGps.stale();proxyPolicy=null;preferences.setMockEnabled(false);notifySeparate("NMEA GPS lost","Android GPS restored to its normal source.",true);logEvent("GPS_PROXY_STALE","");releaseIfIdle()}
  refreshNotification()
 }

 private suspend fun batteryWatchdog(){
  if(session?.paused!=false&&mockGps.status.value.state!=MockGpsState.ACTIVE)return
  val percent=getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
  if(percent in 0..15&&!lowBatteryReported){lowBatteryReported=true;notifySeparate("Low battery: $percent%","Connect this monitoring device to reliable power.",true);logEvent("LOW_BATTERY","$percent")}
  if(percent>20)lowBatteryReported=false
 }

 private fun markPositionDegraded(atElapsed:Long,reason:String){
  if(session?.paused!=false)return
  positionDegradedSince=positionDegradedSince?.let{minOf(it,atElapsed)}?:atElapsed
  positionDegradedReason=reason
 }
 private fun clearPositionDegraded(){positionDegradedSince=null;positionDegradedReason=null}
 private fun withPositionQuality(base:AlarmSnapshot,nowElapsed:Long):AlarmSnapshot{
  if(base.state==AlarmState.ALARM)return base
  val since=positionDegradedSince?:return base
  return if(session?.paused==false&&nowElapsed-since>=15_000L)base.copy(state=AlarmState.ALARM,type=AlarmType.GPS_QUALITY_BAD) else base
 }

 private suspend fun updateAlarm(snapshot:AlarmSnapshot){
  val now=System.currentTimeMillis();var active=session
  val settled=active
  if(snapshot.state!=AlarmState.ALARM&&snapshot.state!=AlarmState.WARNING&&snapshot.state!=AlarmState.ACKNOWLEDGED&&settled?.alarmSnoozedUntil!=null){val updated=settled.copy(alarmSnoozedUntil=null);active=updated;session=updated;dao.updateSession(updated)}
  val expired=active
  if(expired?.alarmSnoozedUntil?.let{it<=now}==true){val updated=expired.copy(alarmSnoozedUntil=null);active=updated;session=updated;dao.updateSession(updated)}
  lastSnapshot=snapshot;alarmUi.publish(snapshot);val critical=snapshot.state==AlarmState.ALARM
  if(AlarmReminderPolicy.shouldSound(snapshot,active?.paused?:true,active?.alarmSnoozedUntil,now))sound() else silence()
  if(critical&&snapshot.type!=lastReportedAlarm){lastReportedAlarm=snapshot.type;session?.let{current->val updated=current.copy(alarmCount=current.alarmCount+1);session=updated;dao.updateSession(updated)};logEvent("ALARM_TRIGGERED",snapshot.type?.name?:"")}
  if(!critical&&snapshot.state!=AlarmState.ACKNOWLEDGED)lastReportedAlarm=null
  refreshNotification(snapshot)
 }
 private fun refreshNotification(snapshot:AlarmSnapshot?=lastSnapshot){
  val proxy=mockGps.status.value;val active=session;val now=System.currentTimeMillis();val snoozed=AlarmReminderPolicy.isSnoozed(active?.alarmSnoozedUntil,now);val alarmCondition=active?.paused==false&&snapshot?.type!=null&&(snapshot.state==AlarmState.ALARM||snapshot.state==AlarmState.ACKNOWLEDGED);val remaining=active?.alarmSnoozedUntil?.let{((it-now+59_999)/60_000).coerceAtLeast(1)}
  val base=when{
   snapshot?.type==AlarmType.GPS_DATA_LOST&&active?.paused==false->l("GPS DATA LOST","GPS 数据丢失")
   snapshot?.type==AlarmType.GPS_QUALITY_BAD&&active?.paused==false->l("GPS QUALITY DEGRADED: ${positionDegradedReason?:"unknown"}","GPS 质量下降：${positionDegradedReason?:"未知原因"}")
   snapshot?.type==AlarmType.ANCHOR_RADIUS_EXCEEDED&&active?.paused==false->l("ANCHOR ALARM ${snapshot.distanceMeters?.toInt()} m","锚警：距离 ${snapshot.distanceMeters?.toInt()} 米")
   active?.paused==true&&proxy.state==MockGpsState.ACTIVE->l("Anchor session paused • NMEA GPS proxy active","锚泊监控已暂停 · NMEA GPS 代理运行中")
   active?.paused==true->l("Anchor session paused","锚泊监控已暂停")
   active?.centerStatus==AnchorCenterStatus.CANDIDATE_READY.name->l("Watch active • estimated centre awaits approval","锚警监控中 · 估算中心等待确认")
   active?.centerStatus==AnchorCenterStatus.LEARNING.name->l("Watch active • temporary boundary armed • ${backdownSamples.size} fixes","锚警监控中 · 临时边界已布防 · ${backdownSamples.size} 个定位点")
   active!=null&&currentGpsSource==GpsDataSource.NMEA&&nmeaLossAnnounced->l("Watch active • NMEA connection lost • reconnecting","锚警监控中 · NMEA 连接丢失 · 正在重连")
   active!=null&&currentGpsSource==GpsDataSource.SYSTEM->l("Watch ${snapshot?.distanceMeters?.toInt()?:"--"} m • SYSTEM GPS","锚警 ${snapshot?.distanceMeters?.toInt()?:"--"} 米 · 系统 GPS")
   active!=null&&currentGpsSource==GpsDataSource.DEMO->l("Watch ${snapshot?.distanceMeters?.toInt()?:"--"} m • DEMO ${demoLocation.status.value.scenario.name}","锚警 ${snapshot?.distanceMeters?.toInt()?:"--"} 米 · 演示 ${demoLocation.status.value.scenario.name}")
   active!=null->l("Watch ${snapshot?.distanceMeters?.toInt()?:"--"} m • NMEA ${navigation.connectionState.value.name}","锚警 ${snapshot?.distanceMeters?.toInt()?:"--"} 米 · NMEA ${navigation.connectionState.value.name}")
   proxy.state==MockGpsState.ACTIVE->l("NMEA → Android GPS active • ${proxy.publishedFixes} fixes","NMEA → Android GPS 已开启 · ${proxy.publishedFixes} 个定位点")
   sharingEnabled->l("NMEA Sharing • ${sharingServer.status.value.clientCount} clients • port ${sharingServer.status.value.port}","NMEA 共享 · ${sharingServer.status.value.clientCount} 个客户端 · 端口 ${sharingServer.status.value.port}")
   else->l("Safety monitor idle","安全监控待命")
  }
  val text=if(snoozed&&alarmCondition)l("$base • snoozed, remind in ${remaining}m","$base · 已暂停响铃，${remaining} 分钟后再次提醒") else base
  getSystemService(NotificationManager::class.java).notify(ONGOING,notification(text,alarmCondition,snoozed))
 }
 private fun notification(text:String,alarm:Boolean,silent:Boolean=false):Notification{val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT);val snooze=PendingIntent.getService(this,1,Intent(this,AnchorForegroundService::class.java).setAction(SNOOZE),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT);return NotificationCompat.Builder(this,if(alarm)ALARM_CH else STATUS_CH).setSmallIcon(android.R.drawable.ic_dialog_map).setContentTitle(if(alarm)l("Anchor by Yokuli alarm","Yokuli 锚警") else if(mockGps.status.value.state==MockGpsState.ACTIVE)l("NMEA GPS Proxy","NMEA GPS 代理") else l("Anchor by Yokuli active","Yokuli 锚警运行中")).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(silent).setPriority(if(alarm)NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW).setCategory(if(alarm)NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_SERVICE).apply{if(alarm){setFullScreenIntent(open,true);addAction(0,l("SNOOZE ${alarmSnoozeMinutes} MIN","${alarmSnoozeMinutes} 分钟后提醒"),snooze)}}.build()}
 private fun notifySeparate(title:String,text:String,high:Boolean){getSystemService(NotificationManager::class.java).notify(EVENT,NotificationCompat.Builder(this,if(high)EVENT_CH else STATUS_CH).setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(serviceMessage(title)).setContentText(serviceMessage(text)).setAutoCancel(true).setPriority(if(high)NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT).build())}

 private fun sound(){
  if(alarmPlayer?.isPlaying==true)return
  val builtIn=anchorAlarmUri();val systemFallback=android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
  val selected=if(selectedAlarmSound==AlarmSound.CUSTOM)customAlarmSoundUri?.let{runCatching{android.net.Uri.parse(it)}.getOrNull()} else builtIn
  if(!playAlarm(selected)&&!playAlarm(builtIn))playAlarm(systemFallback)
  getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createWaveform(longArrayOf(0,800,400),0))
 }
 private fun anchorAlarmUri():android.net.Uri?=runCatching{
  val file=java.io.File(cacheDir,"yokuli-anchor-alarm.wav")
  if(!file.exists()||file.length()<1_000){val rate=22_050;val seconds=4;val samples=rate*seconds;val pcmBytes=samples*2;val bytes=java.nio.ByteBuffer.allocate(44+pcmBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);bytes.put("RIFF".toByteArray());bytes.putInt(36+pcmBytes);bytes.put("WAVEfmt ".toByteArray());bytes.putInt(16);bytes.putShort(1.toShort());bytes.putShort(1.toShort());bytes.putInt(rate);bytes.putInt(rate*2);bytes.putShort(2.toShort());bytes.putShort(16.toShort());bytes.put("data".toByteArray());bytes.putInt(pcmBytes);for(index in 0 until samples){val time=index.toDouble()/rate;val frequency=if(((time/.42).toInt()%2)==0)760.0 else 1040.0;val pulse=.45+.55*kotlin.math.sin(2.0*Math.PI*time*2.0).let{kotlin.math.abs(it)};val value=(kotlin.math.sin(2.0*Math.PI*frequency*time)*Short.MAX_VALUE*.58*pulse).toInt().coerceIn(Short.MIN_VALUE.toInt(),Short.MAX_VALUE.toInt());bytes.putShort(value.toShort())};file.outputStream().use{it.write(bytes.array())}}
  android.net.Uri.fromFile(file)
 }.getOrNull()
 private fun playAlarm(uri:android.net.Uri?):Boolean{
  if(uri==null)return false
  val player=MediaPlayer()
  return runCatching{player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());if(uri.scheme=="file")player.setDataSource(requireNotNull(uri.path))else player.setDataSource(this,uri);player.isLooping=true;player.prepare();player.start();alarmPlayer=player;true}.getOrElse{runCatching{player.release()};false}
 }
 private fun silence(){alarmPlayer?.release();alarmPlayer=null;getSystemService(Vibrator::class.java).cancel()}
 private suspend fun snoozeAlarm(){
  val current=session?:return
  if(current.paused||lastSnapshot?.type==null)return
  val minutes=preferences.settings.first().alarmSnoozeMinutes
  val until=AlarmReminderPolicy.snoozeUntil(System.currentTimeMillis(),minutes)
  val updated=current.copy(alarmSnoozedUntil=until);dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=System.currentTimeMillis(),type="ALARM_SNOOZED",detail="${minutes}m"));session=updated;dao.updateSession(updated);lastSnapshot=engine.acknowledge();alarmUi.publish(lastSnapshot!!);silence();refreshNotification()
 }
 private fun locks(keepWifi:Boolean){if(wake==null)wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"anchorwatch:monitor").apply{setReferenceCounted(false);acquire()};if(keepWifi&&wifi==null)wifi=getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"anchorwatch:nmea").apply{setReferenceCounted(false);acquire()}}
 private suspend fun pauseWatch(){
  val current=session?:return
  if(current.paused)return
  val updated=current.copy(paused=true,alarmSnoozedUntil=null);session=updated;dao.updateSession(updated);dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=System.currentTimeMillis(),type="SESSION_PAUSED"))
  if(currentGpsSource==GpsDataSource.DEMO)demoLocation.pause();phoneHeading.stop()
  engine.stop();lastSnapshot=AlarmSnapshot(AlarmState.STOPPED);alarmUi.clear();lastReportedAlarm=null;nmeaLossAnnounced=false;clearPositionDegraded();silence();getSystemService(NotificationManager::class.java).cancel(EVENT);refreshNotification();releaseIfIdle()
 }
 private suspend fun resumeWatch(){
  val current=(session?:dao.active())?:return
  if(!current.paused){session=current;refreshNotification();return}
  val settings=preferences.settings.first();locks(settings.keepWifiAwake)
  val lossMillis=settings.gpsLossSeconds*1000L
  val fix=when(settings.gpsDataSource){
   GpsDataSource.NMEA->{navigation.acquireBackgroundConnection(settings.profile);withTimeoutOrNull(10_000){navigation.connectionState.first{it==NmeaConnectionState.CONNECTED};navigation.fix.filterNotNull().filter{it.valid&&SystemClock.elapsedRealtime()-it.receivedElapsedRealtime<lossMillis}.first()}}
   GpsDataSource.SYSTEM->{if(!enableSystemGps())null else withTimeoutOrNull(10_000){systemLocation.fix.filterNotNull().filter{it.valid&&SystemClock.elapsedRealtime()-it.receivedElapsedRealtime<lossMillis}.first()}}
   GpsDataSource.DEMO->{if(!enableSystemGps())null else demoLocation.resume()?:demoLocation.start(current.learningReferenceLatitude?:current.anchorLatitude,current.learningReferenceLongitude?:current.anchorLongitude,runCatching{AnchorPlacementMode.valueOf(current.placementMode)}.getOrDefault(AnchorPlacementMode.CENTER_DROP),settings.demoScenario,current.alarmRadiusMeters,settings.demoSpeedMultiplier,seed=demoSeed(current))}
  }
  if(fix==null){notifySeparate("Anchor watch remains paused","A fresh ${when(settings.gpsDataSource){GpsDataSource.NMEA->"NMEA";GpsDataSource.SYSTEM->"System";GpsDataSource.DEMO->"Demo"}} GPS position is required before resuming.",true);session=current;releaseIfIdle();return}
  val resumedAt=SystemClock.elapsedRealtime();val updated=current.copy(paused=false,alarmSnoozedUntil=null);session=updated;dao.updateSession(updated);positionIntegrity.reset();clearPositionDegraded();if(updated.usePhoneHeading)phoneHeading.start();engine=alarmEngine(settings);lastSnapshot=if(updated.centerStatus!=AnchorCenterStatus.RESOLVED.name)engine.learn(updated.learningConfig(),resumedAt)else engine.arm(updated.config(),resumedAt);handleFix(fix,settings.gpsDataSource);dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=System.currentTimeMillis(),type="SESSION_RESUMED"));notifySeparate("Anchor watch resumed","The existing anchor centre, track and alarm range were preserved.",false)
 }
 private suspend fun liftAnchor(){
  val current=session?:dao.active()?:return;val now=System.currentTimeMillis();dao.insertEvent(AlarmEventEntity(sessionId=current.id,timestamp=now,type="ANCHOR_LIFTED"));dao.updateSession(current.copy(active=false,paused=false,endedAt=now,alarmSnoozedUntil=null));if(currentGpsSource==GpsDataSource.DEMO)demoLocation.stop();phoneHeading.stop();session=null;backdownSamples.clear();candidateDriftDetector.reset();positionIntegrity.reset();clearPositionDegraded();engine.stop();lastSnapshot=null;alarmUi.clear();lastReportedAlarm=null;nmeaLossAnnounced=false;silence();getSystemService(NotificationManager::class.java).cancel(EVENT);refreshNotification();releaseIfIdle()
 }
 private suspend fun updateWatchSettings(intent:Intent){
  val current=session?:return;val wasAlarm=lastSnapshot?.state==AlarmState.ALARM;val alarm=intent.getDoubleExtra("alarm",current.alarmRadiusMeters).takeIf{it>0}?:return;val warning=maxOf(alarm*.8,alarm-10).coerceAtMost(alarm-.1)
  val settings=preferences.settings.first();val snoozedUntil=if(wasAlarm||lastSnapshot?.state==AlarmState.ACKNOWLEDGED)AlarmReminderPolicy.snoozeUntil(System.currentTimeMillis(),settings.alarmSnoozeMinutes)else null
  val updated=current.copy(alarmRadiusMeters=alarm,warningRadiusMeters=warning,alarmSnoozedUntil=snoozedUntil)
  session=updated;dao.updateSession(updated);silence();lastReportedAlarm=null
  if(!updated.paused){val resetAt=SystemClock.elapsedRealtime();engine=alarmEngine(settings);lastSnapshot=if(updated.centerStatus!=AnchorCenterStatus.RESOLVED.name)engine.learn(updated.learningConfig(),resetAt)else engine.arm(updated.config(),resetAt);positionIntegrity.reset();clearPositionDegraded();val fix=when(settings.gpsDataSource){GpsDataSource.NMEA->navigation.fix.value;GpsDataSource.SYSTEM->systemLocation.fix.value;GpsDataSource.DEMO->demoLocation.fix.value};val lastFix=if(settings.gpsDataSource==GpsDataSource.NMEA)navigation.diagnostics.value.lastFixElapsed else fix?.receivedElapsedRealtime;if(fix?.valid==true&&lastFix!=null&&resetAt-lastFix<settings.gpsLossSeconds*1000L)handleFix(fix,settings.gpsDataSource)}
  dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=System.currentTimeMillis(),type="ALARM_RANGE_CHANGED",detail="${current.alarmRadiusMeters.toInt()}m_TO_${alarm.toInt()}m"));if(wasAlarm&&lastSnapshot?.state!=AlarmState.ALARM)dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=System.currentTimeMillis(),type="ALARM_CLEARED_BY_RANGE_CHANGE",detail="${alarm.toInt()}m"));notifySeparate("Anchor range updated","Alarm radius is now ${alarm.toInt()} m for this session.",false);refreshNotification();releaseIfIdle()
 }
 private fun enableSystemGps():Boolean{if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return false;val ready=runCatching{ServiceCompat.startForeground(this,ONGOING,notification("System GPS anchor monitoring…",false),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)}.isSuccess;if(ready)systemLocation.setBackgroundEnabled(true);return ready}
 private fun releaseIfIdle(){if(session?.paused!=false&&mockGps.status.value.state!=MockGpsState.ACTIVE&&!sharingEnabled&&!armPending){wake?.takeIf{it.isHeld}?.release();wifi?.takeIf{it.isHeld}?.release();wake=null;wifi=null;navigation.releaseBackgroundConnection();systemLocation.setBackgroundEnabled(false);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}}
 private suspend fun logEvent(type:String,detail:String){session?.let{dao.insertEvent(AlarmEventEntity(sessionId=it.id,timestamp=System.currentTimeMillis(),type=type,detail=detail))}}
 private fun alarmEngine(settings:com.yokuli.anchorwatch.data.preferences.AppSettings)=AlarmEngine(settings.alarmPersistenceSeconds*1000L,gpsLossMillis=settings.gpsLossSeconds*1000L)
 private fun cleanup(){silence();wake?.takeIf{it.isHeld}?.release();wifi?.takeIf{it.isHeld}?.release();wake=null;wifi=null}
 override fun onDestroy(){sharingServer.stop();scope.cancel();navigation.releaseBackgroundConnection();systemLocation.setBackgroundEnabled(false);phoneHeading.stop();if(mockGps.status.value.state==MockGpsState.ACTIVE||mockGps.status.value.state==MockGpsState.STARTING)runBlocking(Dispatchers.IO){withTimeoutOrNull(2000){mockGps.stop()}};cleanup();super.onDestroy()};override fun onBind(intent:Intent?)=null
 private fun channels(){getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(NotificationChannel(STATUS_CH,l("Anchor and GPS status","锚警与 GPS 状态"),NotificationManager.IMPORTANCE_LOW),NotificationChannel(EVENT_CH,l("Anchor safety events","锚泊安全事件"),NotificationManager.IMPORTANCE_DEFAULT),NotificationChannel(ALARM_CH,l("Anchor alarms with snooze","带稍后提醒的锚警"),NotificationManager.IMPORTANCE_HIGH).apply{setSound(null,null);enableVibration(false)}))}
 private fun l(english:String,chinese:String)=localized(appLanguage,english,chinese)
 private fun demoSeed(active:AnchorSessionEntity)=active.startedAt xor (active.id shl 17)
 private fun serviceMessage(message:String):String{
  if(!appLanguage.usesChinese())return message
  return when{
   message=="Anchor session already open"->"已有锚泊会话"
   message=="Pause, resume or lift the current anchor before starting another session."->"开始新会话前，请暂停、继续或结束当前锚泊。"
   message=="Anchor watch not started"->"锚警未启动"
   message.startsWith("A live, current ")->"需要实时且新鲜的 GPS 定位才能启动锚警；现有连接没有改变。"
   message.startsWith("The selected setup requires valid water depth")->"当前设置需要有效的水深、锚链、船艏高度和船长；锚链必须长于总垂直深度。"
   message=="Android GPS proxy not active"->"Android GPS 代理未开启"
   message.startsWith("Grant Fine location permission")->"开启 GPS 代理前请授予精确位置权限。"
   message.startsWith("Open the app and grant location permission")->"请打开应用并授予位置权限，然后再开启 GPS 代理。"
   message=="Android GPS restored"->"Android GPS 已恢复"
   message=="GPS source not changed"->"GPS 数据源未切换"
   message=="GPS source changed"->"GPS 数据源已切换"
   message.startsWith("Precise location permission and an available System GNSS provider")->"切换前需要精确位置权限以及可用的系统 GNSS。"
   message.startsWith("NMEA stayed selected because a fresh")->"没有可用的新鲜精确系统 GNSS 定位；锚警仍使用 NMEA。"
   message.startsWith("System GNSS is now monitoring")->"系统 GNSS 已接管同一锚泊会话；锚中心、范围与轨迹均已保留。"
   message.startsWith("NMEA GPS is now monitoring")->"NMEA GPS 已接管同一锚泊会话；锚中心、范围与轨迹均已保留。"
   message.startsWith("Demo mode locks this App")->"演示模式已锁定本应用使用演示 GPS；请先起锚并关闭演示模式。"
   message.startsWith("Demo GPS remains locked")->"当前锚泊会话仍锁定演示 GPS；请先起锚，再退出演示模式。"
   message=="Connect the NMEA source and wait for a fresh valid position before selecting NMEA GPS."->"请先连接 NMEA 数据源并等待新鲜有效的定位，然后才能选择 NMEA GPS。"
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
   message=="Anchor range not updated"->"锚警范围未更新"
   message.startsWith("The selected setup still requires valid water depth")->"当前设置仍需要有效的水深、锚链、船艏高度和船长。"
   message.startsWith("Alarm radius is now ")->message.replace("Alarm radius is now ","本次会话的报警半径现为 ").replace(" m for this session."," 米。")
   else->message
  }
 }
 private fun AnchorSessionEntity.config()=AnchorConfig(anchorLatitude,anchorLongitude,rodeLengthMeters,waterDepthMeters,bowRollerHeightMeters,gpsAntennaOffsetMeters,warningRadiusMeters,alarmRadiusMeters)
 private fun AnchorSessionEntity.learningConfig()=AnchorConfig(learningReferenceLatitude?:anchorLatitude,learningReferenceLongitude?:anchorLongitude,rodeLengthMeters,waterDepthMeters,bowRollerHeightMeters,gpsAntennaOffsetMeters,warningRadiusMeters,alarmRadiusMeters)
 private inline fun <reified T:Enum<T>>enumExtra(intent:Intent,key:String,default:T)=runCatching{enumValueOf<T>(intent.getStringExtra(key)?:default.name)}.getOrDefault(default)
 companion object{const val ARM="com.yokuli.anchorwatch.ARM";const val ACK="com.yokuli.anchorwatch.ACK";const val SNOOZE="com.yokuli.anchorwatch.SNOOZE";const val STOP_WATCH="com.yokuli.anchorwatch.STOP_WATCH";const val PAUSE_WATCH="com.yokuli.anchorwatch.PAUSE_WATCH";const val RESUME_WATCH="com.yokuli.anchorwatch.RESUME_WATCH";const val LIFT_ANCHOR="com.yokuli.anchorwatch.LIFT_ANCHOR";const val UPDATE_RADIUS="com.yokuli.anchorwatch.UPDATE_RADIUS";const val STOP_WATCH_AND_DISCONNECT="com.yokuli.anchorwatch.STOP_WATCH_AND_DISCONNECT";const val SWITCH_WATCH_TO_SYSTEM="com.yokuli.anchorwatch.SWITCH_WATCH_TO_SYSTEM";const val SWITCH_WATCH_SOURCE_SYSTEM="com.yokuli.anchorwatch.SWITCH_WATCH_SOURCE_SYSTEM";const val SWITCH_WATCH_SOURCE_NMEA="com.yokuli.anchorwatch.SWITCH_WATCH_SOURCE_NMEA";const val ACCEPT_ESTIMATED_CENTER="com.yokuli.anchorwatch.ACCEPT_ESTIMATED_CENTER";const val REJECT_ESTIMATED_CENTER="com.yokuli.anchorwatch.REJECT_ESTIMATED_CENTER";const val DISABLE_DEMO_TO_SYSTEM="com.yokuli.anchorwatch.DISABLE_DEMO_TO_SYSTEM";const val START_PROXY="com.yokuli.anchorwatch.START_PROXY";const val STOP_PROXY="com.yokuli.anchorwatch.STOP_PROXY";const val TEST_ALARM="com.yokuli.anchorwatch.TEST_ALARM";const val STOP_ALARM_TEST="com.yokuli.anchorwatch.STOP_ALARM_TEST";const val SET_NMEA_SHARING="com.yokuli.anchorwatch.SET_NMEA_SHARING";const val STATUS_CH="anchor_status";const val EVENT_CH="anchor_events_v2";const val ALARM_CH="anchor_alarm_selectable_v2";const val ONGOING=42;const val EVENT=43}
}
