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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@AndroidEntryPoint
class AnchorForegroundService:Service(){
 @Inject lateinit var navigation:NavigationRepository;@Inject lateinit var dao:AnchorDao;@Inject lateinit var preferences:SettingsRepository;@Inject lateinit var mockGps:GlobalMockLocationManager;@Inject lateinit var systemLocation:SystemLocationRepository
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);private val commandMutex=Mutex();private val proxyMutex=Mutex();private var wake:PowerManager.WakeLock?=null;private var wifi:WifiManager.WifiLock?=null;private var alarmPlayer:MediaPlayer?=null;private var engine=AlarmEngine();private var session:AnchorSessionEntity?=null;private var lastSnapshot:AlarmSnapshot?=null;private var lastTrack=0L;private var proxyPolicy:MockGpsPolicy?=null;private var lowBatteryReported=false;private var lastReportedAlarm:AlarmType?=null;private var nmeaLossAnnounced=false;private var currentGpsSource=GpsDataSource.SYSTEM;private val centerEstimator=AnchorCenterEstimator();private val backdownEstimator=BackdownCenterEstimator();private val centerPoints=mutableListOf<AnchorCenterEstimator.Point>();private val backdownSamples=mutableListOf<BackdownCenterEstimator.Sample>();private var lastCenterUpdate=0L;@Volatile private var armPending=false

 private data class SourcedFix(val source:GpsDataSource,val fix:NavigationFix)
 private data class ArmRequest(val config:AnchorConfig,val placement:AnchorPlacementMode,val rangeMode:AnchorRangeMode,val safetyPreset:AnchorSafetyPreset,val boatLength:Double?)

 override fun onCreate(){
  super.onCreate();channels();ServiceCompat.startForeground(this,ONGOING,notification("Starting safety monitor…",false),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
  scope.launch{commandMutex.withLock{restoreState()}}
  scope.launch{combine(preferences.settings.map{it.gpsDataSource}.distinctUntilChanged(),navigation.fix,systemLocation.fix){source,nmea,system->currentGpsSource=source;(if(source==GpsDataSource.NMEA)nmea else system)?.let{SourcedFix(source,it)}}.filterNotNull().collect{handleFix(it.fix,it.source)}}
  scope.launch{navigation.connectionState.collect(::handleNmeaState)}
  scope.launch{while(isActive){delay(1000);watchdog()}}
  scope.launch{while(isActive){delay(30_000);batteryWatchdog()}}
 }

 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  when(intent?.action){
   ARM->{armPending=true;val c=AnchorConfig(intent.getDoubleExtra("lat",0.0),intent.getDoubleExtra("lon",0.0),intent.getDoubleExtra("rode",0.0),intent.getDoubleExtra("depth",Double.NaN).takeUnless{it.isNaN()},warningRadiusMeters=intent.getDoubleExtra("warning",40.0),alarmRadiusMeters=intent.getDoubleExtra("alarm",50.0));val request=ArmRequest(c,enumExtra(intent,"placement",AnchorPlacementMode.CENTER_DROP),enumExtra(intent,"rangeMode",AnchorRangeMode.BASIC),enumExtra(intent,"safetyPreset",AnchorSafetyPreset.BALANCED),intent.getDoubleExtra("boatLength",Double.NaN).takeUnless{it.isNaN()});scope.launch{commandMutex.withLock{try{arm(request)}finally{armPending=false;releaseIfIdle()}}}}
   ACK->{lastSnapshot=engine.acknowledge();silence();refreshNotification();scope.launch{logEvent("ALARM_ACKNOWLEDGED","")}}
   STOP_WATCH,PAUSE_WATCH->scope.launch{commandMutex.withLock{pauseWatch()}}
   RESUME_WATCH->scope.launch{commandMutex.withLock{resumeWatch()}}
   LIFT_ANCHOR->scope.launch{commandMutex.withLock{liftAnchor()}}
   UPDATE_RADIUS->scope.launch{commandMutex.withLock{updateWatchSettings(intent)}}
   STOP_WATCH_AND_DISCONNECT->scope.launch{commandMutex.withLock{stopWatchAndDisconnect()}}
   SWITCH_WATCH_TO_SYSTEM->scope.launch{commandMutex.withLock{switchWatchToSystem(true)}}
   SWITCH_WATCH_SOURCE_SYSTEM->scope.launch{commandMutex.withLock{switchWatchToSystem(false)}}
   SWITCH_WATCH_SOURCE_NMEA->scope.launch{commandMutex.withLock{switchWatchToNmea()}}
   START_PROXY->scope.launch{commandMutex.withLock{startProxy()}}
   STOP_PROXY->scope.launch{commandMutex.withLock{stopProxy("Android GPS proxy stopped by user.")}}
  }
  return START_STICKY
 }

 private suspend fun restoreState(){
  val settings=preferences.settings.first();currentGpsSource=settings.gpsDataSource;session=dao.active();session?.let{active->
   engine=alarmEngine(settings);val points=dao.points(active.id).first()
   if(active.centerStatus==AnchorCenterStatus.LEARNING.name){backdownSamples.addAll(points.map{BackdownCenterEstimator.Sample(it.latitude,it.longitude,it.timestamp,it.hdop)});lastSnapshot=engine.learn(active.config(),SystemClock.elapsedRealtime())}
   else{centerPoints.addAll(points.filter{active.centerResolvedAt==null||it.timestamp>=active.centerResolvedAt}.map{AnchorCenterEstimator.Point(it.latitude,it.longitude)});lastSnapshot=engine.arm(active.config(),SystemClock.elapsedRealtime())}
   if(!active.paused)locks(settings.keepWifiAwake)
  }
  if(session?.paused==false){if(settings.gpsDataSource==GpsDataSource.NMEA)navigation.acquireBackgroundConnection(settings.profile)else enableSystemGps()}
  if(settings.mockEnabled&&settings.gpsDataSource==GpsDataSource.NMEA){navigation.acquireBackgroundConnection(settings.profile);locks(settings.keepWifiAwake);startProxy()}
  else if(settings.mockEnabled){preferences.setMockEnabled(false);mockGps.stop("System GPS selected — global NMEA proxy disabled.")}
  handleNmeaState(navigation.connectionState.value)
  refreshNotification()
 }

 private suspend fun arm(request:ArmRequest){
  if(session!=null){notifySeparate("Anchor session already open","Pause, resume or lift the current anchor before starting another session.",true);return}
  val c=request.config
  val settings=preferences.settings.first();val now=SystemClock.elapsedRealtime()
  val sourceReady=if(settings.gpsDataSource==GpsDataSource.NMEA)navigation.connectionState.value==NmeaConnectionState.CONNECTED else enableSystemGps()
  val latestFix=if(settings.gpsDataSource==GpsDataSource.NMEA)navigation.fix.value else systemLocation.fix.value
  val lastFix=if(settings.gpsDataSource==GpsDataSource.NMEA)navigation.diagnostics.value.lastFixElapsed else latestFix?.receivedElapsedRealtime
  val freshFix=sourceReady&&latestFix?.valid==true&&lastFix!=null&&now-lastFix<settings.gpsLossSeconds*1000L
  if(!freshFix){notifySeparate("Anchor watch not started","A live, current ${if(settings.gpsDataSource==GpsDataSource.NMEA)"NMEA" else "system"} GPS fix is required. Existing connections were left unchanged.",false);refreshNotification();releaseIfIdle();return}
  val wallNow=System.currentTimeMillis();val learning=request.placement==AnchorPlacementMode.BACKDOWN
  val entity=AnchorSessionEntity(startedAt=wallNow,anchorLatitude=c.latitude,anchorLongitude=c.longitude,rodeLengthMeters=c.rodeLengthMeters,waterDepthMeters=c.waterDepthMeters,bowRollerHeightMeters=c.bowRollerHeightMeters,gpsAntennaOffsetMeters=c.gpsAntennaOffsetMeters,expectedSwingRadiusMeters=c.alarmRadiusMeters,warningRadiusMeters=c.warningRadiusMeters,alarmRadiusMeters=c.alarmRadiusMeters,placementMode=request.placement.name,centerStatus=if(learning)AnchorCenterStatus.LEARNING.name else AnchorCenterStatus.RESOLVED.name,centerResolvedAt=if(learning)null else wallNow,centerConfidence=if(learning)Confidence.LOW.name else Confidence.HIGH.name,centerSampleCount=if(learning)0 else 1,boatLengthMeters=request.boatLength,rangeMode=request.rangeMode.name,safetyPreset=request.safetyPreset.name)
  session=entity.copy(id=dao.insertSession(entity));dao.insertEvent(AlarmEventEntity(sessionId=session!!.id,timestamp=wallNow,type=if(learning)"SESSION_STARTED_CENTER_LEARNING" else "SESSION_STARTED"));centerPoints.clear();backdownSamples.clear();silence();lastReportedAlarm=null;engine=alarmEngine(settings);lastSnapshot=if(learning)engine.learn(c,now)else engine.arm(c,now);locks(settings.keepWifiAwake);if(settings.gpsDataSource==GpsDataSource.NMEA)navigation.acquireBackgroundConnection(settings.profile);updateAlarm(engine.onFix(latestFix!!))
 }

 private suspend fun startProxy()=proxyMutex.withLock{
  val settings=preferences.settings.first();if(settings.gpsDataSource!=GpsDataSource.NMEA){preferences.setMockEnabled(false);proxyPolicy=null;mockGps.stop("System GPS selected — global NMEA proxy disabled.");refreshNotification();releaseIfIdle();return@withLock};navigation.acquireBackgroundConnection(settings.profile);locks(settings.keepWifiAwake);proxyPolicy=MockGpsPolicy(settings.gpsLossSeconds*1000L,settings.mockHz).apply{start(SystemClock.elapsedRealtime())}
  if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){preferences.setMockEnabled(false);proxyPolicy=null;mockGps.stop("GPS proxy was not enabled. Fine location permission is required; Android GPS is using its normal source.");notifySeparate("Android GPS proxy not active","Grant Fine location permission before enabling GPS proxy.",true);refreshNotification();releaseIfIdle();return@withLock}
  val foregroundReady=runCatching{ServiceCompat.startForeground(this,ONGOING,notification("Starting NMEA → Android GPS…",false),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)}.isSuccess
  if(!foregroundReady){preferences.setMockEnabled(false);proxyPolicy=null;mockGps.stop("GPS proxy was not enabled. Android did not allow a location foreground service; Android GPS is using its normal source.");notifySeparate("Android GPS proxy not active","Open the app and grant location permission before enabling GPS proxy.",true);refreshNotification();releaseIfIdle();return@withLock}
  val result=mockGps.start(settings.enhancedMock)
  val enabled=result.state==MockGpsState.ACTIVE
  preferences.setMockEnabled(enabled)
  if(!enabled){proxyPolicy=null;mockGps.stop("${result.message} Android GPS is using its normal source.")}
  if(enabled)logEvent("GPS_PROXY_STARTED",result.message) else notifySeparate("Android GPS proxy not active",result.message,true)
  refreshNotification();releaseIfIdle()
 }

 private suspend fun stopProxy(message:String)=proxyMutex.withLock{mockGps.stop(message);preferences.setMockEnabled(false);proxyPolicy=null;notifySeparate("Android GPS restored",message,false);refreshNotification();releaseIfIdle()}

 private suspend fun switchWatchToSystem(disconnectNmea:Boolean){
  val active=session
  val settings=preferences.settings.first()
  if(settings.gpsDataSource!=GpsDataSource.NMEA){if(disconnectNmea)navigation.disconnectAll();refreshNotification();return}
  if(GpsSourceSafety.blocksSystemGps(settings.mockEnabled,mockGps.status.value.state)){notifySeparate("GPS source not changed","Disable the global NMEA GPS proxy before selecting System GPS. Android mock mode replaces the system fused location source.",true);refreshNotification();return}
  if(active==null||active.paused){currentGpsSource=GpsDataSource.SYSTEM;preferences.save(settings.copy(gpsDataSource=GpsDataSource.SYSTEM,mockEnabled=false));if(disconnectNmea)navigation.disconnectAll()else navigation.releaseBackgroundConnection();refreshNotification();releaseIfIdle();return}
  if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
   notifySeparate("NMEA was not disconnected","Grant precise location permission before switching an active anchor watch to System GPS.",true);refreshNotification();return
  }
  if(!enableSystemGps()){notifySeparate("NMEA was not disconnected","Android did not allow System GPS monitoring. Anchor watch is still using NMEA.",true);return}
  val lossMillis=settings.gpsLossSeconds*1000L
  val systemFix=withTimeoutOrNull(10_000){systemLocation.fix.filterNotNull().filter{it.valid&&SystemClock.elapsedRealtime()-it.receivedElapsedRealtime<lossMillis}.first()}
  if(systemFix==null){
   systemLocation.setBackgroundEnabled(false)
   notifySeparate("NMEA was not disconnected","No fresh System GPS fix was available. Anchor watch is still using NMEA.",true);refreshNotification();return
  }
  currentGpsSource=GpsDataSource.SYSTEM;nmeaLossAnnounced=false
  preferences.save(settings.copy(gpsDataSource=GpsDataSource.SYSTEM,mockEnabled=false))
  updateAlarm(engine.onFix(systemFix))
  if(disconnectNmea)navigation.disconnectAll()else navigation.releaseBackgroundConnection()
  dao.insertEvent(AlarmEventEntity(sessionId=active.id,timestamp=System.currentTimeMillis(),type="WATCH_GPS_SOURCE_CHANGED",detail="NMEA_TO_SYSTEM"))
  notifySeparate("Anchor watch switched to System GPS",if(disconnectNmea)"NMEA was disconnected only after a fresh System GPS position was acquired." else "A fresh, non-mock System GPS position was acquired before the watch switched.",false)
  refreshNotification()
 }

 private suspend fun switchWatchToNmea(){
  val active=session;val settings=preferences.settings.first()
  if(settings.gpsDataSource==GpsDataSource.NMEA){refreshNotification();return}
  if(active==null||active.paused){currentGpsSource=GpsDataSource.NMEA;preferences.save(settings.copy(gpsDataSource=GpsDataSource.NMEA));systemLocation.setBackgroundEnabled(false);refreshNotification();releaseIfIdle();return}
  navigation.acquireBackgroundConnection(settings.profile)
  val lossMillis=settings.gpsLossSeconds*1000L
  val nmeaFix=withTimeoutOrNull(10_000){
   navigation.connectionState.first{it==NmeaConnectionState.CONNECTED}
   navigation.fix.filterNotNull().filter{it.valid&&SystemClock.elapsedRealtime()-it.receivedElapsedRealtime<lossMillis}.first()
  }
  if(nmeaFix==null){navigation.releaseBackgroundConnection();notifySeparate("GPS source not changed","No fresh NMEA position was available. Anchor watch is still using System GPS.",true);refreshNotification();return}
  currentGpsSource=GpsDataSource.NMEA;nmeaLossAnnounced=false
  preferences.save(settings.copy(gpsDataSource=GpsDataSource.NMEA))
  updateAlarm(engine.onFix(nmeaFix));systemLocation.setBackgroundEnabled(false)
  dao.insertEvent(AlarmEventEntity(sessionId=active.id,timestamp=System.currentTimeMillis(),type="WATCH_GPS_SOURCE_CHANGED",detail="SYSTEM_TO_NMEA"))
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
     val estimate=backdownEstimator.estimateSamples(backdownSamples)
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
  val updated=current.copy(anchorLatitude=estimate.latitude,anchorLongitude=estimate.longitude,centerStatus=AnchorCenterStatus.RESOLVED.name,centerResolvedAt=wallNow,centerConfidence=estimate.confidence.name,centerSampleCount=estimate.sampleCount)
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
  val now=SystemClock.elapsedRealtime();session?.takeIf{!it.paused}?.let{updateAlarm(engine.tick(now))}
  if(mockGps.status.value.state==MockGpsState.ACTIVE&&proxyPolicy?.isStale(now)==true){mockGps.stale();proxyPolicy=null;preferences.setMockEnabled(false);notifySeparate("NMEA GPS lost","Android GPS restored to its normal source.",true);logEvent("GPS_PROXY_STALE","");releaseIfIdle()}
  refreshNotification()
 }

 private suspend fun batteryWatchdog(){
  if(session?.paused!=false&&mockGps.status.value.state!=MockGpsState.ACTIVE)return
  val percent=getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
  if(percent in 0..15&&!lowBatteryReported){lowBatteryReported=true;notifySeparate("Low battery: $percent%","Connect this monitoring device to reliable power.",true);logEvent("LOW_BATTERY","$percent")}
  if(percent>20)lowBatteryReported=false
 }

 private fun updateAlarm(snapshot:AlarmSnapshot){lastSnapshot=snapshot;val critical=snapshot.state==AlarmState.ALARM;if(critical&&!snapshot.acknowledged)sound();if(critical&&snapshot.type!=lastReportedAlarm){lastReportedAlarm=snapshot.type;scope.launch{logEvent("ALARM_TRIGGERED",snapshot.type?.name?:"")}};if(!critical)lastReportedAlarm=null;refreshNotification(snapshot)}
 private fun refreshNotification(snapshot:AlarmSnapshot?=lastSnapshot){val proxy=mockGps.status.value;val active=session;val text=when{snapshot?.type==AlarmType.GPS_DATA_LOST&&active?.paused==false->"GPS DATA LOST";snapshot?.type==AlarmType.ANCHOR_RADIUS_EXCEEDED&&active?.paused==false->"ANCHOR ALARM ${snapshot.distanceMeters?.toInt()} m";active?.paused==true&&proxy.state==MockGpsState.ACTIVE->"Anchor session paused • NMEA GPS proxy active";active?.paused==true->"Anchor session paused";active?.centerStatus==AnchorCenterStatus.LEARNING.name->"Watch active • learning anchor centre • ${backdownSamples.size} fixes";active!=null&&currentGpsSource==GpsDataSource.NMEA&&nmeaLossAnnounced->"Watch active • NMEA connection lost • reconnecting";active!=null&&currentGpsSource==GpsDataSource.SYSTEM->"Watch ${snapshot?.distanceMeters?.toInt()?:"--"} m • SYSTEM GPS";active!=null->"Watch ${snapshot?.distanceMeters?.toInt()?:"--"} m • NMEA ${navigation.connectionState.value.name}";proxy.state==MockGpsState.ACTIVE->"NMEA → Android GPS active • ${proxy.publishedFixes} fixes";else->"Safety monitor idle"};getSystemService(NotificationManager::class.java).notify(ONGOING,notification(text,active?.paused==false&&snapshot?.state==AlarmState.ALARM))}
 private fun notification(text:String,alarm:Boolean):Notification{val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT);val ack=PendingIntent.getService(this,1,Intent(this,AnchorForegroundService::class.java).setAction(ACK),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT);return NotificationCompat.Builder(this,if(alarm)ALARM_CH else STATUS_CH).setSmallIcon(android.R.drawable.ic_dialog_map).setContentTitle(if(alarm)"NMEA Anchor Alarm" else if(mockGps.status.value.state==MockGpsState.ACTIVE)"NMEA GPS Proxy" else "Anchor Watch Active").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setPriority(if(alarm)NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW).setCategory(if(alarm)NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_SERVICE).apply{if(alarm)addAction(0,"ACKNOWLEDGE",ack)}.build()}
 private fun notifySeparate(title:String,text:String,high:Boolean){getSystemService(NotificationManager::class.java).notify(EVENT,NotificationCompat.Builder(this,if(high)ALARM_CH else STATUS_CH).setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(title).setContentText(text).setAutoCancel(true).setPriority(if(high)NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT).build())}

 private fun sound(){if(alarmPlayer?.isPlaying==true)return;runCatching{alarmPlayer=MediaPlayer().apply{setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());setDataSource(this@AnchorForegroundService,android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI);isLooping=true;prepare();start()};getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createWaveform(longArrayOf(0,800,400),0))}}
 private fun silence(){alarmPlayer?.release();alarmPlayer=null;getSystemService(Vibrator::class.java).cancel()}
 private fun locks(keepWifi:Boolean){if(wake==null)wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"anchorwatch:monitor").apply{setReferenceCounted(false);acquire()};if(keepWifi&&wifi==null)wifi=getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"anchorwatch:nmea").apply{setReferenceCounted(false);acquire()}}
 private suspend fun pauseWatch(){
  val current=session?:return
  if(current.paused)return
  val updated=current.copy(paused=true);session=updated;dao.updateSession(updated);dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=System.currentTimeMillis(),type="SESSION_PAUSED"))
  engine.stop();lastSnapshot=AlarmSnapshot(AlarmState.STOPPED);lastReportedAlarm=null;nmeaLossAnnounced=false;silence();refreshNotification();releaseIfIdle()
 }
 private suspend fun resumeWatch(){
  val current=(session?:dao.active())?:return
  if(!current.paused){session=current;refreshNotification();return}
  val settings=preferences.settings.first();locks(settings.keepWifiAwake)
  val lossMillis=settings.gpsLossSeconds*1000L
  val fix=if(settings.gpsDataSource==GpsDataSource.NMEA){
   navigation.acquireBackgroundConnection(settings.profile)
   withTimeoutOrNull(10_000){navigation.connectionState.first{it==NmeaConnectionState.CONNECTED};navigation.fix.filterNotNull().filter{it.valid&&SystemClock.elapsedRealtime()-it.receivedElapsedRealtime<lossMillis}.first()}
  }else{
   if(!enableSystemGps())null else withTimeoutOrNull(10_000){systemLocation.fix.filterNotNull().filter{it.valid&&SystemClock.elapsedRealtime()-it.receivedElapsedRealtime<lossMillis}.first()}
  }
  if(fix==null){notifySeparate("Anchor watch remains paused","A fresh ${if(settings.gpsDataSource==GpsDataSource.NMEA)"NMEA" else "System"} GPS position is required before resuming.",true);session=current;releaseIfIdle();return}
  val updated=current.copy(paused=false);session=updated;dao.updateSession(updated);engine=alarmEngine(settings);lastSnapshot=if(updated.centerStatus==AnchorCenterStatus.LEARNING.name)engine.learn(updated.config(),SystemClock.elapsedRealtime())else engine.arm(updated.config(),SystemClock.elapsedRealtime());updateAlarm(engine.onFix(fix));dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=System.currentTimeMillis(),type="SESSION_RESUMED"));notifySeparate("Anchor watch resumed","The existing anchor centre, track and alarm range were preserved.",false)
 }
 private suspend fun liftAnchor(){
  val current=session?:dao.active()?:return;val now=System.currentTimeMillis();dao.insertEvent(AlarmEventEntity(sessionId=current.id,timestamp=now,type="ANCHOR_LIFTED"));dao.updateSession(current.copy(active=false,paused=false,endedAt=now));session=null;centerPoints.clear();backdownSamples.clear();engine.stop();lastSnapshot=null;lastReportedAlarm=null;nmeaLossAnnounced=false;silence();refreshNotification();releaseIfIdle()
 }
 private suspend fun updateWatchSettings(intent:Intent){
  val current=session?:return;val wasAlarm=lastSnapshot?.state==AlarmState.ALARM;val alarm=intent.getDoubleExtra("alarm",current.alarmRadiusMeters).takeIf{it>0}?:return;val warning=maxOf(alarm*.8,alarm-10).coerceAtMost(alarm-.1)
  val updated=current.copy(alarmRadiusMeters=alarm,warningRadiusMeters=warning,expectedSwingRadiusMeters=alarm,waterDepthMeters=intent.getDoubleExtra("depth",Double.NaN).takeUnless{it.isNaN()},rodeLengthMeters=intent.getDoubleExtra("rode",current.rodeLengthMeters),boatLengthMeters=intent.getDoubleExtra("boatLength",Double.NaN).takeUnless{it.isNaN()},rangeMode=enumExtra(intent,"rangeMode",runCatching{AnchorRangeMode.valueOf(current.rangeMode)}.getOrDefault(AnchorRangeMode.BASIC)).name,safetyPreset=enumExtra(intent,"safetyPreset",runCatching{AnchorSafetyPreset.valueOf(current.safetyPreset)}.getOrDefault(AnchorSafetyPreset.BALANCED)).name)
  session=updated;dao.updateSession(updated);silence();lastReportedAlarm=null
  if(!updated.paused){val settings=preferences.settings.first();engine=alarmEngine(settings);lastSnapshot=if(updated.centerStatus==AnchorCenterStatus.LEARNING.name)engine.learn(updated.config(),SystemClock.elapsedRealtime())else engine.arm(updated.config(),SystemClock.elapsedRealtime());val fix=if(settings.gpsDataSource==GpsDataSource.NMEA)navigation.fix.value else systemLocation.fix.value;fix?.takeIf{it.valid}?.let{updateAlarm(engine.onFix(it))}}
  dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=System.currentTimeMillis(),type="ALARM_RANGE_CHANGED",detail="${current.alarmRadiusMeters.toInt()}m_TO_${alarm.toInt()}m"));if(wasAlarm&&lastSnapshot?.state!=AlarmState.ALARM)dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=System.currentTimeMillis(),type="ALARM_CLEARED_BY_RANGE_CHANGE",detail="${alarm.toInt()}m"));notifySeparate("Anchor range updated","Alarm radius is now ${alarm.toInt()} m for this session.",false);refreshNotification();releaseIfIdle()
 }
 private fun enableSystemGps():Boolean{if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return false;val ready=runCatching{ServiceCompat.startForeground(this,ONGOING,notification("System GPS anchor monitoring…",false),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)}.isSuccess;if(ready)systemLocation.setBackgroundEnabled(true);return ready}
 private fun releaseIfIdle(){if(session?.paused!=false&&mockGps.status.value.state!=MockGpsState.ACTIVE&&!armPending){wake?.takeIf{it.isHeld}?.release();wifi?.takeIf{it.isHeld}?.release();wake=null;wifi=null;navigation.releaseBackgroundConnection();systemLocation.setBackgroundEnabled(false);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}}
 private suspend fun logEvent(type:String,detail:String){session?.let{dao.insertEvent(AlarmEventEntity(sessionId=it.id,timestamp=System.currentTimeMillis(),type=type,detail=detail))}}
 private fun alarmEngine(settings:com.yokuli.anchorwatch.data.preferences.AppSettings)=AlarmEngine(settings.alarmPersistenceSeconds*1000L,gpsLossMillis=settings.gpsLossSeconds*1000L)
 private fun cleanup(){silence();wake?.takeIf{it.isHeld}?.release();wifi?.takeIf{it.isHeld}?.release();wake=null;wifi=null}
 override fun onDestroy(){scope.cancel();navigation.releaseBackgroundConnection();systemLocation.setBackgroundEnabled(false);runBlocking(Dispatchers.IO){withTimeoutOrNull(2000){mockGps.stop()}};cleanup();super.onDestroy()};override fun onBind(intent:Intent?)=null
 private fun channels(){getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(NotificationChannel(STATUS_CH,"Anchor and GPS status",NotificationManager.IMPORTANCE_LOW),NotificationChannel(ALARM_CH,"Safety alarms",NotificationManager.IMPORTANCE_HIGH).apply{enableVibration(true)}))}
 private fun AnchorSessionEntity.config()=AnchorConfig(anchorLatitude,anchorLongitude,rodeLengthMeters,waterDepthMeters,bowRollerHeightMeters,gpsAntennaOffsetMeters,warningRadiusMeters,alarmRadiusMeters)
 private inline fun <reified T:Enum<T>>enumExtra(intent:Intent,key:String,default:T)=runCatching{enumValueOf<T>(intent.getStringExtra(key)?:default.name)}.getOrDefault(default)
 companion object{const val ARM="com.yokuli.anchorwatch.ARM";const val ACK="com.yokuli.anchorwatch.ACK";const val STOP_WATCH="com.yokuli.anchorwatch.STOP_WATCH";const val PAUSE_WATCH="com.yokuli.anchorwatch.PAUSE_WATCH";const val RESUME_WATCH="com.yokuli.anchorwatch.RESUME_WATCH";const val LIFT_ANCHOR="com.yokuli.anchorwatch.LIFT_ANCHOR";const val UPDATE_RADIUS="com.yokuli.anchorwatch.UPDATE_RADIUS";const val STOP_WATCH_AND_DISCONNECT="com.yokuli.anchorwatch.STOP_WATCH_AND_DISCONNECT";const val SWITCH_WATCH_TO_SYSTEM="com.yokuli.anchorwatch.SWITCH_WATCH_TO_SYSTEM";const val SWITCH_WATCH_SOURCE_SYSTEM="com.yokuli.anchorwatch.SWITCH_WATCH_SOURCE_SYSTEM";const val SWITCH_WATCH_SOURCE_NMEA="com.yokuli.anchorwatch.SWITCH_WATCH_SOURCE_NMEA";const val START_PROXY="com.yokuli.anchorwatch.START_PROXY";const val STOP_PROXY="com.yokuli.anchorwatch.STOP_PROXY";const val STATUS_CH="anchor_status";const val ALARM_CH="anchor_alarm";const val ONGOING=42;const val EVENT=43}
}
