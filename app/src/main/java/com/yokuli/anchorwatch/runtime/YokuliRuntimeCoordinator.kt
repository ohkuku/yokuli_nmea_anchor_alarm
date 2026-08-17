package com.yokuli.anchorwatch.runtime

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.data.AlarmUiRepository
import com.yokuli.anchorwatch.data.database.AnchorDao
import com.yokuli.anchorwatch.data.diagnostics.IncidentLogger
import com.yokuli.anchorwatch.data.diagnostics.IncidentSeverity
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.data.sonar.SonarSurveyRecorder
import com.yokuli.anchorwatch.domain.anchor.*
import com.yokuli.anchorwatch.domain.model.*
import com.yokuli.anchorwatch.domain.condition.ConditionAlarmSource
import com.yokuli.anchorwatch.domain.condition.ConditionRuntimeSnapshot
import com.yokuli.anchorwatch.domain.condition.DepthGuardStatus
import com.yokuli.anchorwatch.domain.condition.WindSpeedGuardStatus
import com.yokuli.anchorwatch.domain.condition.WindShiftGuardStatus
import com.yokuli.anchorwatch.domain.sonar.TideMode
import com.yokuli.anchorwatch.location.*
import com.yokuli.anchorwatch.localization.localized
import com.yokuli.anchorwatch.localization.usesChinese
import com.yokuli.anchorwatch.runtime.nmea.NmeaRuntime
import com.yokuli.anchorwatch.runtime.anchor.AnchorRuntimeHost
import com.yokuli.anchorwatch.runtime.anchor.AnchorRuntimeActor
import com.yokuli.anchorwatch.runtime.anchor.AnchorWatchRuntime
import com.yokuli.anchorwatch.runtime.anchor.ArmRequest
import com.yokuli.anchorwatch.runtime.notification.AlarmAudioController
import com.yokuli.anchorwatch.runtime.notification.NotificationCoordinator
import com.yokuli.anchorwatch.runtime.notification.AlarmAudioArbiter
import com.yokuli.anchorwatch.runtime.condition.ConditionRuntime
import com.yokuli.anchorwatch.data.condition.LiveDepthRepository
import com.yokuli.anchorwatch.data.condition.LiveWindRepository
import com.yokuli.anchorwatch.runtime.sonar.SonarRuntime
import com.yokuli.anchorwatch.runtime.sharing.NmeaSharingRuntime
import com.yokuli.anchorwatch.runtime.proxy.GpsProxyRuntime
import com.yokuli.anchorwatch.runtime.proxy.ProxyRuntimeResult
import com.yokuli.anchorwatch.runtime.health.BatteryHealthMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

class YokuliRuntimeCoordinator @Inject constructor(
 @ApplicationContext private val context:Context,
 private val navigation:NavigationRepository,
 private val dao:AnchorDao,
 private val preferences:SettingsRepository,
 private val mockGps:GlobalMockLocationManager,
 private val proxyRuntime:GpsProxyRuntime,
 private val systemLocation:SystemLocationRepository,
 private val demoLocation:DemoLocationRepository,
 private val phoneHeading:PhoneHeadingRepository,
 private val acceptedPosition:AcceptedPositionRepository,
 private val alarmUi:AlarmUiRepository,
 private val sharingRuntime:NmeaSharingRuntime,
 private val sonarRecorder:SonarSurveyRecorder,
 private val sonarRuntime:SonarRuntime,
 private val resources:RuntimeResourceManager,
 private val diagnostics:RuntimeDiagnosticsRepository,
 private val incidentLogger:IncidentLogger,
 private val nmeaRuntime:NmeaRuntime,
 private val monotonicClock:MonotonicClock,
 private val wallClock:WallClock,
 private val alarmAudio:AlarmAudioController,
 private val notificationCoordinator:NotificationCoordinator,
 private val conditionRuntime:ConditionRuntime,
 private val liveDepth:LiveDepthRepository,
 private val liveWind:LiveWindRepository,
 private val batteryHealth:BatteryHealthMonitor
){
 private lateinit var host:RuntimeServiceHost
 private lateinit var commandActor:SerialRuntimeActor
 private lateinit var anchorActor:AnchorRuntimeActor
 private lateinit var proxyActor:SerialRuntimeActor
 private lateinit var anchorRuntime:AnchorWatchRuntime
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);private val stateReady=CompletableDeferred<Unit>();private val alarmTestGeneration=AtomicLong(0L);private val acceptedIncidentBatch=AtomicLong(0L);private val pendingCommands=AtomicInteger(0);private val audioArbiter=AlarmAudioArbiter();private var alarmSnoozeMinutes=5;private var selectedAlarmSound=AlarmSound.SYSTEM_ALARM;private var customAlarmSoundUri:String?=null;@Volatile private var armPending=false;@Volatile private var alarmTestActive=false;@Volatile private var appLanguage=AppLanguage.ENGLISH

 private data class SourcedFix(val source:GpsDataSource,val fix:NavigationFix)
 fun start(host:RuntimeServiceHost){
  this.host=host
  diagnostics.serviceStarting()
  incidentLogger.record("service","STARTED")
  anchorRuntime=AnchorWatchRuntime(navigation,dao,preferences,mockGps,systemLocation,demoLocation,phoneHeading,acceptedPosition,alarmUi,sonarRecorder,resources,diagnostics,nmeaRuntime,liveDepth,liveWind,object:AnchorRuntimeHost{
   override fun notificationPermissionGranted()=host.notificationPermissionGranted()
   override fun enableSystemGps()=this@YokuliRuntimeCoordinator.enableSystemGps()
   override fun notify(title:String,message:String,high:Boolean)=notifySeparate(title,message,high)
   override fun refresh()=refreshNotification()
   override fun sound(){setAlarmSource(ConditionAlarmSource.ANCHOR,true)}
   override fun silence(){setAlarmSource(ConditionAlarmSource.ANCHOR,false)}
   override fun cancelUrgentNotification()=notificationCoordinator.cancelEvent()
   override fun releaseIfIdle()=this@YokuliRuntimeCoordinator.releaseIfIdle()
  },monotonicClock,wallClock)
  anchorActor=AnchorRuntimeActor(scope,{stateReady.await()},anchorRuntime){error->incidentLogger.exception("anchor_runtime","ACTOR_FAILED",error,anchorRuntime.activeSession()?.id)}
  proxyActor=SerialRuntimeActor(scope,{stateReady.await()}){error->incidentLogger.exception("gps_proxy","ACTOR_FAILED",error,anchorRuntime.activeSession()?.id)}
  commandActor=SerialRuntimeActor(scope,{stateReady.await()}){error->incidentLogger.exception("service","COMMAND_ACTOR_FAILED",error,anchorRuntime.activeSession()?.id)}
  channels()
  host.startForeground(notification(l("Starting safety monitor…","正在启动安全监控…"),false),location=false)
  // Subscribe before either state restoration or provider collection can publish a fix.
  // SharedFlow intentionally has no replay (a fix must never be processed twice), so a
  // normally-dispatched subscriber here creates a cold-start race that can lose the first
  // safety position. UNDISPATCHED runs collect() up to its first suspension immediately.
  scope.launch(start=CoroutineStart.UNDISPATCHED){acceptedPosition.accepted.collect{event->val count=acceptedIncidentBatch.incrementAndGet();if(count==1L||count%100L==0L)incidentLogger.record("gps","ACCEPTED_BATCH",sessionId=anchorRuntime.activeSession()?.id,details=mapOf("acceptedSinceStart" to count,"source" to event.source.name));anchorActor.submit{onAcceptedPosition(event.accepted,event.source)};if(event.source==GpsDataSource.NMEA)proxyActor.submit{handleProxyResult(proxyRuntime.onAcceptedNmeaFix(event.accepted.fix))};sharingRuntime.onAcceptedPosition(event,monotonicClock.elapsedRealtime())}}
  scope.launch{try{restoreState();diagnostics.serviceReady(anchorRuntime.activeSession()?.id)}finally{stateReady.complete(Unit)}}
  scope.launch{combine(preferences.settings.map{it.gpsDataSource}.distinctUntilChanged(),navigation.fix,systemLocation.fix){source,nmea,system->anchorRuntime.selectIdleSource(source);when(val selected=anchorRuntime.snapshot().gpsSource){GpsDataSource.NMEA->nmea?.let{SourcedFix(selected,it)};GpsDataSource.SYSTEM->system?.let{SourcedFix(selected,it)};GpsDataSource.DEMO->null}}.filterNotNull().collect{value->anchorRuntime.submitRawFix(value.fix,value.source)}}
  scope.launch{acceptedPosition.state.map{Triple(it.disposition,it.reason,it.rawFix?.receivedElapsedRealtime)}.distinctUntilChanged().collect{(disposition,reason,at)->diagnostics.recordPositionDisposition(disposition);if(disposition=="QUARANTINED"||disposition=="REJECTED")incidentLogger.record("gps",disposition,if(disposition=="REJECTED")IncidentSeverity.CRITICAL else IncidentSeverity.WARNING,anchorRuntime.activeSession()?.id,mapOf("reason" to reason));anchorActor.submit{onPositionHealth(disposition,reason,at)}}}
  scope.launch{navigation.connectionState.collect{state->incidentLogger.record("nmea","CONNECTION_${state.name}",if(state==NmeaConnectionState.ERROR||state==NmeaConnectionState.STALE)IncidentSeverity.WARNING else IncidentSeverity.INFO,anchorRuntime.activeSession()?.id);anchorActor.submit{onNmeaState(state)}}}
  scope.launch{alarmUi.snapshot.map{it.state to it.type}.distinctUntilChanged().collect{(state,type)->if(type!=AlarmType.ALARM_TEST)incidentLogger.record("alarm","${state.name}_${type?.name?:"NONE"}",if(state==AlarmState.ALARM)IncidentSeverity.CRITICAL else IncidentSeverity.INFO,anchorRuntime.activeSession()?.id)}}
  scope.launch{conditionRuntime.state.map{Triple(it.depth.status,it.windSpeed.status,it.windShift.status)}.distinctUntilChanged().drop(1).collect{(depth,wind,shift)->
   when{depth==DepthGuardStatus.DATA_UNAVAILABLE->notifySeparate("Depth data unavailable","The depth guard remains enabled but fresh NMEA depth has been missing for 10 seconds.",true);wind==WindSpeedGuardStatus.DATA_UNAVAILABLE||shift==WindShiftGuardStatus.DATA_UNAVAILABLE->notifySeparate("Wind data unavailable","A wind guard remains enabled but its required fresh NMEA data is unavailable.",true);wind==WindSpeedGuardStatus.WARNING->notifySeparate("High wind warning","Filtered wind has remained above the warning threshold.",false)}
   incidentLogger.record("conditions","DEPTH_${depth.name};WIND_${wind.name};SHIFT_${shift.name}",if(depth.name.contains("ALARM")||wind==WindSpeedGuardStatus.ALARM||shift==WindShiftGuardStatus.ALARM)IncidentSeverity.CRITICAL else if(depth==DepthGuardStatus.DATA_UNAVAILABLE||wind==WindSpeedGuardStatus.DATA_UNAVAILABLE||shift==WindShiftGuardStatus.DATA_UNAVAILABLE)IncidentSeverity.WARNING else IncidentSeverity.INFO,anchorRuntime.activeSession()?.id)
  }}
  scope.launch{sharingRuntime.status.map{it.clientCount}.distinctUntilChanged().collect{count->incidentLogger.record("sharing","CLIENT_COUNT",details=mapOf("clients" to count))}}
  scope.launch{dao.sessions().map{sessions->sessions.firstOrNull{it.active}?.let{listOf(it.id,it.centerStatus,it.candidateDecision,it.centerSampleCount,it.candidateAngularSectorCount,it.candidateSwingReversalCount,it.provisionalRadiusMeters)}}.distinctUntilChanged().collect{candidate->candidate?.let{incidentLogger.record("anchor","CENTRE_STATE",sessionId=it[0] as Long,details=mapOf("status" to it[1],"decision" to it[2],"samples" to it[3],"sectors" to it[4],"reversals" to it[5],"uncertaintyMeters" to it[6]))}}}
  scope.launch{sonarRecorder.status.map{Triple(it.activeSurvey?.id,it.lastDisposition?.name,it.message)}.distinctUntilChanged().collect{(survey,disposition,message)->incidentLogger.record("sonar","STATE",if(message.contains("waiting",true))IncidentSeverity.WARNING else IncidentSeverity.INFO,details=mapOf("surveyActive" to (survey!=null),"disposition" to disposition,"message" to message))}}
  scope.launch{preferences.settings.map{it.alarmSnoozeMinutes}.distinctUntilChanged().collect{alarmSnoozeMinutes=it}}
  scope.launch{preferences.settings.map{it.gpsDataSource}.distinctUntilChanged().collect{source->incidentLogger.record("gps","SOURCE_${source.name}",sessionId=anchorRuntime.activeSession()?.id)}}
  scope.launch{preferences.settings.map{it.alarmSound to it.customAlarmSoundUri}.distinctUntilChanged().collect{(sound,uri)->selectedAlarmSound=sound;customAlarmSoundUri=uri}}
  scope.launch{preferences.settings.map{it.appLanguage}.distinctUntilChanged().collect{appLanguage=it;channels();refreshNotification()}}
  scope.launch{preferences.settings.map{Triple(it.nmeaSharingEnabled,it.nmeaSharingPort,it.gpsDataSource)}.distinctUntilChanged().collect{(enabled,port,source)->configureSharing(enabled,port,source)}}
  scope.launch{navigation.validRawSentences.collect(sharingRuntime::onBoatSentence)}
  scope.launch{while(isActive){delay(1000);anchorActor.submit{watchdog();val conditions=conditionRuntime.tick(monotonicClock.elapsedRealtime());refreshSessionFromDatabase();setConditionAlarmSources(conditions);refreshNotification()};proxyActor.submit{handleProxyResult(proxyRuntime.watchdog(monotonicClock.elapsedRealtime()))}}}
  scope.launch{while(isActive){delay(30_000);batteryWatchdog()}}
 }

 fun submit(command:RuntimeCommand){
  when(command){
   is RuntimeCommand.ArmWatch->{armPending=true;val request=ArmRequest(command.config,command.placement,command.rangeMode,command.safetyPreset,command.boatLength,command.positionSource,command.centerSource,command.usePhoneHeading,command.depthSource,command.conditions);launchCommand{try{anchorActor.execute{arm(request);conditionRuntime.sync(activeSession())}}finally{armPending=false;releaseIfIdle()}}}
   RuntimeCommand.SnoozeAlarm->launchCommand{val until=wallClock.currentTimeMillis()+alarmSnoozeMinutes*60_000L;anchorActor.execute{snooze();conditionRuntime.snooze(until);refreshSessionFromDatabase()};audioArbiter.snoozeActive(wallClock.currentTimeMillis(),until);reconcileAudio()}
   RuntimeCommand.PauseWatch->launchCommand{anchorActor.execute{conditionRuntime.flush();refreshSessionFromDatabase();pause();conditionRuntime.sync(activeSession())};clearConditionSources()}
   RuntimeCommand.ResumeWatch->launchCommand{anchorActor.execute{resume();conditionRuntime.sync(activeSession())}}
   RuntimeCommand.LiftAnchor->launchCommand{anchorActor.execute{conditionRuntime.flush();refreshSessionFromDatabase();lift();conditionRuntime.sync(null)};clearConditionSources()}
   is RuntimeCommand.UpdateRadius->launchCommand{anchorActor.execute{updateRadius(command.radiusMeters)}}
   RuntimeCommand.PauseWatchAndDisconnect->launchCommand{stopWatchAndDisconnect()}
   is RuntimeCommand.Candidate->launchCommand{anchorActor.execute{when(command.action){CandidateAction.ACCEPT->acceptCandidate(command.sessionId,command.candidateId);CandidateAction.KEEP_CURRENT->keepCurrentCenter(command.sessionId,command.candidateId);CandidateAction.CONTINUE_ESTIMATING->continueEstimating(command.sessionId,command.candidateId)}}}
   is RuntimeCommand.UpdatePhoneHeading->launchCommand{anchorActor.execute{updatePhoneHeading(command.enabled)}}
   is RuntimeCommand.UpdateConditionGuards->launchCommand{anchorActor.execute{conditionRuntime.updateConfig(command.config);refreshSessionFromDatabase();setConditionAlarmSources(conditionRuntime.tick(monotonicClock.elapsedRealtime()));refreshNotification()}}
   RuntimeCommand.ResetWindBaseline->launchCommand{anchorActor.execute{conditionRuntime.resetWindBaseline();refreshSessionFromDatabase();setConditionAlarmSources(conditionRuntime.tick(monotonicClock.elapsedRealtime()));refreshNotification()}}
   RuntimeCommand.StartProxy->launchCommand{proxyActor.execute{startProxy()}}
   RuntimeCommand.StopProxy->launchCommand{proxyActor.execute{stopProxy(l("Android GPS proxy stopped by user.","用户已关闭 Android GPS 代理。"))}}
   RuntimeCommand.TestAlarm->{val generation=alarmTestGeneration.incrementAndGet();launchCommand{testAlarm(generation)}}
   RuntimeCommand.StopAlarmTest->{alarmTestGeneration.incrementAndGet();setAlarmSource(ConditionAlarmSource.ALARM_TEST,false);launchCommand{stopAlarmTest()}}
   is RuntimeCommand.SetSharing->launchCommand{configureSharing(command.enabled,command.port,preferences.settings.first().gpsDataSource)}
   is RuntimeCommand.StartSonar->launchCommand{startSonarSurvey(command.name,command.tideMode,command.manualTideOffsetMeters,command.tideStationId)}
   RuntimeCommand.StopSonar->launchCommand{sonarRuntime.stop();incidentLogger.record("sonar","SURVEY_STOPPED");refreshNotification();releaseIfIdle()}
   RuntimeCommand.RestoreOnly,is RuntimeCommand.Unknown->Unit
  }
 }

 private fun launchCommand(action:suspend ()->Unit){
  pendingCommands.incrementAndGet()
  if(!commandActor.submit{try{action()}catch(error:Throwable){incidentLogger.exception("service","COMMAND_FAILED",error,anchorRuntime.activeSession()?.id);notifySeparate("Safety command failed",error.message?:error.javaClass.simpleName,true)}finally{if(pendingCommands.decrementAndGet()==0)releaseIfIdle()}}){
   pendingCommands.decrementAndGet()
  }
 }

 private suspend fun restoreState(){
  var settings=preferences.settings.first()
  settings=anchorRuntime.restore(settings)
  alarmSnoozeMinutes=settings.alarmSnoozeMinutes
  selectedAlarmSound=settings.alarmSound
  customAlarmSoundUri=settings.customAlarmSoundUri
  appLanguage=settings.appLanguage
  channels()
  sonarRuntime.restore()
  conditionRuntime.sync(anchorRuntime.activeSession())
  handleProxyResult(proxyRuntime.restoreIfRequested{ensureLocationForeground("Starting NMEA → Android GPS…")})
  anchorRuntime.onNmeaState(navigation.connectionState.value)
  refreshNotification()
 }
 private suspend fun startProxy(){handleProxyResult(proxyRuntime.start{ensureLocationForeground("Starting NMEA → Android GPS…")});refreshNotification();releaseIfIdle()}

 private suspend fun stopProxy(message:String){handleProxyResult(proxyRuntime.stop(message));refreshNotification();releaseIfIdle()}

 private fun testAlarm(generation:Long){
  // TEST and STOP arrive as independent service commands. The generation check
  // prevents a delayed TEST coroutine from starting after the user already
  // pressed Stop.
  if(generation!=alarmTestGeneration.get())return
  val anchor=anchorRuntime.snapshot()
  val condition=conditionRuntime.state.value
  if(anchor.session?.paused==false&&(anchor.alarm?.state==AlarmState.ALARM||condition.depth.alarmActive||condition.windSpeed.alarmActive||condition.windShift.alarmActive)){notifySeparate("Alarm test unavailable","Handle or snooze every active safety alarm before testing the sound.",true);releaseIfIdle();return}
  setAlarmSource(ConditionAlarmSource.ALARM_TEST,false)
  if(generation!=alarmTestGeneration.get())return
  // A sound test is itself a foreground safety task. Keep the service alive until
  // the user stops/confirms it or the safety timeout expires; otherwise the normal
  // idle cleanup destroys the player as soon as this command returns.
  alarmTestActive=true
  val playback=setAlarmSource(ConditionAlarmSource.ALARM_TEST,true)
  if(generation!=alarmTestGeneration.get()){alarmTestActive=false;setAlarmSource(ConditionAlarmSource.ALARM_TEST,false);return}
  alarmUi.publish(AlarmSnapshot(AlarmState.ALARM,AlarmType.ALARM_TEST));refreshNotification();notifySeparate("Alarm test",when{!playback.started->"The alarm player could not start. Choose another custom file or use the built-in alarm.";playback.volume==0->"Alarm playback started, but Android alarm volume is muted. Raise Alarm volume, then test again.";else->"Sound and vibration are active globally. Confirm or stop them from the in-app banner; the test stops automatically after 20 seconds."},true)
  scope.launch{delay(20_000);if(alarmTestGeneration.compareAndSet(generation,generation+1))launchCommand{stopAlarmTest()}}
 }
 private fun stopAlarmTest(){alarmTestActive=false;setAlarmSource(ConditionAlarmSource.ALARM_TEST,false);val anchor=anchorRuntime.snapshot();val restore=anchor.alarm;if(anchor.session==null||restore==null)alarmUi.clear()else alarmUi.publish(restore);notificationCoordinator.cancelEvent();refreshNotification();releaseIfIdle()}

 private suspend fun stopWatchAndDisconnect(){
  val wasActive=anchorRuntime.activeSession()!=null
  anchorActor.execute{conditionRuntime.flush();refreshSessionFromDatabase();pause();conditionRuntime.sync(activeSession())}
  navigation.disconnectAll()
  if(wasActive)notifySeparate("Anchor watch paused","This anchor session and its centre were kept; NMEA was disconnected.",false)
 }

 private suspend fun configureSharing(enabled:Boolean,port:Int,source:GpsDataSource){
  val locked=anchorRuntime.activeSession()?.positionSource?.let{runCatching{GpsDataSource.valueOf(it)}.getOrNull()}
  val result=sharingRuntime.configure(enabled,port,source,locked)
  if(result.needsLocationForeground)enableSystemGps()
  if(result.title!=null&&result.message!=null)notifySeparate(result.title,result.message,false)
  refreshNotification();releaseIfIdle()
 }

 private suspend fun batteryWatchdog(){
  val state=batteryHealth.sample(resources.snapshot().owners.isNotEmpty())
  if(state.newlyLow){notifySeparate("Low battery: ${state.percent}%","Connect this monitoring device to reliable power.",true);anchorActor.submit{logEvent("LOW_BATTERY","${state.percent}")};incidentLogger.record("battery","LOW",IncidentSeverity.CRITICAL,anchorRuntime.activeSession()?.id,mapOf("percent" to state.percent))}
 }

 private fun refreshNotification(){
  val anchor=anchorRuntime.snapshot();val snapshot=anchor.alarm;val active=anchor.session;val proxy=proxyRuntime.status.value;val now=wallClock.currentTimeMillis();val condition=conditionRuntime.state.value;val anchorAlarm=active?.paused==false&&snapshot?.type!=null&&(snapshot.state==AlarmState.ALARM||snapshot.state==AlarmState.ACKNOWLEDGED);val conditionAlarm=active?.paused==false&&(condition.depth.alarmActive||condition.windSpeed.alarmActive||condition.windShift.alarmActive);val alarmCondition=anchorAlarm||conditionAlarm;val audible=audioArbiter.snapshot(now).shouldSound;val snoozed=alarmCondition&&!audible;val remaining=listOfNotNull(active?.alarmSnoozedUntil,active?.depthAlarmSnoozedUntil,active?.windAlarmSnoozedUntil,active?.windShiftAlarmSnoozedUntil).filter{it>now}.minOrNull()?.let{((it-now+59_999)/60_000).coerceAtLeast(1)}
  val base=when{
   alarmTestActive->l("Alarm test sounding • open the App to confirm or stop","警报测试正在响铃 · 打开应用确认或停止")
   condition.depth.status==DepthGuardStatus.SHALLOW_ALARM->l("SHALLOW WATER ${condition.depth.filteredDepthMeters?.let{"%.1f m".format(it)}?:""}","浅水警报 ${condition.depth.filteredDepthMeters?.let{"%.1f 米".format(it)}?:""}")
   snapshot?.type==AlarmType.GPS_DATA_LOST&&active?.paused==false->l("GPS DATA LOST","GPS 数据丢失")
   snapshot?.type==AlarmType.GPS_QUALITY_BAD&&active?.paused==false->l("GPS QUALITY DEGRADED: ${anchor.positionDegradedReason?:"unknown"}","GPS 质量下降：${anchor.positionDegradedReason?:"未知原因"}")
   snapshot?.type==AlarmType.ANCHOR_RADIUS_EXCEEDED&&active?.paused==false->l("ANCHOR ALARM ${snapshot.distanceMeters?.toInt()} m","锚警：距离 ${snapshot.distanceMeters?.toInt()} 米")
   condition.windSpeed.status==WindSpeedGuardStatus.ALARM->l("HIGH WIND ${condition.windSpeed.filteredSpeedKnots?.let{"%.1f kn".format(it)}?:""}","大风警报 ${condition.windSpeed.filteredSpeedKnots?.let{"%.1f 节".format(it)}?:""}")
   condition.depth.status==DepthGuardStatus.DEEP_ALARM->l("DEEP WATER ${condition.depth.filteredDepthMeters?.let{"%.1f m".format(it)}?:""}","深水警报 ${condition.depth.filteredDepthMeters?.let{"%.1f 米".format(it)}?:""}")
   condition.windShift.status==WindShiftGuardStatus.ALARM->l("WIND SHIFT ${condition.windShift.shiftDegrees?.toInt()?:"—"}°","风向突变 ${condition.windShift.shiftDegrees?.toInt()?:"—"}°")
   active?.paused==true&&proxy.state==MockGpsState.ACTIVE->l("Anchor session paused • NMEA GPS proxy active","锚泊监控已暂停 · NMEA GPS 代理运行中")
   active?.paused==true->l("Anchor session paused","锚泊监控已暂停")
   active?.centerStatus==AnchorCenterStatus.CANDIDATE_READY.name->l("Watch active • estimated centre awaits approval","锚警监控中 · 估算中心等待确认")
   active?.centerStatus==AnchorCenterStatus.LEARNING.name->l("Watch active • temporary boundary armed • ${anchor.learningSampleCount} fixes","锚警监控中 · 临时边界已布防 · ${anchor.learningSampleCount} 个定位点")
   active!=null&&anchor.gpsSource==GpsDataSource.NMEA&&anchor.nmeaLossAnnounced->l("Watch active • NMEA connection lost • reconnecting","锚警监控中 · NMEA 连接丢失 · 正在重连")
   active!=null&&anchor.gpsSource==GpsDataSource.SYSTEM->l("Watch ${snapshot?.distanceMeters?.toInt()?:"--"} m • SYSTEM GPS","锚警 ${snapshot?.distanceMeters?.toInt()?:"--"} 米 · 系统 GPS")
   active!=null&&anchor.gpsSource==GpsDataSource.DEMO->l("Watch ${snapshot?.distanceMeters?.toInt()?:"--"} m • DEMO ${demoLocation.status.value.scenario.name}","锚警 ${snapshot?.distanceMeters?.toInt()?:"--"} 米 · 演示 ${demoLocation.status.value.scenario.name}")
   active!=null->l("Watch ${snapshot?.distanceMeters?.toInt()?:"--"} m • NMEA ${navigation.connectionState.value.name}","锚警 ${snapshot?.distanceMeters?.toInt()?:"--"} 米 · NMEA ${navigation.connectionState.value.name}")
   proxy.state==MockGpsState.ACTIVE->l("NMEA → Android GPS active • ${proxy.publishedFixes} fixes","NMEA → Android GPS 已开启 · ${proxy.publishedFixes} 个定位点")
   sharingRuntime.enabled->l("NMEA Sharing • ${sharingRuntime.status.value.clientCount} clients • port ${sharingRuntime.status.value.port}","NMEA 共享 · ${sharingRuntime.status.value.clientCount} 个客户端 · 端口 ${sharingRuntime.status.value.port}")
   sonarRuntime.status.value.activeSurvey!=null->l("Sonar survey recording • ${sonarRuntime.status.value.activeSurvey?.sampleCount?:0} samples","声呐调查记录中 · ${sonarRuntime.status.value.activeSurvey?.sampleCount?:0} 个样本")
   else->l("Safety monitor idle","安全监控待命")
  }
  val activeAlertCount=(if(anchorAlarm)1 else 0)+(if(condition.depth.alarmActive)1 else 0)+(if(condition.windSpeed.alarmActive)1 else 0)+(if(condition.windShift.alarmActive)1 else 0)
  val countedBase=if(activeAlertCount>1)l("$base • +${activeAlertCount-1} other active alert${if(activeAlertCount>2)"s" else ""}","$base · 另有 ${activeAlertCount-1} 项警报")else base
  val text=if(snoozed&&alarmCondition)l("$countedBase • snoozed, remind in ${remaining?:alarmSnoozeMinutes}m","$countedBase · 已暂停响铃，${remaining?:alarmSnoozeMinutes} 分钟后再次提醒")else countedBase
  notificationCoordinator.publishForeground(notification(text,alarmCondition,snoozed))
 }
 private fun notification(text:String,alarm:Boolean,silent:Boolean=false):Notification=notificationCoordinator.foregroundNotification(text,alarm,silent,if(alarm)l("Anchor Watch alarm","Anchor Watch 锚警") else if(mockGps.status.value.state==MockGpsState.ACTIVE)l("NMEA GPS Proxy","NMEA GPS 代理") else l("Anchor Watch active","Anchor Watch 运行中"),l("SNOOZE ${alarmSnoozeMinutes} MIN","${alarmSnoozeMinutes} 分钟后提醒"))
 private fun notifySeparate(title:String,text:String,high:Boolean)=notificationCoordinator.publishEvent(serviceMessage(title),serviceMessage(text),high)

 private fun setAlarmSource(source:ConditionAlarmSource,active:Boolean):com.yokuli.anchorwatch.runtime.notification.AlarmPlayback{audioArbiter.setActive(source,active);return reconcileAudio()}
 private fun reconcileAudio():com.yokuli.anchorwatch.runtime.notification.AlarmPlayback=if(audioArbiter.snapshot(wallClock.currentTimeMillis()).shouldSound)alarmAudio.start(selectedAlarmSound,customAlarmSoundUri)else{alarmAudio.stop();com.yokuli.anchorwatch.runtime.notification.AlarmPlayback(false,0)}
 private fun setConditionAlarmSources(value:ConditionRuntimeSnapshot){val active=conditionRuntime.audibleSources(wallClock.currentTimeMillis());audioArbiter.setActive(ConditionAlarmSource.DEPTH,ConditionAlarmSource.DEPTH in active);audioArbiter.setActive(ConditionAlarmSource.WIND_SPEED,ConditionAlarmSource.WIND_SPEED in active);audioArbiter.setActive(ConditionAlarmSource.WIND_SHIFT,ConditionAlarmSource.WIND_SHIFT in active);reconcileAudio()}
 private fun clearConditionSources(){audioArbiter.clear(ConditionAlarmSource.DEPTH);audioArbiter.clear(ConditionAlarmSource.WIND_SPEED);audioArbiter.clear(ConditionAlarmSource.WIND_SHIFT);reconcileAudio()}
 private fun enableSystemGps():Boolean{if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return false;return host.startForeground(notification("System GPS anchor monitoring…",false),location=true)}
 private fun ensureLocationForeground(message:String)=host.startForeground(notification(message,false),location=true)
 private suspend fun handleProxyResult(result:ProxyRuntimeResult?){
 result?:return
  result.eventType?.let{eventType->anchorActor.submit{logEvent(eventType,result.eventDetail)}}
  result.eventType?.let{incidentLogger.record("gps_proxy",it,if(result.high)IncidentSeverity.WARNING else IncidentSeverity.INFO,anchorRuntime.activeSession()?.id,mapOf("detail" to result.eventDetail))}
  if(result.title!=null&&result.message!=null)notifySeparate(result.title,result.message,result.high)
 }
 private suspend fun startSonarSurvey(name:String,tideMode:TideMode,manualTideOffset:Double,tideStationId:String?){
  val result=sonarRuntime.start(name,tideMode,manualTideOffset,tideStationId)
  incidentLogger.record("sonar",if(result.started)"SURVEY_STARTED" else "SURVEY_START_REJECTED",if(result.started)IncidentSeverity.INFO else IncidentSeverity.WARNING,details=mapOf("tideMode" to tideMode.name,"reason" to result.message))
  if(!result.started)notifySeparate(result.title?:"Sonar survey not started",result.message?:"Sonar runtime rejected the request.",true)
  refreshNotification()
 }
 private fun releaseIfIdle(){if(pendingCommands.get()==0&&!alarmTestActive&&anchorRuntime.activeSession()?.paused!=false&&proxyRuntime.status.value.state!=MockGpsState.ACTIVE&&!sharingRuntime.enabled&&sonarRuntime.status.value.activeSurvey==null&&!armPending){nmeaRuntime.releaseIfUnowned();host.stopForegroundAndSelf()}}
 private fun cleanup(){alarmTestGeneration.incrementAndGet();alarmTestActive=false;audioArbiter.clearAll();alarmAudio.stop();resources.releaseAll()}
 fun shutdown(){incidentLogger.record("service","STOPPED");commandActor.shutdown();anchorActor.shutdown();proxyActor.shutdown();sharingRuntime.shutdown();scope.cancel();navigation.releaseBackgroundConnection();runBlocking(Dispatchers.IO){withTimeoutOrNull(2000){proxyRuntime.shutdown()}};cleanup();diagnostics.serviceStopped()}
 private fun channels()=notificationCoordinator.createChannels(l("Anchor and GPS status","锚警与 GPS 状态"),l("Anchor safety events","锚泊安全事件"),l("Anchor alarms with snooze","带稍后提醒的锚警"))
 private fun l(english:String,chinese:String)=localized(appLanguage,english,chinese)
 private fun serviceMessage(message:String):String{
  if(!appLanguage.usesChinese())return message
  return when{
   message=="Anchor session already open"->"已有锚泊会话"
   message=="Pause, resume or lift the current anchor before starting another session."->"开始新会话前，请暂停、继续或结束当前锚泊。"
   message=="Anchor watch not started"->"锚警未启动"
   message=="Alarm test"->"警报测试"
   message=="Alarm test unavailable"->"暂时无法测试警报"
   message.startsWith("Handle or snooze the active anchor alarm")->"请先处理或稍后提醒当前锚警，再测试声音。"
   message.startsWith("Handle or snooze every active safety alarm")->"请先处理或稍后提醒所有当前安全警报，再测试声音。"
   message.startsWith("The alarm player could not start")->"警报播放器无法启动；请选择其他自定义声音或使用内置警报。"
   message.startsWith("Alarm playback started, but Android alarm volume is muted")->"警报已开始播放，但 Android 的警报音量为静音；请调高警报音量后重试。"
   message.startsWith("Sound and vibration are active globally")->"声音与振动正在全局运行；请在应用内横幅确认或停止，测试会在 20 秒后自动停止。"
   message.startsWith("A live, current ")->"需要实时且新鲜的 GPS 定位才能启动锚警；现有连接没有改变。"
   message.startsWith("NMEA depth was selected")->"已选择 NMEA 水深，但 NMEA 尚未连接或 DPT/DBT 水深已过期。请重新连接，或改用手动水深。"
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
   message=="Phone heading enabled"->"手机船首向证据已开启"
   message=="Phone heading disabled"->"手机船首向证据已关闭"
   message=="Phone heading unavailable"->"手机船首向不可用"
   message=="Phone heading not changed"->"手机船首向设置未改变"
   message.startsWith("New stable phone-heading samples")->"新的稳定手机船首向样本会在新的校准批次中辅助估算；已有样本会继续保留。"
   message.startsWith("No new phone-heading samples")->"不会再加入新的手机船首向样本；本会话已使用的样本仍保留在估算器中。"
   message.startsWith("This device does not provide a compatible rotation sensor")->"此设备没有兼容的旋转传感器；GPS 与风向估算会继续正常工作。"
   message.startsWith("Android could not start the phone orientation sensor")->"Android 无法启动手机方向传感器；已有估算证据已保留。"
   message.startsWith("Phone heading is only estimator evidence")->"手机船首向只在锚点中心仍处于学习阶段时作为估算辅助证据。"
   else->message
  }
 }
}
