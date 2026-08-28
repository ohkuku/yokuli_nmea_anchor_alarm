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
import com.yokuli.anchorwatch.domain.anchor.AnchorCentreEvidenceBaseline
import com.yokuli.anchorwatch.domain.anchor.AnchorCentreEvidenceGrowthPolicy
import com.yokuli.anchorwatch.domain.anchor.AnchorCentreCandidatePolicy
import com.yokuli.anchorwatch.domain.anchor.AnchorCentreApplyPolicy
import com.yokuli.anchorwatch.domain.anchor.AnchorContinuousEstimatePolicy
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
import com.yokuli.anchorwatch.domain.model.AnchorOriginMode
import com.yokuli.anchorwatch.domain.model.AnchorMonitoringPhase
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
import com.yokuli.anchorwatch.location.AcceptedAnchorPositionPolicy
import com.yokuli.anchorwatch.location.AcceptedPositionEvent
import com.yokuli.anchorwatch.location.AnchorRawPositionPrimingPolicy
import com.yokuli.anchorwatch.location.DemoLocationRepository
import com.yokuli.anchorwatch.location.DemoSonarGenerator
import com.yokuli.anchorwatch.location.GlobalMockLocationManager
import com.yokuli.anchorwatch.location.GpsSourceSafety
import com.yokuli.anchorwatch.location.IntegrityAcceptedFix
import com.yokuli.anchorwatch.location.NmeaPositionAwaiter
import com.yokuli.anchorwatch.location.NmeaSourceSelectionPolicy
import com.yokuli.anchorwatch.location.PhoneHeadingRepository
import com.yokuli.anchorwatch.location.SystemLocationRepository
import com.yokuli.anchorwatch.runtime.MonotonicClock
import com.yokuli.anchorwatch.runtime.ArmPositionDiagnostic
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
    val originMode:AnchorOriginMode=AnchorOriginMode.CURRENT_ACCEPTED_POSITION,
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
    fun notifyArmFailure(title:String,message:String,high:Boolean)=notify(title,message,high)
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
    private var monitoringGpsLossMillis=15_000L
    private var restoredDemoElapsed=0L
    private var lastEstimateAt=0L
    private var lastEstimateSampleCount=0
    private var demoSonar:DemoSonarGenerator?=null
    private val estimator=BackdownCenterEstimator()
    private val driftDetector=CandidateDriftDetector()
    private val samples=mutableListOf<BackdownCenterEstimator.Sample>()
    private var estimationBaseline:AnchorCentreEvidenceBaseline?=null
    private var headingEvidencePaused:Boolean?=null
    private var headingEvidenceTransition:Boolean?=null
    private var headingEvidenceTransitionSince=0L
    private var lastProcessedAcceptedKey:List<Any?>?=null
    private val headingEvidenceEpochEvents=setOf("ANCHOR_HEADING_EVIDENCE_ENABLED","ANCHOR_HEADING_EVIDENCE_DISABLED","ANCHOR_HEADING_EVIDENCE_SOURCE_CHANGED","HEADING_EVIDENCE_ENABLED","HEADING_EVIDENCE_DISABLED","HEADING_EVIDENCE_SOURCE_CHANGED")

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
        monitoringGpsLossMillis=(settings.gpsLossSeconds*1_000L).coerceAtLeast(1_000L)
        session=dao.active()?.let{active->
            // Compatibility fields are retained in Room/backup, but they are
            // no longer an enable gate. Upgrade the in-memory/restored row to
            // the automatic-evidence contract without moving its centre.
            if(active.usePhoneHeading&&active.headingEvidenceEnabled)active else active.copy(
                usePhoneHeading=true,
                headingEvidenceEnabled=true,
                headingEvidenceEpoch=active.headingEvidenceEpoch.coerceAtLeast(1L),
                headingEvidenceEnabledAt=active.headingEvidenceEnabledAt?:wallClock.currentTimeMillis(),
            ).also{dao.updateSession(it)}
        }
        val lockedSource=session?.positionSource?.let{runCatching{GpsDataSource.valueOf(it)}.getOrNull()}
        if(lockedSource!=null&&lockedSource!=settings.gpsDataSource){settings=settings.copy(gpsDataSource=lockedSource);preferences.save(settings)}
        currentGpsSource=lockedSource?:settings.gpsDataSource
        if(session==null)acceptedPosition.selectSource(currentGpsSource)
        driftDetector.reset()
        if(session==null)alarmUi.clear()
        session?.let{active->
            val waitingForGps=active.monitoringPhase==AnchorMonitoringPhase.WAITING_FOR_GPS.name||
                (active.monitoringPhase==AnchorMonitoringPhase.PAUSED.name&&active.monitoringActivatedAt==null)
            engine=alarmEngine(settings)
            val points=dao.points(active.id).first()
            val events=dao.events(active.id).first()
            val epochEvents=events.drop(events.indexOfLast{it.type=="ANCHOR_CENTER_ANALYSIS_RESET"}+1)
            val headingEvidenceCutoff=epochEvents.lastOrNull{it.type in headingEvidenceEpochEvents}?.timestamp
            fun headingAllowedAt(timestamp:Long)=headingEvidenceCutoff==null||timestamp>=headingEvidenceCutoff
            estimationBaseline=epochEvents.lastOrNull{it.type=="ANCHOR_CENTER_ESTIMATION_CONTINUED"}?.detail?.let(::decodeBaseline)
            driftDetector.restore(epochEvents.filter{it.type=="ESTIMATED_CENTER_HISTORY"}.mapNotNull{CandidateCenterObservation.decode(it.detail)},epochEvents.any{it.type=="POSSIBLE_ANCHOR_DRAG_TREND"})
            restoredDemoElapsed=(if(active.paused)points.lastOrNull()?.timestamp?.minus(active.startedAt) else wallClock.currentTimeMillis()-active.startedAt)?.coerceAtLeast(0L)?:0L
            acceptedPosition.lockSource(active.id,currentGpsSource)
            points.lastOrNull()?.takeUnless{waitingForGps}?.let{point->
                val elapsed=(monotonicClock.elapsedRealtime()-(wallClock.currentTimeMillis()-point.timestamp).coerceAtLeast(0L)).coerceAtLeast(1L)
                val headingAllowed=headingAllowedAt(point.timestamp)
                acceptedPosition.seed(currentGpsSource,NavigationFix(latitude=point.latitude,longitude=point.longitude,timestampUtcMillis=point.timestamp,receivedElapsedRealtime=elapsed,sogKnots=point.sog,cogTrueDegrees=point.cog,headingTrueDegrees=point.heading.takeIf{headingAllowed},hdop=point.hdop,horizontalAccuracyMeters=point.horizontalAccuracyMeters,positionProvider=runCatching{PositionProvider.valueOf(point.positionProvider)}.getOrDefault(PositionProvider.UNKNOWN),sourceSentence="PERSISTED_ACCEPTED_FIX",valid=true,headingSource=runCatching{HeadingSource.valueOf(point.headingSource)}.getOrDefault(HeadingSource.NONE).takeIf{headingAllowed}?:HeadingSource.NONE,headingQuality=runCatching{HeadingQuality.valueOf(point.headingQuality)}.getOrDefault(HeadingQuality.UNAVAILABLE).takeIf{headingAllowed}?:HeadingQuality.UNAVAILABLE,headingEpoch=point.headingEpoch.takeIf{headingAllowed},headingSampleSequence=point.headingSampleSequence.takeIf{headingAllowed},windDirectionTrueDegrees=point.windDirectionTrue,windSpeedKnots=point.windSpeedKnots,apparentWindAngleDegrees=point.apparentWindAngle,trueWindAngleDegrees=point.trueWindAngle,trueWindSpeedKnots=point.trueWindSpeedKnots,apparentWindSpeedKnots=point.apparentWindSpeedKnots),active.id)
            }
            if(active.estimationEpoch>0L&&!waitingForGps){
                samples.addAll(points.filter{it.fixTrust!=FixTrust.REJECTED.name&&it.fixTrust!=FixTrust.QUARANTINED.name}.map{point->point.toEstimatorSample(headingAllowedAt(point.timestamp))})
            }
            lastSnapshot=if(waitingForGps)AlarmSnapshot(if(active.paused)AlarmState.STOPPED else AlarmState.SETTING)else if(active.centerStatus!=AnchorCenterStatus.RESOLVED.name)engine.learn(active.learningConfig(),monotonicClock.elapsedRealtime())else engine.arm(active.config(),monotonicClock.elapsedRealtime())
            if(waitingForGps)alarmUi.clear()else lastSnapshot?.let(alarmUi::publish)
            if(!active.paused)setResources(active,settings)
        }
        if(session?.paused==false){
            when(currentGpsSource){
                GpsDataSource.NMEA->nmeaRuntime.ensureSafetyConnected(settings.profile)
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
        if(session!=null){host.notifyArmFailure("Anchor session already open","Pause, resume or lift the current anchor before starting another session.",true);return}
        val armStartedElapsed=monotonicClock.elapsedRealtime()
        var settings=preferences.settings.first();val positionSource=request.positionSource?:settings.gpsDataSource
        monitoringGpsLossMillis=(settings.gpsLossSeconds*1_000L).coerceAtLeast(1_000L)
        val geometryRequired=request.placement==AnchorPlacementMode.BACKDOWN||request.rangeMode==AnchorRangeMode.ADVANCED
        val config=if(geometryRequired&&request.depthSource==AnchorDepthSource.NMEA){
            val live=liveDepth.state.value
            // Read the evidence before taking the comparison clock. A depth or
            // position collector may publish while ARM is running; comparing a
            // newer sample against the command's older start time produces a
            // negative age and used to mislabel live evidence as stale.
            val depthCheckNow=monotonicClock.elapsedRealtime()
            if(!AnchorSetupDepthPolicy.nmeaAvailable(navigation.connectionState.value,live.depthMeters,live.receivedElapsedRealtime,depthCheckNow)){
                host.notifyArmFailure("Anchor watch not started","NMEA depth was selected, but the NMEA stream is not connected or its DPT/DBT depth is no longer fresh. Reconnect it or choose Manual depth.",true)
                return
            }
            request.config.copy(waterDepthMeters=live.depthMeters)
        }else request.config
        if(!host.notificationPermissionGranted()){host.notifyArmFailure("Anchor watch not started","Notification permission is required so background safety alarms remain visible.",true);return}
        if(settings.demoMode&&positionSource!=GpsDataSource.DEMO){host.notifyArmFailure("Anchor watch not started","Demo mode locks the position source to Demo GPS.",true);return}
        if(!settings.demoMode&&positionSource==GpsDataSource.DEMO){host.notifyArmFailure("Anchor watch not started","Demo GPS is only available while Developer demo mode is enabled.",true);return}
        if(positionSource==GpsDataSource.SYSTEM&&GpsSourceSafety.blocksSystemGps(settings.mockEnabled,mockGps.status.value.state)){host.notifyArmFailure("Anchor watch not started","Phone GPS is not independent while the global NMEA GPS proxy is active. Disable the proxy first.",true);return}
        val userCoordinateMode=request.originMode in setOf(AnchorOriginMode.MANUAL_COORDINATE,AnchorOriginMode.MAP_PICK)
        if(userCoordinateMode&&(config.latitude !in -90.0..90.0||config.longitude !in -180.0..180.0||!config.latitude.isFinite()||!config.longitude.isFinite())){host.notifyArmFailure("Anchor watch not started","Enter a valid anchor coordinate.",true);return}
        if(geometryRequired){val depth=config.waterDepthMeters;val rode=config.rodeLengthMeters;val bow=config.bowRollerHeightMeters;if(depth==null||depth<0||bow<=0||rode<=depth+bow||(request.rangeMode==AnchorRangeMode.ADVANCED&&(request.boatLength?:0.0)<=0)){host.notifyArmFailure("Anchor watch not started","The selected setup requires valid water depth, rode, bow height and boat length; rode must exceed the total vertical depth.",true);return}}
        // Runtime is the authority: Current/Estimate coordinates supplied by
        // Compose are never trusted. Manual/Map coordinates may be persisted
        // without GPS, but monitoring remains explicitly WAITING_FOR_GPS.
        val startupNeedsSystem=positionSource in setOf(GpsDataSource.SYSTEM,GpsDataSource.DEMO)
        if(startupNeedsSystem)resources.set(RuntimeOwner.ANCHOR_STARTUP,RuntimeRequirement(needsSystemLocation=true,needsWakeLock=true,needsPhoneMotion=true))
        try{
        if(startupNeedsSystem)host.enableSystemGps()
        if(!acceptedPosition.beginArmAttempt(positionSource)){
            host.notifyArmFailure("Anchor watch not started","Accepted-position evidence is still locked to another anchor session. Return to the active session and pause or lift it before starting a new one.",true)
            return
        }
        // StateFlow may already contain the first provider fix before the
        // accepted-position collector observes it. Prime it synchronously
        // through the same integrity filter; never read raw coordinates as an
        // authoritative anchor origin.
        val preDecisionPrime=if(positionSource!=GpsDataSource.DEMO)primeSelectedRawPosition(positionSource,emitAcceptedEvents=false) else emptyList()
        // Snapshot every piece of accepted-position evidence first and only
        // then read the monotonic comparison clock. The previous implementation
        // captured `now` at command entry. A System or NMEA fix arriving during
        // startup was therefore newer than `now` and the negative age was
        // incorrectly reported as ACCEPTED_POSITION_STALE.
        val acceptedStateSnapshot=acceptedPosition.state.value
        val demoOriginSnapshot=systemLocation.fix.value
        val providerFixSnapshot=when(positionSource){
            GpsDataSource.NMEA->navigation.fix.value
            GpsDataSource.SYSTEM,GpsDataSource.DEMO->demoOriginSnapshot
        }
        val nmeaConnectionSnapshot=navigation.connectionState.value
        val nmeaConnectionStartedSnapshot=navigation.connectionStartedElapsed.value
        val nmeaGenerationSnapshot=navigation.connectionGeneration()
        val positionCheckNow=monotonicClock.elapsedRealtime()
        val acceptedReadiness=if(positionSource==GpsDataSource.DEMO){
            val fix=demoOriginSnapshot?.takeIf{it.valid&&it.positionProvider==PositionProvider.ANDROID_GNSS&&!it.isMockLocation&&(it.horizontalAccuracyMeters?:Double.POSITIVE_INFINITY)<=30.0&&positionCheckNow-it.receivedElapsedRealtime in 0L until monitoringGpsLossMillis}
            com.yokuli.anchorwatch.location.AcceptedAnchorPositionReadiness(fix!=null,fix,if(fix==null)"DEMO_ORIGIN_REQUIRES_PHONE_GNSS" else "READY","Demo origin uses a current precise Phone GNSS fix.")
        }else AcceptedAnchorPositionPolicy.evaluate(acceptedStateSnapshot,positionSource,positionCheckNow,monitoringGpsLossMillis,nmeaConnectionSnapshot,nmeaConnectionStartedSnapshot,nmeaGenerationSnapshot,GpsSourceSafety.blocksSystemGps(settings.mockEnabled,mockGps.status.value.state))
        fun recordArmPositionDecision(outcome:String)=diagnostics.recordArmPositionDiagnostic(ArmPositionDiagnostic(
            requestedSource=positionSource.name,
            outcome=outcome,
            readinessReason=acceptedReadiness.reason,
            armStartedElapsedRealtime=armStartedElapsed,
            decisionElapsedRealtime=positionCheckNow,
            providerReceivedElapsedRealtime=providerFixSnapshot?.receivedElapsedRealtime,
            providerAgeMillis=providerFixSnapshot?.let{positionCheckNow-it.receivedElapsedRealtime},
            providerType=providerFixSnapshot?.positionProvider?.name,
            acceptedSelectedSource=acceptedStateSnapshot.selectedSource.name,
            acceptedDisposition=acceptedStateSnapshot.disposition,
            acceptedReason=acceptedStateSnapshot.reason,
            acceptedReceivedElapsedRealtime=acceptedStateSnapshot.acceptedFix?.receivedElapsedRealtime,
            acceptedAgeMillis=acceptedStateSnapshot.acceptedFix?.let{positionCheckNow-it.receivedElapsedRealtime},
            acceptedLastElapsedRealtime=acceptedStateSnapshot.lastAcceptedElapsedRealtime,
            primeResultCount=preDecisionPrime.size,
            connectionState=nmeaConnectionSnapshot.name,
            connectionStartedElapsedRealtime=nmeaConnectionStartedSnapshot,
            liveConnectionGeneration=nmeaGenerationSnapshot,
            acceptedConnectionGeneration=acceptedStateSnapshot.acceptedConnectionGeneration,
        ))
        val acceptedOriginRequired=request.originMode in setOf(AnchorOriginMode.CURRENT_ACCEPTED_POSITION,AnchorOriginMode.BACKDOWN_FROM_ACCEPTED_POSITION)
        if(acceptedOriginRequired&&!acceptedReadiness.ready){
            recordArmPositionDecision("REJECTED")
            host.notifyArmFailure("Anchor watch not started","The current accepted ${if(positionSource==GpsDataSource.NMEA)"NMEA" else "Phone GNSS"} position is no longer usable (${acceptedReadiness.reason}). Wait for the accepted-position evidence to recover, or choose a manual coordinate / map point.",true)
            return
        }
        recordArmPositionDecision(if(acceptedReadiness.ready)"READY" else "SESSION_WAITING_FOR_GPS")
        val acceptedStartFix=acceptedReadiness.fix
        val acceptedStartGeneration=acceptedStateSnapshot.acceptedConnectionGeneration
        val conditions=request.conditions.validated()
        val conditionDepthSnapshot=liveDepth.state.value
        val liveWindState=liveWind.state.value
        val conditionConnectionSnapshot=navigation.connectionState.value
        val conditionCheckNow=monotonicClock.elapsedRealtime()
        if(positionSource!=GpsDataSource.DEMO&&(conditions.depthGuardEnabled||conditions.windGuardEnabled||conditions.windShiftEnabled)&&!com.yokuli.anchorwatch.domain.condition.ConditionGuardAvailability.hasInstrumentTraffic(conditionConnectionSnapshot)){host.notifyArmFailure("Anchor watch not started","Condition alerts require an explicitly connected NMEA stream. Connect the boat source and wait for live sensor data.",true);return}
        if(positionSource!=GpsDataSource.DEMO&&conditions.depthGuardEnabled&&!conditionDepthSnapshot.isFresh(conditionCheckNow)){host.notifyArmFailure("Anchor watch not started","Depth guard is enabled but no fresh NMEA depth is available. Disable the guard or wait for the sounder.",true);return}
        if(positionSource!=GpsDataSource.DEMO&&conditions.windGuardEnabled&&liveWindState.speed(conditionCheckNow,conditions.windAllowApparentFallback)==null){host.notifyArmFailure("Anchor watch not started","Wind guard is enabled but no fresh supported NMEA wind speed is available.",true);return}
        if(positionSource!=GpsDataSource.DEMO&&conditions.windShiftEnabled&&liveWindState.direction(conditionCheckNow)==null){host.notifyArmFailure("Anchor watch not started","Wind shift guard requires fresh true wind direction (MWD or coherent MWV-T plus HDT).",true);return}
        val learning=request.placement==AnchorPlacementMode.BACKDOWN
        val authoritativeCoordinate=acceptedStartFix.takeIf{acceptedOriginRequired}
        val c=config.copy(latitude=authoritativeCoordinate?.latitude?:config.latitude,longitude=authoritativeCoordinate?.longitude?:config.longitude,gpsAntennaOffsetMeters=if(positionSource==GpsDataSource.NMEA)config.gpsAntennaOffsetMeters else 0.0)
        // A user-confirmed Manual/Map coordinate is always persisted as a
        // WAITING session first. Even when a provider fix was accepted just
        // before ARM, monitoring may start only after lockSource() resets the
        // integrity epoch and that same raw evidence is accepted again. This
        // gives one deterministic WAITING -> ARMED/LEARNING transition and one
        // GPS_MONITORING_ACTIVATED audit event without moving the coordinate.
        val waitingForGps=positionSource!=GpsDataSource.DEMO&&(userCoordinateMode||!acceptedReadiness.ready)
        val monitoringPhase=when{waitingForGps->AnchorMonitoringPhase.WAITING_FOR_GPS;learning->AnchorMonitoringPhase.LEARNING;else->AnchorMonitoringPhase.ARMED}
        val wallNow=wallClock.currentTimeMillis();val horizontalRode=AnchorGeometry.expectedRadius(c.rodeLengthMeters,c.waterDepthMeters,c.bowRollerHeightMeters,c.gpsAntennaOffsetMeters)
        val entity=AnchorSessionEntity(startedAt=wallNow,anchorLatitude=c.latitude,anchorLongitude=c.longitude,rodeLengthMeters=c.rodeLengthMeters,waterDepthMeters=c.waterDepthMeters,bowRollerHeightMeters=c.bowRollerHeightMeters,gpsAntennaOffsetMeters=c.gpsAntennaOffsetMeters,expectedSwingRadiusMeters=horizontalRode,warningRadiusMeters=c.warningRadiusMeters,alarmRadiusMeters=c.alarmRadiusMeters,placementMode=request.placement.name,centerStatus=if(learning)AnchorCenterStatus.LEARNING.name else AnchorCenterStatus.RESOLVED.name,centerResolvedAt=if(learning)null else wallNow,centerConfidence=if(learning)Confidence.LOW.name else Confidence.HIGH.name,centerSampleCount=if(learning)0 else 1,boatLengthMeters=request.boatLength,rangeMode=request.rangeMode.name,safetyPreset=request.safetyPreset.name,learningReferenceLatitude=if(learning)c.latitude else null,learningReferenceLongitude=if(learning)c.longitude else null,provisionalAnchorLatitude=if(learning)c.latitude else null,provisionalAnchorLongitude=if(learning)c.longitude else null,provisionalRadiusMeters=if(learning)maxOf(horizontalRode,c.rodeLengthMeters*.85,25.0) else null,positionSource=positionSource.name,anchorPositionMode=if(learning)AnchorPositionMode.ESTIMATE.name else AnchorPositionMode.KNOWN.name,centerSource=if(learning)AnchorCenterSource.UNKNOWN.name else request.centerSource.name,usePhoneHeading=true,candidateDecision=CandidateDecision.NONE.name,estimationEpoch=if(geometryRequired)1 else 0,estimationEpochStartedAt=if(geometryRequired)wallNow else null,adoptedCenterEpoch=if(learning)0 else 1,depthGuardEnabled=conditions.depthGuardEnabled,shallowDepthAlarmMeters=conditions.shallowDepthAlarmMeters,deepDepthAlarmMeters=conditions.deepDepthAlarmMeters,windGuardEnabled=conditions.windGuardEnabled,windWarningKnots=conditions.windWarningKnots,windAlarmKnots=conditions.windAlarmKnots,windShiftEnabled=conditions.windShiftEnabled,windShiftThresholdDegrees=conditions.windShiftThresholdDegrees,windAllowApparentFallback=conditions.windAllowApparentFallback,headingEvidenceEnabled=true,headingEvidenceEpoch=1,headingEvidenceEnabledAt=wallNow,anchorOriginMode=request.originMode.name,monitoringPhase=monitoringPhase.name,monitoringActivatedAt=wallNow.takeUnless{waitingForGps})
        settings=settings.copy(gpsDataSource=positionSource);preferences.save(settings);currentGpsSource=positionSource
        session=entity.copy(id=dao.insertSession(entity));acceptedPosition.lockSource(session!!.id,positionSource);dao.insertEvent(AlarmEventEntity(sessionId=session!!.id,timestamp=wallNow,type=if(waitingForGps)"SESSION_CREATED_WAITING_FOR_GPS" else if(learning)"SESSION_STARTED_CENTER_LEARNING" else "SESSION_STARTED",detail="SOURCE=${positionSource.name};ORIGIN=${request.originMode.name};CENTER=${request.centerSource.name};DEPTH_SOURCE=${request.depthSource.name};HEADING_EVIDENCE=AUTOMATIC"));samples.clear();driftDetector.reset();lastProcessedAcceptedKey=null;clearPositionDegraded();host.silence();lastReportedAlarm=null;engine=alarmEngine(settings);val monitoringStartedAt=monotonicClock.elapsedRealtime();lastSnapshot=if(waitingForGps)AlarmSnapshot(AlarmState.SETTING)else if(learning)engine.learn(c,monitoringStartedAt)else engine.arm(c,monitoringStartedAt);if(!waitingForGps)lastSnapshot?.let(alarmUi::publish)else alarmUi.clear();setResources(session!!,settings);if(positionSource==GpsDataSource.NMEA)nmeaRuntime.ensureSafetyConnected(settings.profile);if(positionSource==GpsDataSource.DEMO)demoSonar=DemoSonarGenerator(demoSeed(session!!),settings.demoScenario)
        // lockSource() intentionally resets the integrity epoch. Prime again
        // immediately after persistence so WAITING is deterministic and never
        // depends on a future StateFlow emission. The direct callback remains
        // inside this serialized runtime command and cannot be overtaken by an
        // alarm/track update.
        val primedAccepted=if(positionSource==GpsDataSource.DEMO)emptyList() else primeSelectedRawPosition(positionSource,emitAcceptedEvents=false).ifEmpty{
            // The provider StateFlow may be cleared between the pre-lock read
            // and persistence. A fix accepted by the immediately preceding
            // readiness evaluation may be re-submitted, but still goes through
            // the reset integrity filter and retains the verified NMEA epoch.
            acceptedStartFix?.takeIf{fix->positionSource!=GpsDataSource.NMEA||acceptedStartGeneration==navigation.connectionGeneration()&&navigation.connectionStartedElapsed.value?.let{fix.receivedElapsedRealtime>=it}==true}?.let{fix->acceptedPosition.submit(positionSource,fix,navigation.connectionGeneration().takeIf{positionSource==GpsDataSource.NMEA},emitAcceptedEvents=false)}?:emptyList()
        }.ifEmpty{
            // A provider collector can win the lock-to-prime race. Consume its
            // already accepted state synchronously; the later queued event is
            // made harmless by the runtime event key below.
            acceptedEventFromCurrentState(positionSource)?.let(::listOf)?:emptyList()
        }
        val initialFix=if(positionSource==GpsDataSource.DEMO)demoLocation.start(c.latitude,c.longitude,request.placement,settings.demoScenario,c.alarmRadiusMeters,settings.demoSpeedMultiplier,monitoringStartedAt,seed=demoSeed(session!!)) else null
        if(primedAccepted.isNotEmpty()){
            primedAccepted.forEach{event->onAcceptedPosition(event.accepted,event.source,event.headingEvidence,event.connectionGeneration)}
        }else if(initialFix!=null){
            submitRawFix(initialFix,positionSource)
        }else if(waitingForGps){
            val reason=when(positionSource){
                GpsDataSource.NMEA->"NMEA_${navigation.connectionState.value.name}_NO_ACCEPTABLE_CURRENT_FIX"
                GpsDataSource.SYSTEM->"PHONE_GNSS_NO_ACCEPTABLE_CURRENT_FIX"
                GpsDataSource.DEMO->"DEMO_ORIGIN_USES_SETUP_COORDINATE"
            }
            if(positionSource!=GpsDataSource.DEMO)markPositionDegraded(positionCheckNow,reason)
            host.notify(
                "Anchor session created · waiting for GPS",
                when(positionSource){
                    GpsDataSource.NMEA->"The anchor coordinate is saved. Movement monitoring, track recording and centre learning have not started. They will start automatically after a current accepted NMEA position arrives."
                    GpsDataSource.SYSTEM->"The anchor coordinate is saved. Movement monitoring and track recording have not started. They will start automatically after a current accepted Phone GNSS position arrives."
                    GpsDataSource.DEMO->"The Demo session is waiting for its generated position."
                },
                true,
            )
        }
        host.refresh()
        }finally{if(startupNeedsSystem)resources.release(RuntimeOwner.ANCHOR_STARTUP)}
    }

    suspend fun onNmeaState(state:NmeaConnectionState){
        val watchingNmea=session?.paused==false&&currentGpsSource==GpsDataSource.NMEA
        val lost=state in setOf(NmeaConnectionState.ERROR,NmeaConnectionState.DISCONNECTED,NmeaConnectionState.RECONNECTING,NmeaConnectionState.CONNECTED_NO_DATA,NmeaConnectionState.CONNECTED_NO_FIX,NmeaConnectionState.STALE)
        if(watchingNmea&&lost&&!nmeaLossAnnounced){
            nmeaLossAnnounced=true
            logEvent("NMEA_CONNECTION_LOST",state.name)
            host.notify(
                "NMEA connection lost",
                "Anchor watch is still active. Safety-owned recovery continues with bounded retries until NMEA returns or you explicitly pause/stop recovery. A GPS-data-loss alarm will follow if current accepted positions do not return.",
                true,
            )
        }
        if(!watchingNmea)nmeaLossAnnounced=false
        host.refresh()
    }

    suspend fun onPositionHealth(disposition:String,reason:String?,atElapsed:Long?){
        if(disposition!="REJECTED"&&disposition!="QUARANTINED")return
        val firstInEpisode=positionDegradedSince==null
        markPositionDegraded(atElapsed?:monotonicClock.elapsedRealtime(),reason?:disposition)
        if(firstInEpisode)logEvent(if(disposition=="REJECTED")"GPS_FIX_REJECTED" else "GPS_SPIKE_QUARANTINED",reason?:disposition)
    }

    fun submitRawFix(rawFix:NavigationFix,source:GpsDataSource){
        val locked=session?.positionSource?.let{runCatching{GpsDataSource.valueOf(it)}.getOrNull()}
        if(locked!=null&&locked!=source)return
        val active=session?.takeIf{!it.paused}
        val monitoringFixAccepted=when{
            active==null->true
            source==GpsDataSource.NMEA->NmeaSourceSelectionPolicy.isUsablePosition(
                navigation.connectionState.value,
                rawFix,
                navigation.connectionStartedElapsed.value,
                monotonicClock.elapsedRealtime(),
                monitoringGpsLossMillis,
            )
            source==GpsDataSource.SYSTEM->rawFix.valid&&rawFix.positionProvider==PositionProvider.ANDROID_GNSS&&
                (rawFix.horizontalAccuracyMeters?:Double.POSITIVE_INFINITY)<=30.0&&
                monotonicClock.elapsedRealtime()-rawFix.receivedElapsedRealtime in 0L until monitoringGpsLossMillis
            else->rawFix.valid
        }
        if(!monitoringFixAccepted){
            markPositionDegraded(monotonicClock.elapsedRealtime(),"${source.name}_FIX_REJECTED_BY_RUNTIME_HEALTH_GATE")
            return
        }
        acceptedPosition.submit(source,rawFix,navigation.connectionGeneration().takeIf{source==GpsDataSource.NMEA})
    }

    private fun primeSelectedRawPosition(source:GpsDataSource,emitAcceptedEvents:Boolean):List<AcceptedPositionEvent>{
        val candidate=AnchorRawPositionPrimingPolicy.select(
            source=source,
            systemFix=systemLocation.fix.value,
            nmeaFix=navigation.fix.value,
            nmeaConnectionStartedElapsedRealtime=navigation.connectionStartedElapsed.value,
            nmeaConnectionGeneration=navigation.connectionGeneration(),
        )?:return emptyList()
        return acceptedPosition.submit(candidate.source,candidate.fix,candidate.connectionGeneration,emitAcceptedEvents)
    }

    private fun acceptedEventFromCurrentState(source:GpsDataSource):AcceptedPositionEvent?{
        val state=acceptedPosition.state.value
        if(state.selectedSource!=source||state.disposition!="ACCEPTED")return null
        val fix=state.acceptedFix?:return null
        val generation=state.acceptedConnectionGeneration.takeIf{source==GpsDataSource.NMEA}
        if(source==GpsDataSource.NMEA&&generation!=navigation.connectionGeneration())return null
        return AcceptedPositionEvent(source,IntegrityAcceptedFix(fix,state.trust?:FixTrust.DEGRADED,reason=state.reason),state.headingEvidence,generation)
    }

    suspend fun onAcceptedPosition(accepted:IntegrityAcceptedFix,source:GpsDataSource,headingEvidence:com.yokuli.anchorwatch.location.AnchorHeadingEvidence=com.yokuli.anchorwatch.location.AnchorHeadingEvidence(reason="NOT_REPORTED"),connectionGeneration:Long?=null){
        if(source!=currentGpsSource)return
        if(source==GpsDataSource.NMEA&&connectionGeneration!=navigation.connectionGeneration())return
        val fix=accepted.fix
        val eventKey=listOf(source,fix.receivedElapsedRealtime,fix.latitude,fix.longitude,fix.sourceSentence,connectionGeneration)
        if(lastProcessedAcceptedKey==eventKey)return
        lastProcessedAcceptedKey=eventKey
        if(source==GpsDataSource.DEMO){
            demoSonar?.observation(fix,fix.receivedElapsedRealtime)?.let{observation->liveDepth.accept(observation,isDemo=true);sonarRecorder.submitDemo(observation)}
            liveWind.accept(NmeaUpdate(trueHeading=fix.headingTrueDegrees,trueWindDirection=fix.windDirectionTrueDegrees,trueWindSpeedKnots=fix.trueWindSpeedKnots?:fix.windSpeedKnots,apparentWindSpeedKnots=fix.apparentWindSpeedKnots,trueWindAngle=fix.trueWindAngleDegrees,apparentWindAngle=fix.apparentWindAngleDegrees,type="DEMO"),fix.receivedElapsedRealtime)
        }
        if(accepted.trust==FixTrust.TRUSTED)clearPositionDegraded() else markPositionDegraded(fix.receivedElapsedRealtime,accepted.reason?:accepted.trust.name)
        if(source==GpsDataSource.NMEA&&nmeaLossAnnounced&&session?.paused==false){nmeaLossAnnounced=false;logEvent("NMEA_CONNECTION_RESTORED","");host.notify("NMEA GPS restored","Valid NMEA positions are flowing again; anchor watch remained active.",false)}
        var active=session
        if(active!=null&&!active.paused&&active.monitoringPhase==AnchorMonitoringPhase.WAITING_FOR_GPS.name){
            val activatedAt=wallClock.currentTimeMillis()
            val learning=active.placementMode==AnchorPlacementMode.BACKDOWN.name
            val updated=active.copy(
                monitoringPhase=if(learning)AnchorMonitoringPhase.LEARNING.name else AnchorMonitoringPhase.ARMED.name,
                monitoringActivatedAt=activatedAt,
            )
            // The user-confirmed Manual/Map anchor coordinate is deliberately
            // unchanged. This first accepted fix only starts monitoring.
            engine=alarmEngine(preferences.settings.first())
            lastSnapshot=if(learning)engine.learn(updated.learningConfig(),fix.receivedElapsedRealtime)else engine.arm(updated.config(),fix.receivedElapsedRealtime)
            session=updated;dao.updateSessionAndInsertEvent(updated,AlarmEventEntity(sessionId=updated.id,timestamp=activatedAt,type="GPS_MONITORING_ACTIVATED",detail="SOURCE=${source.name};ANCHOR_COORDINATE_PRESERVED=true"));active=updated
            clearPositionDegraded()
            host.notify("Anchor position monitoring started","The first current accepted position arrived. Movement monitoring is now active; the saved anchor coordinate was not changed.",false)
        }
        val evidenceSource=headingEvidence.sourceId.takeIf{headingEvidence.trueDegrees!=null}
        if(active?.estimationEpoch?.let{it>0L}==true&&evidenceSource!=null&&active.headingEvidenceSourceId!=evidenceSource){
            val sourceChanged=active.headingEvidenceSourceId!=null
            val updated=active.copy(headingEvidenceSourceId=evidenceSource,headingEvidenceEpoch=active.headingEvidenceEpoch+if(sourceChanged)1 else 0)
            if(sourceChanged){samples.replaceAll{it.copy(headingTrueDegrees=null,headingSampleSequence=null,headingSource=HeadingSource.NONE,headingQuality=HeadingQuality.UNAVAILABLE)};lastEstimateAt=0L;lastEstimateSampleCount=0;dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=wallClock.currentTimeMillis(),type="ANCHOR_HEADING_EVIDENCE_SOURCE_CHANGED",detail="from=${active.headingEvidenceSourceId};to=$evidenceSource;epoch=${updated.headingEvidenceEpoch};PRIOR_HEADING_EXCLUDED"))}
            session=updated;dao.updateSession(updated);active=updated
        }
        if(active!=null&&!active.paused){
            updateHeadingEvidenceTransition(active,headingEvidence)
            val snapshot=withPositionQuality(engine.onFix(fix,fix.receivedElapsedRealtime),fix.receivedElapsedRealtime)
            if(snapshot.maxDistanceMeters>active.maxDistanceMeters){val updated=active.copy(maxDistanceMeters=snapshot.maxDistanceMeters);session=updated;dao.updateSession(updated)}
            val pointTime=wallClock.currentTimeMillis()-(monotonicClock.elapsedRealtime()-fix.receivedElapsedRealtime).coerceAtLeast(0L)
            if(pointTime-lastTrack>=900L||accepted.wasQuarantined){
                lastTrack=pointTime
                dao.insertPoint(TrackPointEntity(sessionId=active.id,timestamp=pointTime,latitude=fix.latitude,longitude=fix.longitude,distanceFromAnchor=snapshot.distanceMeters?:0.0,sog=fix.sogKnots,cog=fix.cogTrueDegrees,heading=fix.headingTrueDegrees,hdop=fix.hdop,windDirectionTrue=fix.windDirectionTrueDegrees,windSpeedKnots=fix.windSpeedKnots,apparentWindAngle=fix.apparentWindAngleDegrees,trueWindAngle=fix.trueWindAngleDegrees,trueWindSpeedKnots=fix.trueWindSpeedKnots,apparentWindSpeedKnots=fix.apparentWindSpeedKnots,headingMeasured=fix.headingTrueDegrees!=null,headingSampleSequence=fix.headingSampleSequence,windSampleSequence=fix.windSampleSequence,positionSource=source.name,positionProvider=fix.positionProvider.name,horizontalAccuracyMeters=fix.horizontalAccuracyMeters,fixTrust=accepted.trust.name,wasQuarantined=accepted.wasQuarantined,quarantineReason=accepted.reason,headingSource=fix.headingSource.name,headingQuality=fix.headingQuality.name,headingEpoch=fix.headingEpoch))
                if(active.estimationEpoch>0L){
                    samples+=BackdownCenterEstimator.Sample(latitude=fix.latitude,longitude=fix.longitude,timestamp=pointTime,hdop=fix.hdop,horizontalAccuracyMeters=fix.horizontalAccuracyMeters,positionProvider=fix.positionProvider,fixTrust=accepted.trust,headingTrueDegrees=fix.headingTrueDegrees,cogTrueDegrees=fix.cogTrueDegrees,sogKnots=fix.sogKnots,windDirectionTrueDegrees=fix.windDirectionTrueDegrees,windSpeedKnots=fix.windSpeedKnots,apparentWindAngleDegrees=fix.apparentWindAngleDegrees,trueWindAngleDegrees=fix.trueWindAngleDegrees,trueWindSpeedKnots=fix.trueWindSpeedKnots,apparentWindSpeedKnots=fix.apparentWindSpeedKnots,headingSampleSequence=fix.headingSampleSequence,windSampleSequence=fix.windSampleSequence,headingSource=fix.headingSource,headingQuality=fix.headingQuality)
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
        val now=wallClock.currentTimeMillis();val updated=current.copy(anchorLatitude=current.provisionalAnchorLatitude,anchorLongitude=current.provisionalAnchorLongitude,centerStatus=AnchorCenterStatus.RESOLVED.name,centerResolvedAt=now,centerConfidence=Confidence.HIGH.name,centerSource=AnchorCenterSource.ESTIMATED_USER_ACCEPTED.name,anchorPositionMode=AnchorPositionMode.ESTIMATE.name,candidateId=null,candidateCreatedAt=null,candidateDecision=CandidateDecision.NONE.name,candidateNotificationShown=false,provisionalAnchorLatitude=null,provisionalAnchorLongitude=null,provisionalRadiusMeters=null,adoptedCenterEpoch=current.adoptedCenterEpoch+1,alarmSnoozedUntil=null)
        session=updated;dao.updateSessionAndInsertEvent(updated,AlarmEventEntity(sessionId=updated.id,timestamp=now,type="ANCHOR_CENTER_ACCEPTED_BY_USER",detail="candidate=$candidateId;radius=${updated.alarmRadiusMeters};adoptedEpoch=${updated.adoptedCenterEpoch}"));engine=alarmEngine(preferences.settings.first())
        if(updated.paused){
            resources.release(RuntimeOwner.ANCHOR_WATCH);nmeaRuntime.releaseIfUnowned();engine.stop();lastSnapshot=AlarmSnapshot(AlarmState.STOPPED);alarmUi.clear()
        }else{
            setResources(updated,preferences.settings.first());lastSnapshot=engine.arm(updated.config(),monotonicClock.elapsedRealtime());alarmUi.publish(lastSnapshot!!)
        }
        host.silence();host.refresh()
    }

    suspend fun keepCurrentCenter(sessionId:Long,candidateId:Long){
        val current=session
        if(current==null||current.id!=sessionId||current.candidateId!=candidateId||current.candidateDecision!=CandidateDecision.AVAILABLE.name){logEvent("ANCHOR_CENTER_KEEP_CURRENT_REJECTED","STALE_CANDIDATE");return}
        if(lastSnapshot?.state in setOf(AlarmState.WARNING,AlarmState.ALARM,AlarmState.ACKNOWLEDGED)){host.notify("Centre decision not applied","Return inside the warning boundary or pause the watch first.",true);return}
        val now=wallClock.currentTimeMillis();val updated=current.copy(centerStatus=AnchorCenterStatus.RESOLVED.name,centerResolvedAt=now,centerConfidence=Confidence.LOW.name,centerSource=AnchorCenterSource.CURRENT_POSITION.name,anchorPositionMode=AnchorPositionMode.ESTIMATE.name,candidateId=null,candidateCreatedAt=null,candidateDecision=CandidateDecision.NONE.name,candidateNotificationShown=false,provisionalAnchorLatitude=null,provisionalAnchorLongitude=null,provisionalRadiusMeters=null)
        session=updated;dao.updateSessionAndInsertEvent(updated,AlarmEventEntity(sessionId=updated.id,timestamp=now,type="ANCHOR_CENTER_CURRENT_KEPT",detail="candidate=$candidateId;estimator=continues"));engine=alarmEngine(preferences.settings.first())
        if(updated.paused){
            resources.release(RuntimeOwner.ANCHOR_WATCH);nmeaRuntime.releaseIfUnowned();engine.stop();lastSnapshot=AlarmSnapshot(AlarmState.STOPPED);alarmUi.clear()
        }else{
            setResources(updated,preferences.settings.first());lastSnapshot=engine.arm(updated.config(),monotonicClock.elapsedRealtime())
        }
        host.refresh()
    }

    suspend fun applyRecalculatedCentre(sessionId:Long,expectedCurrentLatitude:Double,expectedCurrentLongitude:Double,latitude:Double,longitude:Double,uncertaintyMeters:Double,trackDiameterMeters:Double,fitRadiusMeters:Double?,shiftMeters:Double){
        val current=session
        if(current==null||current.id!=sessionId||!latitude.isFinite()||!longitude.isFinite()||latitude !in -90.0..90.0||longitude !in -180.0..180.0){logEvent("ANCHOR_CENTRE_RECALCULATION_APPLY_REJECTED","STALE_OR_INVALID_RESULT");return}
        if(!expectedCurrentLatitude.isFinite()||!expectedCurrentLongitude.isFinite()||AnchorGeometry.distanceMeters(current.anchorLatitude,current.anchorLongitude,expectedCurrentLatitude,expectedCurrentLongitude)>1.0){logEvent("ANCHOR_CENTRE_RECALCULATION_APPLY_REJECTED","CURRENT_CENTRE_CHANGED_SINCE_ANALYSIS");host.notify("Recalculated centre not applied","The current anchor centre changed after this analysis. Run the track analysis again.",true);return}
        if(!AnchorCentreApplyPolicy.mayApply(lastSnapshot?.state)){host.notify("Recalculated centre not applied","Return inside the safe area or pause the watch before changing the anchor centre.",true);return}
        val now=wallClock.currentTimeMillis()
        val updated=current.copy(anchorLatitude=latitude,anchorLongitude=longitude,centerStatus=AnchorCenterStatus.RESOLVED.name,centerResolvedAt=now,centerConfidence=Confidence.HIGH.name,centerSource=AnchorCenterSource.ESTIMATED_USER_ACCEPTED.name,provisionalAnchorLatitude=null,provisionalAnchorLongitude=null,provisionalRadiusMeters=null,candidateId=null,candidateCreatedAt=null,candidateDecision=CandidateDecision.NONE.name,candidateNotificationShown=false,adoptedCenterEpoch=current.adoptedCenterEpoch+1,alarmSnoozedUntil=null)
        session=updated
        dao.updateSessionAndInsertEvent(updated,AlarmEventEntity(sessionId=updated.id,timestamp=now,type="ANCHOR_CENTRE_RECALCULATED_APPLIED",detail="oldLat=${current.anchorLatitude};oldLon=${current.anchorLongitude};newLat=$latitude;newLon=$longitude;shiftMeters=$shiftMeters;uncertainty=$uncertaintyMeters;trackDiameter=$trackDiameterMeters;fitRadius=$fitRadiusMeters;radialObservable=true"))
        val settings=preferences.settings.first();engine=alarmEngine(settings)
        if(updated.paused){engine.stop();lastSnapshot=AlarmSnapshot(AlarmState.STOPPED);alarmUi.clear()}
        else{lastSnapshot=engine.arm(updated.config(),monotonicClock.elapsedRealtime());acceptedPosition.state.value.acceptedFix?.takeIf{it.valid}?.let{fix->lastSnapshot=engine.onFix(fix,fix.receivedElapsedRealtime)};lastSnapshot?.let(alarmUi::publish)}
        host.silence();host.refresh()
    }

    suspend fun continueEstimating(sessionId:Long,candidateId:Long){
        val current=session
        if(current==null||current.id!=sessionId||current.candidateId!=candidateId||current.candidateDecision!=CandidateDecision.AVAILABLE.name){logEvent("ANCHOR_CENTER_CONTINUE_REJECTED","STALE_CANDIDATE");return}
        val latest=estimator.provisionalEstimate(samples,current.expectedSwingRadiusMeters)
        estimationBaseline=AnchorCentreEvidenceBaseline(latest?.sampleCount?:current.centerSampleCount,latest?.angularCoverageDegrees?:current.candidateAngularCoverageDegrees?:0.0,latest?.trackDiameterMeters?:0.0,latest?.swingReversalCount?:current.candidateSwingReversalCount)
        val updated=current.copy(centerStatus=if(current.centerStatus==AnchorCenterStatus.RESOLVED.name)AnchorCenterStatus.RESOLVED.name else AnchorCenterStatus.LEARNING.name,candidateId=null,candidateCreatedAt=null,candidateDecision=CandidateDecision.NONE.name,candidateNotificationShown=false)
        session=updated;dao.updateSession(updated);dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=wallClock.currentTimeMillis(),type="ANCHOR_CENTER_ESTIMATION_CONTINUED",detail="previousCandidate=$candidateId;${encodeBaseline(requireNotNull(estimationBaseline))}"));host.refresh()
    }

    suspend fun resetCentreAnalysis(sessionId:Long){
        val current=session
        if(current==null||current.id!=sessionId||current.estimationEpoch<=0L){logEvent("ANCHOR_CENTER_ANALYSIS_RESET_REJECTED","NO_GEOMETRY_SESSION");return}
        val now=wallClock.currentTimeMillis()
        val updated=current.copy(
            centerStatus=if(current.centerStatus==AnchorCenterStatus.RESOLVED.name)AnchorCenterStatus.RESOLVED.name else AnchorCenterStatus.LEARNING.name,
            centerConfidence=Confidence.LOW.name,centerSampleCount=0,candidateId=null,candidateCreatedAt=null,candidateDecision=CandidateDecision.NONE.name,candidateNotificationShown=false,
            provisionalAnchorLatitude=null,provisionalAnchorLongitude=null,provisionalRadiusMeters=null,candidateRmsErrorMeters=null,candidateAngularCoverageDegrees=null,candidateAngularSectorCount=0,candidateSwingReversalCount=0,candidateTemporalFitConsistent=false,candidateEffectiveDurationMillis=0,candidateDirectionEvidenceConsistent=false,
            candidateTrackDiameterMeters=0.0,candidateFittedRadiusMeters=null,candidateMaximumRodeMeters=0.0,candidateGpsMarginMeters=0.0,candidateRadialObservable=false,candidateObservabilityReason=com.yokuli.anchorwatch.domain.anchor.AnchorCentreObservabilityReason.NO_USABLE_CIRCLE_FIT.name,
            estimationEpoch=current.estimationEpoch+1,estimationEpochStartedAt=now,latestEstimateEpoch=current.latestEstimateEpoch+1,
        )
        samples.clear();driftDetector.reset();estimationBaseline=null;lastEstimateAt=0;lastEstimateSampleCount=0
        session=updated;dao.updateSessionAndInsertEvent(updated,AlarmEventEntity(sessionId=updated.id,timestamp=now,type="ANCHOR_CENTER_ANALYSIS_RESET",detail="epoch=${updated.estimationEpoch};adoptedCentreUnchanged=true"));host.notify("Centre analysis restarted","The adopted safety anchor and alarm boundary were preserved. Only estimator evidence starts a new epoch.",false);host.refresh()
    }

    /** Legacy command compatibility only. Heading evidence is automatic and
     * cannot be enabled/disabled by a session option. Actual accepted source
     * changes create epochs in [onAcceptedPosition]. */

    suspend fun snooze(){
        val current=session?:return;val audibleState=lastSnapshot?.state
        if(current.paused||audibleState !in setOf(AlarmState.WARNING,AlarmState.ALARM,AlarmState.ACKNOWLEDGED))return
        val minutes=preferences.settings.first().alarmSnoozeMinutes;val until=AlarmReminderPolicy.snoozeUntil(wallClock.currentTimeMillis(),minutes);val updated=current.copy(alarmSnoozedUntil=until);dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=wallClock.currentTimeMillis(),type="ALARM_SNOOZED",detail="${minutes}m"));session=updated;dao.updateSession(updated);lastSnapshot=engine.acknowledge();alarmUi.publish(lastSnapshot!!);host.silence();host.refresh()
    }

    suspend fun pause(){
        val current=session?:return;if(current.paused)return
        val updated=current.copy(paused=true,alarmSnoozedUntil=null,monitoringPhase=AnchorMonitoringPhase.PAUSED.name);session=updated;dao.updateSession(updated);dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=wallClock.currentTimeMillis(),type="SESSION_PAUSED"));if(currentGpsSource==GpsDataSource.DEMO)demoLocation.pause();resources.release(RuntimeOwner.ANCHOR_WATCH);nmeaRuntime.releaseIfUnowned();engine.stop();lastSnapshot=AlarmSnapshot(AlarmState.STOPPED);alarmUi.clear();lastReportedAlarm=null;nmeaLossAnnounced=false;clearPositionDegraded();host.silence();host.cancelUrgentNotification();host.refresh();host.releaseIfIdle()
    }

    /**
     * Rebinds a paused session to a verified live source without replacing its
     * anchor centre, range, estimator evidence or track. A source handover is
     * deliberately make-before-break: the Room row and accepted-position gate
     * are changed only after a fresh fix from the requested source exists.
     */
    suspend fun switchPausedPositionSource(requested:GpsDataSource){
        val current=session?:dao.active()?:run{
            host.notify("GPS source not changed","There is no open anchor session.",true)
            return
        }
        session=current
        if(!current.paused){
            host.notify("GPS source not changed","Pause the anchor watch before changing its position source.",true)
            return
        }
        val previous=runCatching{GpsDataSource.valueOf(current.positionSource)}.getOrDefault(currentGpsSource)
        if(previous==requested){
            host.notify("GPS source unchanged","This paused session is already using ${requested.name} GPS.",false)
            return
        }
        if(previous==GpsDataSource.DEMO||requested==GpsDataSource.DEMO){
            host.notify("GPS source not changed","Demo sessions cannot hand over to or from a live GPS source. Lift anchor before changing Demo mode.",true)
            return
        }
        var settings=preferences.settings.first()
        if(requested==GpsDataSource.SYSTEM&&GpsSourceSafety.blocksSystemGps(settings.mockEnabled,mockGps.status.value.state)){
            host.notify("GPS source not changed","Disable the global NMEA GPS proxy before switching this anchor session to System GPS.",true)
            return
        }
        val maximumAge=settings.gpsLossSeconds*1_000L
        var temporarySystemClaim=false
        val fix=try{
            when(requested){
                GpsDataSource.NMEA->{
                    nmeaRuntime.ensureConnected(settings.profile)
                    NmeaPositionAwaiter.awaitUsable(navigation.connectionState,navigation.fix,navigation.connectionStartedElapsed,10_000L,maximumAge,monotonicClock::elapsedRealtime)
                }
                GpsDataSource.SYSTEM->{
                    temporarySystemClaim=true
                    resources.set(
                        RuntimeOwner.ANCHOR_WATCH,
                        RuntimeRequirement(needsSystemLocation=true,needsWakeLock=true,needsPhoneMotion=true),
                    )
                    if(!host.enableSystemGps())null else withTimeoutOrNull(10_000){
                        systemLocation.fix.filterNotNull().filter{candidate->
                            candidate.valid&&candidate.positionProvider==PositionProvider.ANDROID_GNSS&&
                                (candidate.horizontalAccuracyMeters?:Double.POSITIVE_INFINITY)<=30.0&&
                                monotonicClock.elapsedRealtime()-candidate.receivedElapsedRealtime in 0L until maximumAge
                        }.first()
                    }
                }
                GpsDataSource.DEMO->null
            }
        }finally{
            if(temporarySystemClaim)resources.release(RuntimeOwner.ANCHOR_WATCH)
        }
        if(fix==null){
            host.notify(
                "GPS source not changed",
                if(requested==GpsDataSource.NMEA)"No fresh valid NMEA position was available. The paused session kept its previous source."
                else "No fresh precise System GNSS position was available. The paused session kept its previous source.",
                true,
            )
            host.releaseIfIdle()
            return
        }
        val previousAccepted=acceptedPosition.state.value.acceptedFix
        val separation=previousAccepted?.let{AnchorGeometry.distanceMeters(it.latitude,it.longitude,fix.latitude,fix.longitude)}
        val updated=current.copy(positionSource=requested.name,alarmSnoozedUntil=null)
        session=updated
        dao.updateSessionAndInsertEvent(updated,AlarmEventEntity(
            sessionId=updated.id,
            timestamp=wallClock.currentTimeMillis(),
            type="WATCH_GPS_SOURCE_CHANGED_WHILE_PAUSED",
            detail="${previous.name}_TO_${requested.name};HANDOVER_SEPARATION_METERS=${separation?:Double.NaN}",
        ))
        settings=settings.copy(gpsDataSource=requested)
        preferences.save(settings)
        currentGpsSource=requested
        lastProcessedAcceptedKey=null
        acceptedPosition.lockSource(updated.id,requested)
        acceptedPosition.submit(requested,fix,navigation.connectionGeneration().takeIf{requested==GpsDataSource.NMEA})
        nmeaLossAnnounced=false
        clearPositionDegraded()
        host.notify(
            "GPS source changed",
            "${if(requested==GpsDataSource.SYSTEM)"System GNSS" else "NMEA GPS"} is ready for this paused session. The anchor centre, range and track were preserved; review the position, then Resume.",
            false,
        )
        host.refresh()
        host.releaseIfIdle()
    }

    suspend fun resume(){
        val current=(session?:dao.active())?:return
        if(!current.paused){session=current;host.refresh();return}
        val settings=preferences.settings.first();currentGpsSource=runCatching{GpsDataSource.valueOf(current.positionSource)}.getOrDefault(settings.gpsDataSource);setResources(current.copy(paused=false),settings);val lossMillis=settings.gpsLossSeconds*1_000L
        val resumeWaitMillis=maxOf(15_000L,settings.profile.noDataTimeoutSeconds.coerceAtLeast(1)*1_000L).coerceAtMost(30_000L)
        val fix=when(currentGpsSource){
            GpsDataSource.NMEA->{
                // Resume is an explicit user confirmation that this paused
                // session may reopen its required NMEA endpoint. It is one of
                // the deliberately narrow actions allowed to clear the manual
                // disconnect latch; background restore/owners still may not.
                if(nmeaRuntime.userDisconnected()){
                    nmeaRuntime.clearUserDisconnect()
                    dao.insertEvent(AlarmEventEntity(sessionId=current.id,timestamp=wallClock.currentTimeMillis(),type="NMEA_RECONNECT_CONFIRMED_BY_RESUME"))
                }
                nmeaRuntime.ensureSafetyConnected(settings.profile)
                NmeaPositionAwaiter.awaitUsable(navigation.connectionState,navigation.fix,navigation.connectionStartedElapsed,resumeWaitMillis,lossMillis,monotonicClock::elapsedRealtime)
            }
            GpsDataSource.SYSTEM->{if(!host.enableSystemGps())null else systemLocation.fix.value?.takeIf{it.valid&&it.positionProvider==PositionProvider.ANDROID_GNSS&&(it.horizontalAccuracyMeters?:Double.POSITIVE_INFINITY)<=30.0&&monotonicClock.elapsedRealtime()-it.receivedElapsedRealtime in 0L until lossMillis}?:withTimeoutOrNull(resumeWaitMillis){systemLocation.fix.filterNotNull().filter{it.valid&&it.positionProvider==PositionProvider.ANDROID_GNSS&&(it.horizontalAccuracyMeters?:Double.POSITIVE_INFINITY)<=30.0&&monotonicClock.elapsedRealtime()-it.receivedElapsedRealtime in 0L until lossMillis}.first()}}
            GpsDataSource.DEMO->{if(!host.enableSystemGps())null else demoLocation.resume()?:demoLocation.start(current.learningReferenceLatitude?:current.anchorLatitude,current.learningReferenceLongitude?:current.anchorLongitude,runCatching{AnchorPlacementMode.valueOf(current.placementMode)}.getOrDefault(AnchorPlacementMode.CENTER_DROP),settings.demoScenario,current.alarmRadiusMeters,settings.demoSpeedMultiplier,seed=demoSeed(current))}
        }
        if(fix==null){resources.release(RuntimeOwner.ANCHOR_WATCH);nmeaRuntime.releaseIfUnowned();host.notify("Anchor watch remains paused","A fresh ${when(currentGpsSource){GpsDataSource.NMEA->"NMEA";GpsDataSource.SYSTEM->"System";GpsDataSource.DEMO->"Demo"}} GPS position did not arrive within ${resumeWaitMillis/1_000} seconds. The existing session, centre, range and track remain preserved; reconnect or switch the paused session source, then press Resume once.",true);session=current;host.releaseIfIdle();return}
        val resumedAt=monotonicClock.elapsedRealtime();val wallNow=wallClock.currentTimeMillis();val learning=current.centerStatus!=AnchorCenterStatus.RESOLVED.name;val updated=current.copy(paused=false,alarmSnoozedUntil=null,monitoringPhase=if(learning)AnchorMonitoringPhase.LEARNING.name else AnchorMonitoringPhase.ARMED.name,monitoringActivatedAt=current.monitoringActivatedAt?:wallNow);session=updated;dao.updateSession(updated);setResources(updated,settings);lastProcessedAcceptedKey=null;acceptedPosition.lockSource(updated.id,currentGpsSource);clearPositionDegraded();engine=alarmEngine(settings);lastSnapshot=if(learning)engine.learn(updated.learningConfig(),resumedAt)else engine.arm(updated.config(),resumedAt);submitRawFix(fix,currentGpsSource);dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=wallNow,type="SESSION_RESUMED"));host.notify("Anchor watch resumed","The existing anchor centre, track and alarm range were preserved.",false)
    }

    suspend fun lift(){
        val current=session?:dao.active()?:return;val now=wallClock.currentTimeMillis();dao.insertEvent(AlarmEventEntity(sessionId=current.id,timestamp=now,type="ANCHOR_LIFTED"));dao.updateSession(current.copy(active=false,paused=false,endedAt=now,alarmSnoozedUntil=null,monitoringPhase=AnchorMonitoringPhase.ENDED.name));if(currentGpsSource==GpsDataSource.DEMO)demoLocation.stop();demoSonar=null;resources.release(RuntimeOwner.ANCHOR_WATCH);nmeaRuntime.releaseIfUnowned();acceptedPosition.unlockSource(current.id);session=null;samples.clear();driftDetector.reset();clearPositionDegraded();engine.stop();lastSnapshot=null;alarmUi.clear();lastReportedAlarm=null;nmeaLossAnnounced=false;host.silence();host.cancelUrgentNotification();host.refresh();host.releaseIfIdle()
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
        val rangeChangedEvent=AlarmEventEntity(sessionId=updated.id,timestamp=wallClock.currentTimeMillis(),type="ALARM_RANGE_CHANGED",detail="${current.alarmRadiusMeters.toInt()}m_TO_${alarm.toInt()}m")
        // The visible radius and its audit event are one safety decision. Commit
        // them atomically so observers and support bundles can never see a new
        // boundary without the event that explains who changed it and when.
        dao.updateSessionAndInsertEvent(updated,rangeChangedEvent);session=updated;lastReportedAlarm=preservedAlarmType
        if(!updated.paused){lastSnapshot=engine.updateConfig(if(updated.centerStatus!=AnchorCenterStatus.RESOLVED.name)updated.learningConfig()else updated.config(),preservedAlarmType);if(preservedAlarmType==null||preservedAlarmType==AlarmType.ANCHOR_RADIUS_EXCEEDED)freshAcceptedFix?.let{fix->lastSnapshot=engine.onFix(fix,resetAt)};updateAlarm(lastSnapshot!!)}
        if(hadRadiusAlarm&&!dangerStillRemains)dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=wallClock.currentTimeMillis(),type="ALARM_CLEARED_BY_RANGE_CHANGE",detail="${alarm.toInt()}m"));host.notify("Anchor range updated","Alarm radius is now ${alarm.toInt()} m for this session.",false);host.refresh();host.releaseIfIdle()
    }

    suspend fun watchdog(){
        val now=monotonicClock.elapsedRealtime();if(session?.paused==false&&currentGpsSource==GpsDataSource.DEMO)demoLocation.tick(now)?.let{submitRawFix(it,GpsDataSource.DEMO)};session?.takeIf{!it.paused&&it.monitoringPhase!=AnchorMonitoringPhase.WAITING_FOR_GPS.name}?.let{updateAlarm(withPositionQuality(engine.tick(now),now))};host.refresh()
    }

    suspend fun logEvent(type:String,detail:String){session?.let{dao.insertEvent(AlarmEventEntity(sessionId=it.id,timestamp=wallClock.currentTimeMillis(),type=type,detail=detail))}}

    private suspend fun updateHeadingEvidenceTransition(active:AnchorSessionEntity,evidence:com.yokuli.anchorwatch.location.AnchorHeadingEvidence){
        if(active.estimationEpoch<=0L){headingEvidencePaused=null;headingEvidenceTransition=null;return}
        val phoneRelevant=evidence.source==HeadingSource.PHONE||evidence.reason.contains("PHONE")
        if(!phoneRelevant){headingEvidenceTransition=null;return}
        val paused=evidence.source!=HeadingSource.PHONE
        val now=monotonicClock.elapsedRealtime()
        if(headingEvidencePaused==null&&!paused){headingEvidencePaused=false;headingEvidenceTransition=null;return}
        if(headingEvidencePaused==paused){headingEvidenceTransition=null;return}
        if(headingEvidenceTransition!=paused){headingEvidenceTransition=paused;headingEvidenceTransitionSince=now;return}
        if(now-headingEvidenceTransitionSince<3_000L)return
        headingEvidencePaused=paused;headingEvidenceTransition=null
        dao.insertEvent(AlarmEventEntity(sessionId=active.id,timestamp=wallClock.currentTimeMillis(),type=if(paused)"ANCHOR_HEADING_EVIDENCE_PAUSED_UNSTABLE" else "ANCHOR_HEADING_EVIDENCE_RESUMED",detail=evidence.reason))
    }

    private suspend fun updateCandidate(estimate:BackdownAnchorEstimate?){
        val current=session?.takeIf{it.estimationEpoch>0L}?:return
        if(estimate==null)return
        val high=estimate.confidence==Confidence.HIGH&&estimate.radialObservable&&estimate.observabilityReason==com.yokuli.anchorwatch.domain.anchor.AnchorCentreObservabilityReason.OBSERVABLE
        if(high){val observation=CandidateCenterObservation(wallClock.currentTimeMillis(),estimate.latitude,estimate.longitude,estimate.uncertaintyRadiusMeters);when(driftDetector.add(observation)){CandidateDriftUpdate.IGNORED->Unit;CandidateDriftUpdate.RECORDED->dao.insertEvent(AlarmEventEntity(sessionId=current.id,timestamp=observation.timestamp,type="ESTIMATED_CENTER_HISTORY",detail=observation.encode()));CandidateDriftUpdate.POSSIBLE_DRAG->{dao.insertEvent(AlarmEventEntity(sessionId=current.id,timestamp=observation.timestamp,type="ESTIMATED_CENTER_HISTORY",detail=observation.encode()));dao.insertEvent(AlarmEventEntity(sessionId=current.id,timestamp=observation.timestamp,type="POSSIBLE_ANCHOR_DRAG_TREND",detail="Candidate centre moved persistently in one direction; formal radius alarm remains authoritative."));host.notify("Possible slow anchor movement","The estimated centre has moved persistently in one direction. This is an advisory only; check the vessel and the formal alarm boundary.",false)}}}
        val baselineGrowth=AnchorCentreEvidenceGrowthPolicy.hasMeaningfulGrowth(estimationBaseline,estimate,current.expectedSwingRadiusMeters)
        val existing=current.provisionalRadiusMeters;val first=current.candidateId==null;val qualityNotWorse=(current.candidateRmsErrorMeters==null||estimate.rmsErrorMeters==null||estimate.rmsErrorMeters<=current.candidateRmsErrorMeters*1.10)&&(current.candidateAngularCoverageDegrees==null||estimate.angularCoverageDegrees>=current.candidateAngularCoverageDegrees-10.0)&&(estimate.angularSectorCount>=current.candidateAngularSectorCount-1)&&(!current.candidateTemporalFitConsistent||estimate.temporalFitConsistent);val improved=(existing==null||estimate.uncertaintyRadiusMeters<=existing*.85)&&qualityNotWorse;val previewImproved=first&&(existing==null||estimate.uncertaintyRadiusMeters<=existing*.97)&&qualityNotWorse
        // Significance is relative to the centre that actually drives the alarm,
        // never the moving blue preview. Otherwise a 3 m change with ±5 m
        // uncertainty can be presented as a meaningful centre move.
        val shiftFromActiveCentre=AnchorGeometry.distanceMeters(current.anchorLatitude,current.anchorLongitude,estimate.latitude,estimate.longitude)
        val significantShift=AnchorCentreCandidatePolicy.isMeaningfulShift(shiftFromActiveCentre,estimate.uncertaintyRadiusMeters)
        // A READY estimate is always available for manual comparison/adoption.
        // Evidence growth and meaningful shift only control proactive prompts;
        // they must never hide a valid estimate from the skipper.
        val alarmBlocksRefinement=lastSnapshot?.state in setOf(AlarmState.WARNING,AlarmState.ALARM,AlarmState.ACKNOWLEDGED)
        val continuousDecision=AnchorContinuousEstimatePolicy.evaluate(high,alarmBlocksRefinement,driftDetector.refinementSuppressed(),current.centerStatus==AnchorCenterStatus.RESOLVED.name,current.candidateNotificationShown,significantShift,baselineGrowth&&improved)
        val candidateReady=continuousDecision.canCompareAndAdopt
        val newAvailability=candidateReady&&current.candidateDecision!=CandidateDecision.AVAILABLE.name
        val notifyReady=continuousDecision.notify
        if(!candidateReady&&!previewImproved){
            if(estimate.sampleCount>current.centerSampleCount){
                val progress=current.copy(
                    centerConfidence=estimate.confidence.name,
                    centerSampleCount=estimate.sampleCount,
                    candidateRmsErrorMeters=estimate.rmsErrorMeters,
                    candidateAngularCoverageDegrees=estimate.angularCoverageDegrees,
                    candidateAngularSectorCount=estimate.angularSectorCount,
                    candidateSwingReversalCount=estimate.swingReversalCount,
                    candidateTemporalFitConsistent=estimate.temporalFitConsistent,
                    candidateEffectiveDurationMillis=estimate.effectiveDurationMillis,
                    candidateDirectionEvidenceConsistent=estimate.directionEvidenceConsistent,
                    candidateTrackDiameterMeters=estimate.trackDiameterMeters,
                    candidateFittedRadiusMeters=estimate.fittedRadiusMeters,
                    candidateMaximumRodeMeters=estimate.maximumRodeMeters,
                    candidateGpsMarginMeters=estimate.gpsMarginMeters,
                    candidateRadialObservable=estimate.radialObservable,
                    candidateObservabilityReason=estimate.observabilityReason.name,
                    latestEstimateEpoch=current.latestEstimateEpoch+1,
                    candidateId=if(alarmBlocksRefinement||driftDetector.refinementSuppressed())null else current.candidateId,
                    candidateCreatedAt=if(alarmBlocksRefinement||driftDetector.refinementSuppressed())null else current.candidateCreatedAt,
                    candidateDecision=if(alarmBlocksRefinement||driftDetector.refinementSuppressed())CandidateDecision.NONE.name else current.candidateDecision,
                )
                session=progress
                dao.updateSession(progress)
            }
            return
        }
        val candidateId=current.candidateId?:wallClock.currentTimeMillis()
        val updated=current.copy(centerStatus=if(newAvailability&&current.centerStatus!=AnchorCenterStatus.RESOLVED.name)AnchorCenterStatus.CANDIDATE_READY.name else current.centerStatus,provisionalAnchorLatitude=estimate.latitude,provisionalAnchorLongitude=estimate.longitude,provisionalRadiusMeters=estimate.uncertaintyRadiusMeters,centerConfidence=estimate.confidence.name,centerSampleCount=estimate.sampleCount,candidateId=if(candidateReady)candidateId else current.candidateId,candidateCreatedAt=if(newAvailability)wallClock.currentTimeMillis() else current.candidateCreatedAt,candidateDecision=if(candidateReady)CandidateDecision.AVAILABLE.name else current.candidateDecision,candidateNotificationShown=if(notifyReady)true else current.candidateNotificationShown,candidateRmsErrorMeters=estimate.rmsErrorMeters,candidateAngularCoverageDegrees=estimate.angularCoverageDegrees,candidateAngularSectorCount=estimate.angularSectorCount,candidateSwingReversalCount=estimate.swingReversalCount,candidateTemporalFitConsistent=estimate.temporalFitConsistent,candidateEffectiveDurationMillis=estimate.effectiveDurationMillis,candidateDirectionEvidenceConsistent=estimate.directionEvidenceConsistent,candidateTrackDiameterMeters=estimate.trackDiameterMeters,candidateFittedRadiusMeters=estimate.fittedRadiusMeters,candidateMaximumRodeMeters=estimate.maximumRodeMeters,candidateGpsMarginMeters=estimate.gpsMarginMeters,candidateRadialObservable=estimate.radialObservable,candidateObservabilityReason=estimate.observabilityReason.name,latestEstimateEpoch=current.latestEstimateEpoch+1)
        session=updated;dao.updateSession(updated)
        if(notifyReady){estimationBaseline=null;dao.insertEvent(AlarmEventEntity(sessionId=updated.id,timestamp=wallClock.currentTimeMillis(),type="ESTIMATED_CENTER_HIGH",detail="candidate=$candidateId;uncertainty=${estimate.uncertaintyRadiusMeters.toInt()}m;rms=${estimate.rmsErrorMeters};coverage=${estimate.angularCoverageDegrees.toInt()};sectors=${estimate.angularSectorCount};reversals=${estimate.swingReversalCount};temporal=${estimate.temporalFitConsistent};maximumRode=${estimate.maximumRodeMeters};trackDiameter=${estimate.trackDiameterMeters};fitRadius=${estimate.fittedRadiusMeters};radiusRatio=${estimate.fittedRadiusRatio};gpsMargin=${estimate.gpsMarginMeters};radialObservable=${estimate.radialObservable};observabilityReason=${estimate.observabilityReason};nmeaPhysicalHeadingCount=${estimate.nmeaPhysicalHeadingEvidenceCount};phoneHeadingCount=${estimate.phoneHeadingEvidenceCount}"));host.notify("Estimated anchor centre ready","A high-confidence candidate is ready. The working alarm circle has not moved; review it in Watch.",false)}
    }

    private suspend fun updateAlarm(snapshot:AlarmSnapshot){
        val now=wallClock.currentTimeMillis();val previousSnapshot=lastSnapshot;var active=session;val settled=active
        val transitionedSnooze=AlarmReminderPolicy.snoozeAfterTransition(previousSnapshot,snapshot,settled?.alarmSnoozedUntil)
        if(settled!=null&&transitionedSnooze!=settled.alarmSnoozedUntil){val updated=settled.copy(alarmSnoozedUntil=transitionedSnooze);active=updated;session=updated;dao.updateSession(updated)}
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
    private fun setResources(active:AnchorSessionEntity,settings:AppSettings){if(active.paused){resources.release(RuntimeOwner.ANCHOR_WATCH);return};val source=runCatching{GpsDataSource.valueOf(active.positionSource)}.getOrDefault(settings.gpsDataSource);val mayNeedAutomaticPhoneHeading=active.estimationEpoch>0L;resources.set(RuntimeOwner.ANCHOR_WATCH,RuntimeRequirement(needsSystemLocation=source==GpsDataSource.SYSTEM||source==GpsDataSource.DEMO,needsNmeaTransport=source==GpsDataSource.NMEA,needsWakeLock=true,needsWifiLock=source==GpsDataSource.NMEA&&settings.keepWifiAwake,needsPhoneMotion=source==GpsDataSource.SYSTEM||mayNeedAutomaticPhoneHeading,needsPhoneHeading=mayNeedAutomaticPhoneHeading,needsPhonePressure=true))}
    private fun alarmEngine(settings:AppSettings)=AlarmEngine(settings.alarmPersistenceSeconds*1_000L,gpsLossMillis=settings.gpsLossSeconds*1_000L,clock=monotonicClock)
    private fun demoSeed(active:AnchorSessionEntity)=active.startedAt xor (active.id shl 17)
    private fun AnchorSessionEntity.config()=AnchorConfig(anchorLatitude,anchorLongitude,rodeLengthMeters,waterDepthMeters,bowRollerHeightMeters,gpsAntennaOffsetMeters,warningRadiusMeters,alarmRadiusMeters)
    private fun AnchorSessionEntity.learningConfig()=AnchorConfig(learningReferenceLatitude?:anchorLatitude,learningReferenceLongitude?:anchorLongitude,rodeLengthMeters,waterDepthMeters,bowRollerHeightMeters,gpsAntennaOffsetMeters,warningRadiusMeters,alarmRadiusMeters)
    private fun TrackPointEntity.toEstimatorSample(includeHeading:Boolean=true)=BackdownCenterEstimator.Sample(latitude=latitude,longitude=longitude,timestamp=timestamp,hdop=hdop,horizontalAccuracyMeters=horizontalAccuracyMeters,positionProvider=runCatching{PositionProvider.valueOf(positionProvider)}.getOrDefault(PositionProvider.UNKNOWN),fixTrust=runCatching{FixTrust.valueOf(fixTrust)}.getOrDefault(FixTrust.DEGRADED),headingTrueDegrees=heading.takeIf{includeHeading&&(headingSource==HeadingSource.NMEA_PHYSICAL.name||headingSource==HeadingSource.PHONE.name)},cogTrueDegrees=cog,sogKnots=sog,windDirectionTrueDegrees=windDirectionTrue,windSpeedKnots=windSpeedKnots,apparentWindAngleDegrees=apparentWindAngle,trueWindAngleDegrees=trueWindAngle,trueWindSpeedKnots=trueWindSpeedKnots,apparentWindSpeedKnots=apparentWindSpeedKnots,headingSampleSequence=headingSampleSequence.takeIf{includeHeading},windSampleSequence=windSampleSequence,headingSource=if(includeHeading)runCatching{HeadingSource.valueOf(headingSource)}.getOrDefault(HeadingSource.NONE)else HeadingSource.NONE,headingQuality=if(includeHeading)runCatching{HeadingQuality.valueOf(headingQuality)}.getOrDefault(HeadingQuality.UNAVAILABLE)else HeadingQuality.UNAVAILABLE)
    private fun encodeBaseline(value:AnchorCentreEvidenceBaseline)="baselineSamples=${value.samples};baselineCoverage=${value.coverageDegrees};baselineTrackDiameter=${value.trackDiameterMeters};baselineReversals=${value.reversals}"
    private fun decodeBaseline(detail:String):AnchorCentreEvidenceBaseline?{val fields=detail.split(';').mapNotNull{token->token.substringBefore('=',"").takeIf{it.isNotBlank()}?.let{it to token.substringAfter('=',"")}}.toMap();return AnchorCentreEvidenceBaseline(fields["baselineSamples"]?.toIntOrNull()?:return null,fields["baselineCoverage"]?.toDoubleOrNull()?:return null,fields["baselineTrackDiameter"]?.toDoubleOrNull()?:return null,fields["baselineReversals"]?.toIntOrNull()?:return null)}
}
