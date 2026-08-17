package com.yokuli.anchorwatch.runtime.anchor

import com.yokuli.anchorwatch.data.AlarmUiRepository
import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.data.database.AlarmEventEntity
import com.yokuli.anchorwatch.data.database.AnchorDao
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.TrackPointEntity
import com.yokuli.anchorwatch.data.preferences.AppSettings
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.data.sonar.SonarSurveyRecorder
import com.yokuli.anchorwatch.domain.anchor.AlarmEngine
import com.yokuli.anchorwatch.domain.anchor.AlarmReminderPolicy
import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import com.yokuli.anchorwatch.domain.anchor.AnchorDepthSource
import com.yokuli.anchorwatch.domain.anchor.AnchorSetupDepthPolicy
import com.yokuli.anchorwatch.domain.anchor.BackdownCenterEstimator
import com.yokuli.anchorwatch.domain.anchor.CandidateCenterObservation
import com.yokuli.anchorwatch.domain.anchor.CandidateDriftDetector
import com.yokuli.anchorwatch.domain.anchor.CandidateDriftUpdate
import com.yokuli.anchorwatch.domain.model.AlarmSnapshot
import com.yokuli.anchorwatch.domain.model.AlarmState
import com.yokuli.anchorwatch.domain.model.AlarmType
import com.yokuli.anchorwatch.domain.model.AnchorCenterSource
import com.yokuli.anchorwatch.domain.model.AnchorCenterStatus
import com.yokuli.anchorwatch.domain.model.AnchorConfig
import com.yokuli.anchorwatch.domain.model.AnchorPlacementMode
import com.yokuli.anchorwatch.domain.model.AnchorPositionMode
import com.yokuli.anchorwatch.domain.model.AnchorRangeMode
import com.yokuli.anchorwatch.domain.model.AnchorSafetyPreset
import com.yokuli.anchorwatch.domain.model.BackdownAnchorEstimate
import com.yokuli.anchorwatch.domain.model.CandidateDecision
import com.yokuli.anchorwatch.domain.model.Confidence
import com.yokuli.anchorwatch.domain.model.FixTrust
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.HeadingQuality
import com.yokuli.anchorwatch.domain.model.HeadingSource
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.domain.model.PositionProvider
import com.yokuli.anchorwatch.location.AcceptedPositionRepository
import com.yokuli.anchorwatch.location.DemoLocationRepository
import com.yokuli.anchorwatch.location.DemoSonarGenerator
import com.yokuli.anchorwatch.location.GlobalMockLocationManager
import com.yokuli.anchorwatch.location.GpsSourceSafety
import com.yokuli.anchorwatch.location.IntegrityAcceptedFix
import com.yokuli.anchorwatch.location.NmeaFixQualityPolicy
import com.yokuli.anchorwatch.location.PhoneHeadingRepository
import com.yokuli.anchorwatch.location.SystemLocationRepository
import com.yokuli.anchorwatch.runtime.MonotonicClock
import com.yokuli.anchorwatch.runtime.RuntimeOwner
import com.yokuli.anchorwatch.runtime.RuntimeDiagnosticsRepository
import com.yokuli.anchorwatch.runtime.RuntimeRequirement
import com.yokuli.anchorwatch.runtime.RuntimeResourceManager
import com.yokuli.anchorwatch.runtime.SystemMonotonicClock
import com.yokuli.anchorwatch.runtime.SystemWallClock
import com.yokuli.anchorwatch.runtime.WallClock
import com.yokuli.anchorwatch.runtime.nmea.NmeaRuntime
import com.yokuli.anchorwatch.domain.condition.ConditionGuardConfig
import com.yokuli.anchorwatch.data.condition.LiveDepthRepository
import com.yokuli.anchorwatch.data.condition.LiveWindRepository
import com.yokuli.anchorwatch.data.nmea.NmeaUpdate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

data class ArmRequest(
    val config:AnchorConfig,
    val placement:AnchorPlacementMode,
    val rangeMode:AnchorRangeMode,
    val safetyPreset:AnchorSafetyPreset,
    val boatLength:Double?,
    val positionSource:GpsDataSource?,
    val centerSource:AnchorCenterSource,
    val usePhoneHeading:Boolean,
    val depthSource:AnchorDepthSource=AnchorDepthSource.MANUAL,
    val conditions:ConditionGuardConfig=ConditionGuardConfig(),
)

data class AnchorRuntimeSnapshot(
    val session:AnchorSessionEntity?=null,
    val alarm:AlarmSnapshot?=null,
    val gpsSource:GpsDataSource=GpsDataSource.SYSTEM,
    val nmeaLossAnnounced:Boolean=false,
    val positionDegradedReason:String?=null,
    val learningSampleCount:Int=0,
)

interface AnchorRuntimeHost{
    fun notificationPermissionGranted():Boolean
    fun enableSystemGps():Boolean
    fun notify(title:String,message:String,high:Boolean)
    fun refresh()
    fun sound()
    fun silence()
    fun cancelUrgentNotification()
    fun releaseIfIdle()
}

/**
 * Single-consumer anchor safety runtime. The service serializes calls into this
 * object; all mutable alarm/session/estimator state therefore lives here rather
 * than in the Android Service container.
 */
class AnchorWatchRuntime(
    private val navigation:NavigationRepository,
    private val dao:AnchorDao,
    private val preferences:SettingsRepository,
    private val mockGps:GlobalMockLocationManager,
    private val systemLocation:SystemLocationRepository,
    private val demoLocation:DemoLocationRepository,
    private val phoneHeading:PhoneHeadingRepository,
    private val acceptedPosition:AcceptedPositionRepository,
    private val alarmUi:AlarmUiRepository,
    private val sonarRecorder:SonarSurveyRecorder,
    private val resources:RuntimeResourceManager,
    private val diagnostics:RuntimeDiagnosticsRepository,
    private val nmeaRuntime:NmeaRuntime,
    private val liveDepth:LiveDepthRepository,
    private val liveWind:LiveWindRepository,
    private val host:AnchorRuntimeHost,
    private val monotonicClock:MonotonicClock=SystemMonotonicClock,
    private val wallClock:WallClock=SystemWallClock,
){
    private var engine=AlarmEngine(clock=monotonicClock)
    private var session:AnchorSessionEntity?=null
    private var lastSnapshot:AlarmSnapshot?=null
    private var lastTrack=0L
    private var lastReportedAlarm:AlarmType?=null
    private var nmeaLossAnnounced=false
    private var positionDegradedSince:Long?=null
    private var positionDegradedReason:String?=null
    private var currentGpsSource=GpsDataSource.SYSTEM
    private var restoredDemoElapsed=0L
    private var lastEstimateAt=0L
    private var lastEstimateSampleCount=0
    private var demoSonar:DemoSonarGenerator?=null
    private val estimator=BackdownCenterEstimator()
    private val driftDetector=CandidateDriftDetector()
    private val samples=mutableListOf<BackdownCenterEstimator.Sample>()

    fun snapshot()=AnchorRuntimeSnapshot(session,lastSnapshot,currentGpsSource,nmeaLossAnnounced,positionDegradedReason,samples.size)
    fun activeSession()=session
    fun alarmSnapshot()=lastSnapshot
    fun selectIdleSource(source:GpsDataSource){if(session==null)currentGpsSource=source}

    /**
     * Condition monitoring persists independent fields on the same Room row. The
     * coordinator calls this from the serialized anchor actor immediately after
     * those writes so a later anchor update cannot put an older row back.
     */
    suspend fun refreshSessionFromDatabase(){
        val current=session?:return
        dao.session(current.id)?.takeIf{it.active}?.let{session=it}
    }

    suspend fun restore(initialSettings:AppSettings):AppSettings{
        var settings=initialSettings
        session=dao.active()
        val lockedSource=session?.positionSource?.let{runCatching{GpsDataSource.valueOf(it)}.getOrNull()}
        if(lockedSource!=null&&lockedSource!=settings.gpsDataSource){settings=settings.copy(gpsDataSource=lockedSource);preferences.save(settings)}
        currentGpsSource=lockedSource?:settings.gpsDataSource
        if(session==null)acceptedPosition.selectSource(currentGpsSource)
        driftDetector.reset()
        if(session==null)alarmUi.clear()
        session?.let{active->
            engine=alarmEngine(settings)
            val points=dao.points(active.id).first()
            val events=dao.events(active.id).first()
            driftDetector.restore(events.filter{it.type=="ESTIMATED_CENTER_HISTORY"}.mapNotNull{CandidateCenterObservation.decode(it.detail)},events.any{it.type=="POSSIBLE_ANCHOR_DRAG_TREND"})
            restoredDemoElapsed=(if(active.paused)points.lastOrNull()?.timestamp?.minus(active.startedAt) else wallClock.currentTimeMillis()-active.startedAt)?.coerceAtLeast(0L)?:0L
            acceptedPosition.lockSource(active.id,currentGpsSource)
            acceptedPosition.setPhoneHeadingEvidenceEnabled(active.usePhoneHeading)
            points.lastOrNull()?.let{point->
                val elapsed=(monotonicClock.elapsedRealtime()-(wallClock.currentTimeMillis()-point.timestamp).coerceAtLeast(0L)).coerceAtLeast(1L)
                acceptedPosition.seed(currentGpsSource,NavigationFix(latitude=point.latitude,longitude=point.longitude,timestampUtcMillis=point.timestamp,receivedElapsedRealtime=elapsed,sogKnots=point.sog,cogTrueDegrees=point.cog,headingTrueDegrees=point.heading,hdop=point.hdop,horizontalAccuracyMeters=point.horizontalAccuracyMeters,positionProvider=runCatching{PositionProvider.valueOf(point.positionProvider)}.getOrDefault(PositionProvider.UNKNOWN),sourceSentence="PERSISTED_ACCEPTED_FIX",valid=true,headingSource=runCatching{HeadingSource.valueOf(point.headingSource)}.getOrDefault(HeadingSource.NONE),headingQuality=runCatching{HeadingQuality.valueOf(point.headingQuality)}.getOrDefault(HeadingQuality.UNAVAILABLE),headingEpoch=point.headingEpoch,headingSampleSequence=point.headingSampleSequence,windDirectionTrueDegrees=point.windDirectionTrue,windSpeedKnots=point.windSpeedKnots,apparentWindAngleDegrees=point.apparentWindAngle,trueWindAngleDegrees=point.trueWindAngle,trueWindSpeedKnots=point.trueWindSpeedKnots,apparentWindSpeedKnots=point.apparentWindSpeedKnots),active.id)
            }
            if(active.centerStatus!=AnchorCenterStatus.RESOLVED.name){
                samples.addAll(points.filter{it.fixTrust!=FixTrust.REJECTED.name&&it.fixTrust!=FixTrust.QUARANTINED.name}.map{it.toEstimatorSample()})
                lastSnapshot=engine.learn(active.learningConfig(),monotonicClock.elapsedRealtime())
            }else lastSnapshot=engine.arm(active.config(),monotonicClock.elapsedRealtime())
            if(!active.paused)setResources(active,settings)
        }
        if(session?.paused==false){
            when(currentGpsSource){
                GpsDataSource.NMEA->nmeaRuntime.ensureConnected(settings.profile)
                GpsDataSource.SYSTEM->host.enableSystemGps()
                GpsDataSource.DEMO->{host.enableSystemGps();session?.let{active->demoSonar=DemoSonarGenerator(demoSeed(active),settings.demoScenario);if(!demoLocation.status.value.running)demoLocation.start(active.learningReferenceLatitude?:active.anchorLatitude,active.learningReferenceLongitude?:active.anchorLongitude,runCatching{AnchorPlacementMode.valueOf(active.placementMode)}.getOrDefault(AnchorPlacementMode.CENTER_DROP),settings.demoScenario,active.alarmRadiusMeters,settings.demoSpeedMultiplier,initialElapsedMillis=restoredDemoElapsed,seed=demoSeed(active))else demoLocation.resume()}}
            }
        }else if(session?.paused==true&&currentGpsSource==GpsDataSource.DEMO){
            session?.let{active->if(!demoLocation.status.value.running)demoLocation.start(active.learningReferenceLatitude?:active.anchorLatitude,active.learningReferenceLongitude?:active.anchorLongitude,runCatching{AnchorPlacementMode.valueOf(active.placementMode)}.getOrDefault(AnchorPlacementMode.CENTER_DROP),settings.demoScenario,active.alarmRadiusMeters,settings.demoSpeedMultiplier,initialElapsedMillis=restoredDemoElapsed,seed=demoSeed(active));demoLocation.pause()}
        }
        host.refresh()
        return settings
    }

    suspend fun arm(request:ArmRequest){
        if(session!=null){host.notify("Anchor session already open","Pause, resume or lift the current anchor before starting another session.",true);return}
        var settings=preferences.settings.first();val now=monotonicClock.elapsedRealtime();val positionSource=request.positionSource?:settings.gpsDataSource
        val geometryRequired=request.placement==AnchorPlacementMode.BACKDOWN||request.rangeMode==AnchorRangeMode.ADVANCED
        val config=if(geometryRequired&&request.depthSource==AnchorDepthSource.NMEA){
            val live=liveDepth.state.value
            if(!AnchorSetupDepthPolicy.nmeaAvailable(navigation.connectionState.value,live.depthMeters,live.receivedElapsedRealtime,now)){
                host.notify("Anchor watch not started","NMEA depth was selected, but the NMEA stream is not connected or its DPT/DBT depth is no longer fresh. Reconnect it or choose Manual depth.",true)
                return
            }
            request.config.copy(waterDepthMeters=live.depthMeters)
        }else request.config
        if(!host.notificationPermissionGranted()){host.notify("Anchor watch not started","Notification permission is required so background safety alarms remain visible.",true);return}
        if(settings.demoMode&&positionSource!=GpsDataSource.DEMO){host.notify("Anchor watch not started","Demo mode locks the position source to Demo GPS.",true);return}
        if(!settings.demoMode&&positionSource==GpsDataSource.DEMO){host.notify("Anchor watch not started","Demo GPS is only available while Developer demo mode is enabled.",true);return}
        if(positionSource==GpsDataSource.SYSTEM&&GpsSourceSafety.blocksSystemGps(settings.mockEnabled,mockGps.status.value.state)){host.notify("Anchor watch not started","Phone GPS is not independent while the global NMEA GPS proxy is active. Disable the proxy first.",true);return}
        if(config.latitude !in -90.0..90.0||config.longitude !in -180.0..180.0||!config.latitude.isFinite()||!config.longitude.isFinite()){host.notify("Anchor watch not started","Enter a valid anchor coordinate.",true);return}
        if(geometryRequired){val depth=config.waterDepthMeters;val rode=config.rodeLengthMeters;val bow=config.bowRollerHeightMeters;if(depth==null||depth<0||bow<=0||rode<=depth+bow||(request.rangeMode==AnchorRangeMode.ADVANCED&&(request.boatLength?:0.0)<=0)){host.notify("Anchor watch not started","The selected setup requires valid water depth, rode, bow height and boat length; rode must exceed the total vertical depth.",true);return}}
        val sourceReady=when(positionSource){GpsDataSource.NMEA->navigation.connectionState.value==NmeaConnectionState.CONNECTED;GpsDataSource.SYSTEM,GpsDataSource.DEMO->host.enableSystemGps()}
        val latestFix=when(positionSource){GpsDataSource.NMEA->navigation.fix.value;GpsDataSource.SYSTEM,GpsDataSource.DEMO->systemLocation.fix.value}
        val lastFix=if(positionSource==GpsDataSource.NMEA)navigation.diagnostics.value.lastFixElapsed else latestFix?.receivedElapsedRealtime
        val preciseProvider=positionSource==GpsDataSource.NMEA||latestFix?.positionProvider==PositionProvider.ANDROID_GNSS
        val sourceQualityReady=when(positionSource){GpsDataSource.NMEA->NmeaFixQualityPolicy.allowsContinuation(latestFix);GpsDataSource.SYSTEM,GpsDataSource.DEMO->(latestFix?.horizontalAccuracyMeters?:Double.POSITIVE_INFINITY)<=30.0}
        val freshFix=sourceReady&&latestFix?.valid==true&&preciseProvider&&sourceQualityReady&&lastFix!=null&&now-lastFix<settings.gpsLossSeconds*1_000L
        if(!freshFix){val label=when(positionSource){GpsDataSource.NMEA->"NMEA";GpsDataSource.SYSTEM->"system GNSS (not coarse network location)";GpsDataSource.DEMO->"system GNSS origin for Demo"};host.notify("Anchor watch not started","A live, current $label GPS fix is required. Existing connections were left unchanged.",false);host.refresh();host.releaseIfIdle();return}
        val conditions=request.conditions.validated()
        if(positionSource!=GpsDataSource.DEMO&&(conditions.depthGuardEnabled||conditions.windGuardEnabled||conditions.windShiftEnabled)&&navigation.connectionState.value!=NmeaConnectionState.CONNECTED){host.notify("Anchor watch not started","Condition alerts require an explicitly connected NMEA stream. Connect the boat source and wait for live sensor data.",true);return}
        if(positionSource!=GpsDataSource.DEMO&&conditions.depthGuardEnabled&&!liveDepth.state.value.isFresh(now)){host.notify("Anchor watch not started","Depth guard is enabled but no fresh NMEA depth is available. Disable the guard or wait for the sounder.",true);return}
        val liveWindState=liveWind.state.value
        if(positionSource!=GpsDataSource.DEMO&&conditions.windGuardEnabled&&liveWindState.speed(now,conditions.windAllowApparentFallback)==null){host.notify("Anchor watch not started","Wind guard is enabled but no fresh supported NMEA wind speed is available.",true);return}
        if(positionSource!=GpsDataSource.DEMO&&conditions.windShiftEnabled&&liveWindState.direction(now)==null){host.notify("Anchor watch not started","Wind shift guard requires fresh true wind direction (MWD or coherent MWV-T plus HDT).",true);return}
        val learning=request.placement==AnchorPlacementMode.BACKDOWN
        val c=if(positionSource==GpsDataSource.DEMO||learning)config.copy(latitude=latestFix!!.latitude,longitude=latestFix.longitude,gpsAntennaOffsetMeters=if(positionSource==GpsDataSource.NMEA)config.gpsAntennaOffsetMeters else 0.0)else config.copy(gpsAntennaOffsetMeters=if(positionSource==GpsDataSource.NMEA)config.gpsAntennaOffsetMeters else 0.0)
        val wallNow=wallClock.currentTimeMillis();val horizontalRode=AnchorGeometry.expectedRadius(c.rodeLengthMeters,c.waterDepthMeters,c.bowRollerHeightMeters,c.gpsAntennaOffsetMeters)
        val entity=AnchorSessionEntity(startedAt=wallNow,anchorLatitude=c.latitude,anchorLongitude=c.longitude,rodeLengthMeters=c.rodeLengthMeters,waterDepthMeters=c.waterDepthMeters,bowRollerHeightMeters=c.bowRollerHeightMeters,gpsAntennaOffsetMeters=c.gpsAntennaOffsetMeters,expectedSwingRadiusMeters=horizontalRode,warningRadiusMeters=c.warningRadiusMeters,alarmRadiusMeters=c.alarmRadiusMeters,placementMode=request.placement.name,centerStatus=if(learning)AnchorCenterStatus.LEARNING.name else AnchorCenterStatus.RESOLVED.name,centerResolvedAt=if(learning)null else wallNow,centerConfidence=if(learning)Confidence.LOW.name else Confidence.HIGH.name,centerSampleCount=if(learning)0 else 1,boatLengthMeters=request.boatLength,rangeMode=request.rangeMode.name,safetyPreset=request.safetyPreset.name,learningReferenceLatitude=if(learning)c.latitude else null,learningReferenceLongitude=if(learning)c.longitude else null,provisionalAnchorLatitude=if(learning)c.latitude else null,provisionalAnchorLongitude=if(learning)c.longitude else null,provisionalRadiusMeters=if(learning)maxOf(horizontalRode,c.rodeLengthMeters*.85,25.0) else null,positionSource=positionSource.name,anchorPositionMode=if(learning)AnchorPositionMode.ESTIMATE.name else AnchorPositionMode.KNOWN.name,centerSource=if(learning)AnchorCenterSource.UNKNOWN.name else request.centerSource.name,usePhoneHeading=learning&&request.usePhoneHeading,candidateDecision=CandidateDecision.NONE.name,depthGuardEnabled=conditions.depthGuardEnabled,shallowDepthAlarmMeters=conditions.shallowDepthAlarmMeters,deepDepthAlarmMeters=conditions.deepDepthAlarmMeters,windGuardEnabled=conditions.windGuardEnabled,windWarningKnots=conditions.windWarningKnots,windAlarmKnots=conditions.windAlarmKnots,windShiftEnabled=conditions.windShiftEnabled,windShiftThresholdDegrees=conditions.windShiftThresholdDegrees,windAllowApparentFallback=conditions.windAllowApparentFallback)
        settings=settings.copy(gpsDataSource=positionSource);preferences.save(settings);currentGpsSource=positionSource
        session=entity.copy(id=dao.insertSession(entity));acceptedPosition.lockSource(session!!.id,positionSource);acceptedPosition.setPhoneHeadingEvidenceEnabled(learning&&request.usePhoneHeading);dao.insertEvent(AlarmEventEntity(sessionId=session!!.id,timestamp=wallNow,type=if(learning)"SESSION_STARTED_CENTER_LEARNING" else "SESSION_STARTED",detail="SOURCE=${positionSource.name};CENTER=${request.centerSource.name};DEPTH_SOURCE=${request.depthSource.name}"));samples.clear();driftDetector.reset();clearPositionDegraded();host.silence();lastReportedAlarm=null;engine=alarmEngine(settings);lastSnapshot=if(learning)engine.learn(c,now)else engine.arm(c,now);setResources(session!!,settings);if(positionSource==GpsDataSource.NMEA)nmeaRuntime.ensureConnected(settings.profile);if(positionSource==GpsDataSource.DEMO)demoSonar=DemoSonarGenerator(demoSeed(session!!),settings.demoScenario);val initialFix=if(positionSource==GpsDataSource.DEMO)demoLocation.start(c.latitude,c.longitude,request.placement,settings.demoScenario,c.alarmRadiusMeters,settings.demoSpeedMultiplier,now,seed=demoSeed(session!!))?:latestFix!! else latestFix!!;submitRawFix(initialFix,positionSource)
    }

    suspend fun onNmeaState(state:NmeaConnectionState){
        val watchingNmea=session?.paused==false&&currentGpsSource==GpsDataSource.NMEA
        val lost=state in setOf(NmeaConnectionState.ERROR,NmeaConnectionState.DISCONNECTED,NmeaConnectionState.RECONNECTING,NmeaConnectionState.CONNECTED_NO_DATA,NmeaConnectionState.CONNECTED_NO_FIX,NmeaConnectionState.STALE)
        if(watchingNmea&&lost&&!nmeaLossAnnounced){nmeaLossAnnounced=true;logEvent("NMEA_CONNECTION_LOST",state.name);host.notify("NMEA connection lost","Anchor watch is still active and reconnecting. A GPS-data-loss alarm will follow if valid NMEA positions do not return.",true)}
        if(!watchingNmea)nmeaLossAnnounced=false
        host.refresh()
    }

    suspend fun onPositionHealth(disposition:String,reason:String?,atElapsed:Long?){
        if(disposition!="REJECTED"&&disposition!="QUARANTINED")return
        markPositionDegraded(atElapsed?:monotonicClock.elapsedRealtime(),reason?:disposition)
        logEvent(if(disposition=="REJECTED")"GPS_FIX_REJECTED" else "GPS_SPIKE_QUARANTINED",reason?:disposition)
    }

    fun submitRawFix(rawFix:NavigationFix,source:GpsDataSource){
        val locked=session?.positionSource?.let{runCatching{GpsDataSource.valueOf(it)}.getOrNull()}
        if(locked!=null&&locked!=source)return
        acceptedPosition.setPhoneHeadingEvidenceEnabled(session?.usePhoneHeading==true)
        acceptedPosition.submit(source,rawFix)
    }

    suspend fun onAcceptedPosition(accepted:IntegrityAcceptedFix,source:GpsDataSource){
        val fix=accepted.fix
        if(source==GpsDataSource.DEMO){
            demoSonar?.observation(fix,fix.receivedElapsedRealtime)?.let{observation->liveDepth.accept(observation,isDemo=true);sonarRecorder.submitDemo(observation)}
            liveWind.accept(NmeaUpdate(trueHeading=fix.headingTrueDegrees,trueWindDirection=fix.windDirectionTrueDegrees,trueWindSpeedKnots=fix.trueWindSpeedKnots?:fix.windSpeedKnots,apparentWindSpeedKnots=fix.apparentWindSpeedKnots,trueWindAngle=fix.trueWindAngleDegrees,apparentWindAngle=fix.apparentWindAngleDegrees,type="DEMO"),fix.receivedElapsedRealtime)
        }
        if(accepted.trust==FixTrust.TRUSTED)clearPositionDegraded() else markPositionDegraded(fix.receivedElapsedRealtime,accepted.reason?:accepted.trust.name)
        if(source==GpsDataSource.NMEA&&nmeaLossAnnounced&&session?.paused==false){nmeaLossAnnounced=false;logEvent("NMEA_CONNECTION_RESTORED","");host.notify("NMEA GPS restored","Valid NMEA positions are flowing again; anchor watch remained active.",false)}
        val active=session
        if(active!=null&&!active.paused){
            val snapshot=withPositionQuality(engine.onFix(fix,fix.receivedElapsedRealtime),fix.receivedElapsedRealtime)
            if(snapshot.maxDistanceMeters>active.maxDistanceMeters){val updated=active.copy(maxDistanceMeters=snapshot.maxDistanceMeters);session=updated;dao.updateSession(updated)}
            val pointTime=wallClock.currentTimeMillis()-(monotonicClock.elapsedRealtime()-fix.receivedElapsedRealtime).coerceAtLeast(0L)
            if(pointTime-lastTrack>=900L||accepted.wasQuarantined){
                lastTrack=pointTime
                dao.insertPoint(TrackPointEntity(sessionId=active.id,timestamp=pointTime,latitude=fix.latitude,longitude=fix.longitude,distanceFromAnchor=snapshot.distanceMeters?:0.0,sog=fix.sogKnots,cog=fix.cogTrueDegrees,heading=fix.headingTrueDegrees,hdop=fix.hdop,windDirectionTrue=fix.windDirectionTrueDegrees,windSpeedKnots=fix.windSpeedKnots,apparentWindAngle=fix.apparentWindAngleDegrees,trueWindAngle=fix.trueWindAngleDegrees,trueWindSpeedKnots=fix.trueWindSpeedKnots,apparentWindSpeedKnots=fix.apparentWindSpeedKnots,headingMeasured=fix.headingTrueDegrees!=null,headingSampleSequence=fix.headingSampleSequence,windSampleSequence=fix.windSampleSequence,positionSource=source.name,positionProvider=fix.positionProvider.name,horizontalAccuracyMeters=fix.horizontalAccuracyMeters,fixTrust=accepted.trust.name,wasQuarantined=accepted.wasQuarantined,quarantineReason=accepted.reason,headingSource=fix.headingSource.name,headingQuality=fix.headingQuality.name,headingEpoch=fix.headingEpoch))
                if(active.anchorPositionMode==AnchorPositionMode.ESTIMATE.name){
                    samples+=BackdownCenterEstimator.Sample(latitude=fix.latitude,longitude=fix.longitude,timestamp=pointTime,hdop=fix.hdop,horizontalAccuracyMeters=fix.horizontalAccuracyMeters,positionProvider=fix.positionProvider,fixTrust=accepted.trust,headingTrueDegrees=fix.headingTrueDegrees,cogTrueDegrees=fix.cogTrueDegrees,sogKnots=fix.sogKnots,windDirectionTrueDegrees=fix.windDirectionTrueDegrees,windSpeedKnots=fix.windSpeedKnots,apparentWindAngleDegrees=fix.apparentWindAngleDegrees,trueWindAngleDegrees=fix.trueWindAngleDegrees,trueWindSpeedKnots=fix.trueWindSpeedKnots,apparentWindSpeedKnots=fix.apparentWindSpeedKnots,headingSampleSequence=fix.headingSampleSequence,windSampleSequence=fix.windSampleSequence)
                    compressHistory()
                    if(pointTime-lastEstimateAt>=10_000L&&samples.size-lastEstimateSampleCount>=5){
                        lastEstimateAt=pointTime;lastEstimateSampleCount=samples.size
                        val started=System.nanoTime()
                        val estimate=estimator.provisionalEstimate(samples,active.expectedSwingRadiusMeters)
                        diagnostics.recordEstimatorRun((System.nanoTime()-started)/1_000_000L)
                        updateCandidate(estimate)
                    }
                }
            }
            updateAlarm(snapshot)
        }
    }

    suspend fun acceptCandidate(sessionId:Long,candidateId:Long){
        val current=session
        if(current==null||current.id!=sessionId||current.candidateId!=candidateId||current.candidateDecision!=CandidateDecision.AVAILABLE.name||current.provisionalAnchorLatitude==null||current.provisionalAnchorLongitude==null){logEvent("ANCHOR_CENTER_ACCEPT_REJECTED","STALE_CANDIDATE");return}
        if(lastSnapshot?.state in setOf(AlarmState.WARNING,AlarmState.ALARM,AlarmState.ACKNOWLEDGED)){host.notify("Estimated centre not applied","Return inside the warning boundary or pause the watch before changing its centre.",true);return}
        val now=wallClock.currentTimeMillis();val updated=current.copy(anchorLatitude=current.provisionalAnchorLatitude,anchorLongitude=current.provisionalAnchorLongitude,centerStatus=AnchorCenterStatus.RESOLVED.name,centerResolvedAt=now,centerConfidence=Confidence.HIGH.name,centerSource=AnchorCenterSource.ESTIMATED_USER_ACCEPTED.name,anchorPositionMode=AnchorPositionMode.KNOWN.name,candidateDecision=CandidateDecision.ACCEPTED.name,provisionalAnchorLatitude=null,provisionalAnchorLongitude=null,provisionalRadiusMeters=null,usePhoneHeading=false,alarmSnoozedUntil=null)
        session=updated;dao.updateSessionAndInsertEvent(updated,AlarmEventEntity(sessionId=updated.id,timestamp=now,type="ANCHOR_CENTER_ACCEPTED_BY_USER",detail="candidate=$candidateId;radius=${updated.alarmRadiusMeters}"));acceptedPosition.setPhoneHeadingEvidenceEnabled(false);setResources(updated,preferences.settings.first());engine=alarmEngine(preferences.settings.first());lastSnapshot=engine.arm(updated.config(),monotonicClock.elapsedRealtime());alarmUi.publish(lastSnapshot!!);host.silence();host.refresh()
    }

    suspend fun keepCurrentCenter(sessionId:Long,candidateId:Long){
        val current=session
        if(current==null||current.id!=sessionId||current.candidateId!=candidateId||current.candidateDecision!=CandidateDecision.AVAILABLE.name){logEvent("ANCHOR_CENTER_KEEP_CURRENT_REJECTED","STALE_CANDIDATE");return}
        if(lastSnapshot?.state in setOf(AlarmState.WARNING,AlarmState.ALARM,AlarmState.ACKNOWLEDGED)){host.notify("Centre decision not applied","Return inside the warning boundary or pause the watch first.",true);return}
        val now=wallClock.currentTimeMillis();val updated=current.copy(centerStatus=AnchorCenterStatus.RESOLVED.name,centerResolvedAt=now,centerConfidence=Confidence.LOW.name,centerSource=AnchorCenterSource.CURRENT_POSITION.name,anchorPositionMode=AnchorPositionMode.KNOWN.name,candidateDecision=CandidateDecision.REJECTED.name,provisionalAnchorLatitude=null,provisionalAnchorLongitude=null,provisionalRadiusMeters=null,usePhoneHeading=false)
        session=updated;dao.updateSessionAndInsertEvent(updated,AlarmEventEntity(sessionId=updated.id,timestamp=now,type="ANCHOR_CENTER_CURRENT_KEPT",detail="candidate=$candidateId;estimator=stopped"));acceptedPosition.setPhoneHeadingEvidenceEnabled(false);setResources(updated,preferences.settings.first());engine=alarmEngine(preferences.settings.first());lastSnapshot=engine.arm(updated.config(),monotonicClock.elapsedRealtime());host.refresh()
    }

    suspend fun continueEstimating(sessionId:Long,candidateId:Long){
        val current=session
        if(current==null||current.id!=sessionId||current.candidateId!=candidateId||current.candidateDecision!=CandidateDecision.AVAILABLE.name){logEvent("ANCHOR_CENTER_CONTINUE_REJECTED","STALE_CANDIDATE");return}
        val updated=current.copy(centerStatus=AnchorCenterStatus.LEARNING.name,candidateId=null,candidateCreatedAt=null,candidateDecision=CandidateDecision.NONE.name,candidateNotificationShown=false)
        session=updated;dao.updateSession(updated);dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=wallClock.currentTimeMillis(),type="ANCHOR_CENTER_ESTIMATION_CONTINUED",detail="previousCandidate=$candidateId"));host.refresh()
    }

    suspend fun updatePhoneHeading(enabled:Boolean){
        val current=session?:return
        if(current.anchorPositionMode!=AnchorPositionMode.ESTIMATE.name||current.centerStatus==AnchorCenterStatus.RESOLVED.name){host.notify("Phone heading not changed","Phone heading is only estimator evidence while an anchor centre is still being learned.",false);return}
        if(current.usePhoneHeading==enabled)return
        if(enabled&&!phoneHeading.isAvailable()){host.notify("Phone heading unavailable","This device does not provide a compatible rotation sensor. GPS and wind estimation continue normally.",true);return}
        val settings=preferences.settings.first();val updated=current.copy(usePhoneHeading=enabled)
        setResources(updated,settings)
        if(enabled&&!current.paused&&!resources.snapshot().phoneHeadingActive){setResources(current,settings);host.notify("Phone heading unavailable","Android could not start the phone orientation sensor. Existing estimator evidence was preserved.",true);return}
        session=updated;dao.updateSession(updated);dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=wallClock.currentTimeMillis(),type=if(enabled)"PHONE_HEADING_ENABLED" else "PHONE_HEADING_DISABLED",detail="HISTORICAL_EVIDENCE_RETAINED"));host.notify(if(enabled)"Phone heading enabled" else "Phone heading disabled",if(enabled)"New stable phone-heading samples will assist the estimate in a new calibration epoch; existing samples remain available." else "No new phone-heading samples will be added; samples already used by this session remain available to the estimator.",false);host.refresh()
    }

    suspend fun snooze(){
        val current=session?:return;if(current.paused||lastSnapshot?.type==null)return
        val minutes=preferences.settings.first().alarmSnoozeMinutes;val until=AlarmReminderPolicy.snoozeUntil(wallClock.currentTimeMillis(),minutes);val updated=current.copy(alarmSnoozedUntil=until);dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=wallClock.currentTimeMillis(),type="ALARM_SNOOZED",detail="${minutes}m"));session=updated;dao.updateSession(updated);lastSnapshot=engine.acknowledge();alarmUi.publish(lastSnapshot!!);host.silence();host.refresh()
    }

    suspend fun pause(){
        val current=session?:return;if(current.paused)return
        val updated=current.copy(paused=true,alarmSnoozedUntil=null);session=updated;dao.updateSession(updated);dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=wallClock.currentTimeMillis(),type="SESSION_PAUSED"));if(currentGpsSource==GpsDataSource.DEMO)demoLocation.pause();resources.release(RuntimeOwner.ANCHOR_WATCH);engine.stop();lastSnapshot=AlarmSnapshot(AlarmState.STOPPED);alarmUi.clear();lastReportedAlarm=null;nmeaLossAnnounced=false;clearPositionDegraded();host.silence();host.cancelUrgentNotification();host.refresh();host.releaseIfIdle()
    }

    suspend fun resume(){
        val current=(session?:dao.active())?:return
        if(!current.paused){session=current;host.refresh();return}
        val settings=preferences.settings.first();currentGpsSource=runCatching{GpsDataSource.valueOf(current.positionSource)}.getOrDefault(settings.gpsDataSource);setResources(current.copy(paused=false),settings);val lossMillis=settings.gpsLossSeconds*1_000L
        val fix=when(currentGpsSource){
            GpsDataSource.NMEA->{nmeaRuntime.ensureConnected(settings.profile);withTimeoutOrNull(10_000){navigation.connectionState.first{it==NmeaConnectionState.CONNECTED};navigation.fix.filterNotNull().filter{it.valid&&monotonicClock.elapsedRealtime()-it.receivedElapsedRealtime<lossMillis}.first()}}
            GpsDataSource.SYSTEM->{if(!host.enableSystemGps())null else withTimeoutOrNull(10_000){systemLocation.fix.filterNotNull().filter{it.valid&&monotonicClock.elapsedRealtime()-it.receivedElapsedRealtime<lossMillis}.first()}}
            GpsDataSource.DEMO->{if(!host.enableSystemGps())null else demoLocation.resume()?:demoLocation.start(current.learningReferenceLatitude?:current.anchorLatitude,current.learningReferenceLongitude?:current.anchorLongitude,runCatching{AnchorPlacementMode.valueOf(current.placementMode)}.getOrDefault(AnchorPlacementMode.CENTER_DROP),settings.demoScenario,current.alarmRadiusMeters,settings.demoSpeedMultiplier,seed=demoSeed(current))}
        }
        if(fix==null){host.notify("Anchor watch remains paused","A fresh ${when(currentGpsSource){GpsDataSource.NMEA->"NMEA";GpsDataSource.SYSTEM->"System";GpsDataSource.DEMO->"Demo"}} GPS position is required before resuming.",true);session=current;host.releaseIfIdle();return}
        val resumedAt=monotonicClock.elapsedRealtime();val updated=current.copy(paused=false,alarmSnoozedUntil=null);session=updated;dao.updateSession(updated);setResources(updated,settings);acceptedPosition.lockSource(updated.id,currentGpsSource);acceptedPosition.setPhoneHeadingEvidenceEnabled(updated.usePhoneHeading);clearPositionDegraded();engine=alarmEngine(settings);lastSnapshot=if(updated.centerStatus!=AnchorCenterStatus.RESOLVED.name)engine.learn(updated.learningConfig(),resumedAt)else engine.arm(updated.config(),resumedAt);submitRawFix(fix,currentGpsSource);dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=wallClock.currentTimeMillis(),type="SESSION_RESUMED"));host.notify("Anchor watch resumed","The existing anchor centre, track and alarm range were preserved.",false)
    }

    suspend fun lift(){
        val current=session?:dao.active()?:return;val now=wallClock.currentTimeMillis();dao.insertEvent(AlarmEventEntity(sessionId=current.id,timestamp=now,type="ANCHOR_LIFTED"));dao.updateSession(current.copy(active=false,paused=false,endedAt=now,alarmSnoozedUntil=null));if(currentGpsSource==GpsDataSource.DEMO)demoLocation.stop();demoSonar=null;resources.release(RuntimeOwner.ANCHOR_WATCH);acceptedPosition.unlockSource(current.id);session=null;samples.clear();driftDetector.reset();clearPositionDegraded();engine.stop();lastSnapshot=null;alarmUi.clear();lastReportedAlarm=null;nmeaLossAnnounced=false;host.silence();host.cancelUrgentNotification();host.refresh();host.releaseIfIdle()
    }

    suspend fun updateRadius(requestedRadius:Double){
        // Commands can arrive immediately after Android recreates the service. The
        // command actor normally waits for restore, but recovering the active Room
        // row here makes a safety-critical range change idempotent instead of
        // silently dropping it if framework lifecycle delivery races restoration.
        val current=session?:dao.active()?:return
        if(session==null)session=current
        val previous=lastSnapshot
        val alarmWasActive=previous?.state==AlarmState.ALARM||previous?.state==AlarmState.ACKNOWLEDGED
        val hadRadiusAlarm=alarmWasActive&&previous?.type==AlarmType.ANCHOR_RADIUS_EXCEEDED
        val alarm=requestedRadius.takeIf{it.isFinite()&&it>0}?:return
        val warning=maxOf(alarm*.8,alarm-10).coerceAtMost(alarm-.1)
        val settings=preferences.settings.first()
        val resetAt=monotonicClock.elapsedRealtime()
        val acceptedFix=acceptedPosition.state.value.acceptedFix
        val freshAcceptedFix=acceptedFix?.takeIf{resetAt-it.receivedElapsedRealtime<settings.gpsLossSeconds*1_000L}
        val currentDistance=freshAcceptedFix?.let{AnchorGeometry.distanceMeters(current.anchorLatitude,current.anchorLongitude,it.latitude,it.longitude)}?:previous?.distanceMeters
        val dangerStillRemains=hadRadiusAlarm&&currentDistance?.let{it>alarm}==true
        val preservedAlarmType=when{dangerStillRemains->AlarmType.ANCHOR_RADIUS_EXCEEDED;alarmWasActive&&previous?.type!=AlarmType.ANCHOR_RADIUS_EXCEEDED->previous?.type;else->null}
        val snoozedUntil=when{dangerStillRemains->AlarmReminderPolicy.snoozeUntil(wallClock.currentTimeMillis(),settings.alarmSnoozeMinutes);preservedAlarmType!=null->current.alarmSnoozedUntil;else->null}
        val updated=current.copy(alarmRadiusMeters=alarm,warningRadiusMeters=warning,alarmSnoozedUntil=snoozedUntil)
        session=updated;dao.updateSession(updated);lastReportedAlarm=preservedAlarmType
        if(!updated.paused){lastSnapshot=engine.updateConfig(if(updated.centerStatus!=AnchorCenterStatus.RESOLVED.name)updated.learningConfig()else updated.config(),preservedAlarmType);if(preservedAlarmType==null||preservedAlarmType==AlarmType.ANCHOR_RADIUS_EXCEEDED)freshAcceptedFix?.let{fix->lastSnapshot=engine.onFix(fix,resetAt)};updateAlarm(lastSnapshot!!)}
        dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=wallClock.currentTimeMillis(),type="ALARM_RANGE_CHANGED",detail="${current.alarmRadiusMeters.toInt()}m_TO_${alarm.toInt()}m"));if(hadRadiusAlarm&&!dangerStillRemains)dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=wallClock.currentTimeMillis(),type="ALARM_CLEARED_BY_RANGE_CHANGE",detail="${alarm.toInt()}m"));host.notify("Anchor range updated","Alarm radius is now ${alarm.toInt()} m for this session.",false);host.refresh();host.releaseIfIdle()
    }

    suspend fun watchdog(){
        val now=monotonicClock.elapsedRealtime();if(session?.paused==false&&currentGpsSource==GpsDataSource.DEMO)demoLocation.tick(now)?.let{submitRawFix(it,GpsDataSource.DEMO)};session?.takeIf{!it.paused}?.let{updateAlarm(withPositionQuality(engine.tick(now),now))};host.refresh()
    }

    suspend fun logEvent(type:String,detail:String){session?.let{dao.insertEvent(AlarmEventEntity(sessionId=it.id,timestamp=wallClock.currentTimeMillis(),type=type,detail=detail))}}

    private suspend fun updateCandidate(estimate:BackdownAnchorEstimate?){
        val current=session?.takeIf{it.anchorPositionMode==AnchorPositionMode.ESTIMATE.name}?:return
        if(estimate==null)return
        if(estimate.confidence==Confidence.HIGH){val observation=CandidateCenterObservation(wallClock.currentTimeMillis(),estimate.latitude,estimate.longitude,estimate.uncertaintyRadiusMeters);when(driftDetector.add(observation)){CandidateDriftUpdate.IGNORED->Unit;CandidateDriftUpdate.RECORDED->dao.insertEvent(AlarmEventEntity(sessionId=current.id,timestamp=observation.timestamp,type="ESTIMATED_CENTER_HISTORY",detail=observation.encode()));CandidateDriftUpdate.POSSIBLE_DRAG->{dao.insertEvent(AlarmEventEntity(sessionId=current.id,timestamp=observation.timestamp,type="ESTIMATED_CENTER_HISTORY",detail=observation.encode()));dao.insertEvent(AlarmEventEntity(sessionId=current.id,timestamp=observation.timestamp,type="POSSIBLE_ANCHOR_DRAG_TREND",detail="Candidate centre moved persistently in one direction; formal radius alarm remains authoritative."));host.notify("Possible slow anchor movement","The estimated centre has moved persistently in one direction. This is an advisory only; check the vessel and the formal alarm boundary.",false)}}}
        val existing=current.provisionalRadiusMeters;val high=estimate.confidence==Confidence.HIGH;val first=current.candidateId==null;val qualityNotWorse=(current.candidateRmsErrorMeters==null||estimate.rmsErrorMeters==null||estimate.rmsErrorMeters<=current.candidateRmsErrorMeters*1.10)&&(current.candidateAngularCoverageDegrees==null||estimate.angularCoverageDegrees>=current.candidateAngularCoverageDegrees-10.0)&&(estimate.angularSectorCount>=current.candidateAngularSectorCount-1)&&(!current.candidateTemporalFitConsistent||estimate.temporalFitConsistent);val improved=(existing==null||estimate.uncertaintyRadiusMeters<=existing*.85)&&qualityNotWorse;val previewImproved=first&&(existing==null||estimate.uncertaintyRadiusMeters<=existing*.97)&&qualityNotWorse;val makeAvailable=high&&(first||improved)
        if(!makeAvailable&&!previewImproved){if(first&&estimate.sampleCount>current.centerSampleCount){val progress=current.copy(centerConfidence=estimate.confidence.name,centerSampleCount=estimate.sampleCount);session=progress;dao.updateSession(progress)};return}
        val candidateId=if(makeAvailable)wallClock.currentTimeMillis() else current.candidateId
        val updated=current.copy(centerStatus=if(makeAvailable&&current.centerStatus!=AnchorCenterStatus.RESOLVED.name)AnchorCenterStatus.CANDIDATE_READY.name else current.centerStatus,provisionalAnchorLatitude=estimate.latitude,provisionalAnchorLongitude=estimate.longitude,provisionalRadiusMeters=estimate.uncertaintyRadiusMeters,centerConfidence=estimate.confidence.name,centerSampleCount=estimate.sampleCount,candidateId=candidateId,candidateCreatedAt=if(makeAvailable)wallClock.currentTimeMillis() else current.candidateCreatedAt,candidateDecision=if(makeAvailable)CandidateDecision.AVAILABLE.name else current.candidateDecision,candidateNotificationShown=if(makeAvailable)true else current.candidateNotificationShown,candidateRmsErrorMeters=estimate.rmsErrorMeters,candidateAngularCoverageDegrees=estimate.angularCoverageDegrees,candidateAngularSectorCount=estimate.angularSectorCount,candidateSwingReversalCount=estimate.swingReversalCount,candidateTemporalFitConsistent=estimate.temporalFitConsistent,candidateEffectiveDurationMillis=estimate.effectiveDurationMillis,candidateDirectionEvidenceConsistent=estimate.directionEvidenceConsistent)
        session=updated;dao.updateSession(updated)
        if(makeAvailable){dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=wallClock.currentTimeMillis(),type="ESTIMATED_CENTER_HIGH",detail="candidate=$candidateId;uncertainty=${estimate.uncertaintyRadiusMeters.toInt()}m;rms=${estimate.rmsErrorMeters};coverage=${estimate.angularCoverageDegrees.toInt()};sectors=${estimate.angularSectorCount};reversals=${estimate.swingReversalCount};temporal=${estimate.temporalFitConsistent}"));host.notify("Estimated anchor centre ready","A high-confidence candidate is ready. The working alarm circle has not moved; review it in Watch.",false)}
    }

    private suspend fun updateAlarm(snapshot:AlarmSnapshot){
        val now=wallClock.currentTimeMillis();var active=session;val settled=active
        if(snapshot.state!=AlarmState.ALARM&&snapshot.state!=AlarmState.WARNING&&snapshot.state!=AlarmState.ACKNOWLEDGED&&settled?.alarmSnoozedUntil!=null){val updated=settled.copy(alarmSnoozedUntil=null);active=updated;session=updated;dao.updateSession(updated)}
        val expired=active;if(expired?.alarmSnoozedUntil?.let{it<=now}==true){val updated=expired.copy(alarmSnoozedUntil=null);active=updated;session=updated;dao.updateSession(updated)}
        lastSnapshot=snapshot;alarmUi.publish(snapshot);val critical=snapshot.state==AlarmState.ALARM
        if(AlarmReminderPolicy.shouldSound(snapshot,active?.paused?:true,active?.alarmSnoozedUntil,now))host.sound()else host.silence()
        if(critical&&snapshot.type!=lastReportedAlarm){lastReportedAlarm=snapshot.type;session?.let{current->val updated=current.copy(alarmCount=current.alarmCount+1);session=updated;dao.updateSession(updated)};logEvent("ALARM_TRIGGERED",snapshot.type?.name?:"")}
        if(!critical&&snapshot.state!=AlarmState.ACKNOWLEDGED)lastReportedAlarm=null
        host.refresh()
    }

    private fun markPositionDegraded(atElapsed:Long,reason:String){if(session?.paused!=false)return;positionDegradedSince=positionDegradedSince?.let{minOf(it,atElapsed)}?:atElapsed;positionDegradedReason=reason}
    private fun clearPositionDegraded(){positionDegradedSince=null;positionDegradedReason=null}
    private fun withPositionQuality(base:AlarmSnapshot,nowElapsed:Long):AlarmSnapshot{if(base.state==AlarmState.ALARM)return base;val since=positionDegradedSince?:return base;return if(session?.paused==false&&nowElapsed-since>=15_000L)base.copy(state=AlarmState.ALARM,type=AlarmType.GPS_QUALITY_BAD)else base}
    private fun compressHistory(){if(samples.size<=8_000)return;val recent=samples.takeLast(3_600);val old=samples.dropLast(3_600).filterIndexed{index,_->index%5==0};samples.clear();samples.addAll((old+recent).sortedBy{it.timestamp});lastEstimateSampleCount=lastEstimateSampleCount.coerceAtMost(samples.size)}
    private fun setResources(active:AnchorSessionEntity,settings:AppSettings){if(active.paused){resources.release(RuntimeOwner.ANCHOR_WATCH);return};val source=runCatching{GpsDataSource.valueOf(active.positionSource)}.getOrDefault(settings.gpsDataSource);val learning=active.centerStatus!=AnchorCenterStatus.RESOLVED.name;resources.set(RuntimeOwner.ANCHOR_WATCH,RuntimeRequirement(needsSystemLocation=source==GpsDataSource.SYSTEM||source==GpsDataSource.DEMO,needsNmeaTransport=source==GpsDataSource.NMEA,needsWakeLock=true,needsWifiLock=source==GpsDataSource.NMEA&&settings.keepWifiAwake,needsPhoneMotion=source==GpsDataSource.SYSTEM,needsPhoneHeading=learning&&active.usePhoneHeading))}
    private fun alarmEngine(settings:AppSettings)=AlarmEngine(settings.alarmPersistenceSeconds*1_000L,gpsLossMillis=settings.gpsLossSeconds*1_000L,clock=monotonicClock)
    private fun demoSeed(active:AnchorSessionEntity)=active.startedAt xor (active.id shl 17)
    private fun AnchorSessionEntity.config()=AnchorConfig(anchorLatitude,anchorLongitude,rodeLengthMeters,waterDepthMeters,bowRollerHeightMeters,gpsAntennaOffsetMeters,warningRadiusMeters,alarmRadiusMeters)
    private fun AnchorSessionEntity.learningConfig()=AnchorConfig(learningReferenceLatitude?:anchorLatitude,learningReferenceLongitude?:anchorLongitude,rodeLengthMeters,waterDepthMeters,bowRollerHeightMeters,gpsAntennaOffsetMeters,warningRadiusMeters,alarmRadiusMeters)
    private fun TrackPointEntity.toEstimatorSample()=BackdownCenterEstimator.Sample(latitude=latitude,longitude=longitude,timestamp=timestamp,hdop=hdop,horizontalAccuracyMeters=horizontalAccuracyMeters,positionProvider=runCatching{PositionProvider.valueOf(positionProvider)}.getOrDefault(PositionProvider.UNKNOWN),fixTrust=runCatching{FixTrust.valueOf(fixTrust)}.getOrDefault(FixTrust.DEGRADED),headingTrueDegrees=heading.takeIf{headingSource==HeadingSource.NMEA_PHYSICAL.name||headingSource==HeadingSource.PHONE.name},cogTrueDegrees=cog,sogKnots=sog,windDirectionTrueDegrees=windDirectionTrue,windSpeedKnots=windSpeedKnots,apparentWindAngleDegrees=apparentWindAngle,trueWindAngleDegrees=trueWindAngle,trueWindSpeedKnots=trueWindSpeedKnots,apparentWindSpeedKnots=apparentWindSpeedKnots,headingSampleSequence=headingSampleSequence,windSampleSequence=windSampleSequence)
}
