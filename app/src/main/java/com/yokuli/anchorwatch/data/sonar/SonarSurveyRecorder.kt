package com.yokuli.anchorwatch.data.sonar

import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.data.database.DepthSampleEntity
import com.yokuli.anchorwatch.data.database.GridCoordinate
import com.yokuli.anchorwatch.data.database.SonarDao
import com.yokuli.anchorwatch.data.database.SonarSurveyEntity
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.data.tide.LinzTideRepository
import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import com.yokuli.anchorwatch.domain.model.FixTrust
import com.yokuli.anchorwatch.domain.model.PositionProvider
import com.yokuli.anchorwatch.domain.sonar.DepthCandidate
import com.yokuli.anchorwatch.domain.sonar.DepthDisposition
import com.yokuli.anchorwatch.domain.sonar.DepthIntegrityFilter
import com.yokuli.anchorwatch.domain.sonar.DepthObservation
import com.yokuli.anchorwatch.domain.sonar.DepthProvenance
import com.yokuli.anchorwatch.domain.sonar.DepthReference
import com.yokuli.anchorwatch.domain.sonar.TideMode
import com.yokuli.anchorwatch.domain.sonar.SonarGrid
import com.yokuli.anchorwatch.domain.tide.TideCorrectionResult
import com.yokuli.anchorwatch.domain.tide.TideCorrectionStatus
import com.yokuli.anchorwatch.location.AcceptedPositionRepository
import com.yokuli.anchorwatch.runtime.MonotonicClock
import com.yokuli.anchorwatch.runtime.WallClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.DateFormat
import java.util.Date
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.floor

data class SonarRecorderStatus(
    val activeSurvey:SonarSurveyEntity?=null,
    val lastRawDepthMeters:Double?=null,
    val lastNmeaOffsetMeters:Double?=null,
    val lastUserOffsetMeters:Double?=null,
    val lastMeasuredDepthMeters:Double?=null,
    val lastDepthMeters:Double?=null,
    val lastDepthReference:DepthReference?=null,
    val lastSentenceType:String?=null,
    val lastDepthReceivedElapsedRealtime:Long?=null,
    val lastNmeaPositionReceivedElapsedRealtime:Long?=null,
    val lastDepthIsDemo:Boolean=false,
    val lastDepthIsChartDatum:Boolean=false,
    val lastDisposition:DepthDisposition?=null,
    val lastTideCorrection:TideCorrectionResult?=null,
    val gridDiagnostics:SonarGridUpdateDiagnostics=SonarGridUpdateDiagnostics(),
    val message:String="Not recording",
){
    fun hasFreshDepth(nowElapsed:Long,maxAgeMillis:Long=2_000L)=lastDepthReceivedElapsedRealtime?.let{nowElapsed-it in 0..maxAgeMillis}==true
    fun hasFreshRealDepth(nowElapsed:Long,maxAgeMillis:Long=2_000L)=!lastDepthIsDemo&&hasFreshDepth(nowElapsed,maxAgeMillis)
    fun hasFreshNmeaPosition(nowElapsed:Long,maxAgeMillis:Long=2_000L)=lastNmeaPositionReceivedElapsedRealtime?.let{nowElapsed-it in 0..maxAgeMillis}==true
}

/** Real depth is paired only with same-stream NMEA GPS; Demo uses Demo GPS. */
@Singleton
class SonarSurveyRecorder @Inject constructor(
    private val navigation:NavigationRepository,
    private val acceptedPosition:AcceptedPositionRepository,
    private val nmeaPosition:NmeaSonarPositionRepository,
    private val dao:SonarDao,
    private val settings:SettingsRepository,
    private val gridUpdater:SonarIncrementalGridUpdater,
    private val tideRepository:LinzTideRepository,
    private val monotonicClock:MonotonicClock,
    private val wallClock:WallClock,
){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);private val mutex=Mutex();private val integrity=DepthIntegrityFilter()
    private val _status=MutableStateFlow(SonarRecorderStatus());val status=_status.asStateFlow();private var lastRecorded:DepthCandidate?=null
    private val attemptedTideYears=java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<String,Int>>()

    init{
        scope.launch{mutex.withLock{dao.active()?.let{_status.value=SonarRecorderStatus(activeSurvey=it,message="Recording restored")}}}
        scope.launch{nmeaPosition.state.collect{position->mutex.withLock{
            _status.value=_status.value.copy(lastNmeaPositionReceivedElapsedRealtime=position.acceptedFix?.receivedElapsedRealtime)
        }}}
        scope.launch{navigation.depthObservations.collect{onDepth(it,allowDemo=false)}}
        scope.launch{_status.value=_status.value.copy(gridDiagnostics=_status.value.gridDiagnostics.copy(rebuilding=true),message="Rebuilding sonar map…");val diagnostics=gridUpdater.rebuildMissing();_status.value=_status.value.copy(gridDiagnostics=diagnostics,message=if(_status.value.activeSurvey==null)"Not recording" else "Recording restored")}
    }

    suspend fun start(name:String,tideMode:TideMode,manualTideOffsetMeters:Double,sounderOffsetMeters:Double,tideStationId:String?=null):Long{
        val position=acceptedPosition.state.value.acceptedFix?.takeIf{it.positionProvider==PositionProvider.DEMO}?:nmeaPosition.state.value.acceptedFix
        if(tideMode==TideMode.AUTO_PREDICTED)tideRepository.refreshStationCatalog()
        val stationSelection=if(tideMode==TideMode.AUTO_PREDICTED&&position!=null){
            val selected=tideRepository.station(tideStationId)
            if(selected!=null)selected to AnchorGeometry.distanceMeters(position.latitude,position.longitude,selected.latitude,selected.longitude)
            else tideRepository.nearestStation(position.latitude,position.longitude)
        }else null
        stationSelection?.first?.let{station->val instant=Instant.ofEpochMilli(wallClock.currentTimeMillis());attemptedTideYears+=station.id to instant.atZone(ZoneId.of(station.zoneId)).year;tideRepository.ensure(station,instant)}
        return mutex.withLock{
        _status.value.activeSurvey?.let{return@withLock it.id};dao.active()?.let{existing->_status.value=_status.value.copy(activeSurvey=existing,message="Recording restored");return@withLock existing.id}
        integrity.reset();lastRecorded=null;val now=wallClock.currentTimeMillis();val entity=SonarSurveyEntity(name=name.trim().ifBlank{DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(now))},startedAt=now,tideMode=tideMode.name,manualTideOffsetMeters=manualTideOffsetMeters,configuredDepthReference=DepthReference.BELOW_SURFACE.name,sounderOffsetMeters=sounderOffsetMeters.coerceIn(-20.0,20.0),tideStationId=stationSelection?.first?.id,tideStationName=stationSelection?.first?.name,tideStationDistanceMeters=stationSelection?.second)
        val saved=entity.copy(id=dao.insertSurvey(entity));_status.value=_status.value.copy(activeSurvey=saved,message=if(tideMode==TideMode.AUTO_PREDICTED&&stationSelection==null)"Recording raw depth; no LINZ tide station available" else "Waiting for fresh depth and matching position");saved.id
        }
    }

    /**
     * Re-reads the persisted recorder state before runtime owners are restored.
     * This removes the process-start race between the recorder's background
     * initializer and the foreground service restore sequence.
     */
    suspend fun restoreActiveSurvey():SonarSurveyEntity?=mutex.withLock{
        val active=dao.active()
        _status.value=if(active==null)_status.value.copy(activeSurvey=null,message="Not recording")
        else _status.value.copy(activeSurvey=active,message="Recording restored")
        active
    }

    suspend fun stop()=mutex.withLock{val active=_status.value.activeSurvey?:dao.active()?:return@withLock;val now=wallClock.currentTimeMillis();dao.refreshSampleCount(active.id);dao.finish(active.id,now);_status.value=_status.value.copy(activeSurvey=null,message="Survey saved");integrity.reset();lastRecorded=null}
    suspend fun rename(surveyId:Long,name:String){if(name.isNotBlank())dao.rename(surveyId,name.trim())}
    suspend fun delete(surveyId:Long)=gridUpdater.deleteSurvey(surveyId)

    suspend fun rebuild(surveyId:Long)=mutex.withLock{
        val survey=dao.survey(surveyId)?:return@withLock;if(survey.active){_status.value=_status.value.copy(message="Stop the survey before rebuilding its map");return@withLock};val filter=DepthIntegrityFilter();var afterTimestamp=Long.MIN_VALUE;var afterId=Long.MIN_VALUE
        val previousCorrectedCoordinates=dao.correctedCellsForSurvey(surveyId).toSet()
        while(true){
            val page=dao.samplesPage(surveyId,afterTimestamp,afterId,1_000);if(page.isEmpty())break
            if(survey.tideMode==TideMode.AUTO_PREDICTED.name){tideRepository.station(survey.tideStationId)?.let{station->page.map{Instant.ofEpochMilli(it.timestamp)}.distinctBy{it.atZone(ZoneId.of(station.zoneId)).year}.forEach{instant->tideRepository.ensure(station,instant)}}}
            val rebuilt=mutableListOf<DepthSampleEntity>()
            page.forEach{sample->
                val measured=(sample.rawDepthMeters+(sample.nmeaOffsetMeters?:0.0)+survey.sounderOffsetMeters).coerceAtLeast(.01)
                val station=tideRepository.station(survey.tideStationId)
                val tide=if(survey.tideMode==TideMode.AUTO_PREDICTED.name&&station!=null)tideRepository.correctionAt(station,survey.tideStationDistanceMeters,Instant.ofEpochMilli(sample.timestamp))else null
                val normalized=when(survey.tideMode){TideMode.MANUAL.name->measured-survey.manualTideOffsetMeters;TideMode.AUTO_PREDICTED.name->tide?.tideHeightMetersAboveChartDatum?.let(measured::minus);else->null}
                val candidate=DepthCandidate(sample.latitude,sample.longitude,sample.sourceElapsedRealtime,sample.rawDepthMeters,normalized?:measured,sample.horizontalAccuracyMeters);val result=filter.evaluate(candidate)
                if(result.releasedElapsedTimestamps.isNotEmpty()){dao.releaseSlopeSamples(surveyId,result.releasedElapsedTimestamps);for(index in rebuilt.indices)if(rebuilt[index].sourceElapsedRealtime in result.releasedElapsedTimestamps)rebuilt[index]=rebuilt[index].copy(disposition=DepthDisposition.ACCEPTED_STEEP_SLOPE.name,usable=true,integrityReason="Released by coherent three-point slope")}
                rebuilt+=sample.copy(measuredDepthMeters=measured,normalizedDepthMeters=normalized,depthReference=DepthReference.BELOW_SURFACE.name,disposition=result.disposition.name,usable=result.usable,integrityReason=result.reason,positionCorrectionApplied=false,positionCorrectionMethod="NONE",tideHeightMetersApplied=when(survey.tideMode){TideMode.MANUAL.name->survey.manualTideOffsetMeters;TideMode.AUTO_PREDICTED.name->tide?.tideHeightMetersAboveChartDatum;else->null},tideCorrectionMode=survey.tideMode,tideStationId=tide?.stationId?:survey.tideStationId,tideStationName=tide?.stationName?:survey.tideStationName,tideStationDistanceMeters=tide?.stationDistanceMeters?:survey.tideStationDistanceMeters,tidePredictionYear=tide?.predictionYear,tideCorrectionMethod=tide?.method,tideSource=when{tide!=null->"LINZ_DAILY_PREDICTION";survey.tideMode==TideMode.MANUAL.name->"USER_MANUAL";else->null},tideSourceUpdatedAt=tide?.sourceUpdatedAt,tideCorrectionStatus=tide?.status?.name?:if(survey.tideMode==TideMode.OFF.name)"NOT_REQUESTED" else if(survey.tideMode==TideMode.MANUAL.name)"MANUAL" else TideCorrectionStatus.DATA_MISSING.name)
            }
            dao.updateSamples(rebuilt);val last=page.last();afterTimestamp=last.timestamp;afterId=last.id
        }
        dao.refreshSampleCount(surveyId);val diagnostics=gridUpdater.rebuildSurvey(surveyId,previousCorrectedCoordinates=previousCorrectedCoordinates);_status.value=_status.value.copy(gridDiagnostics=diagnostics,message="Survey grid rebuilt from raw soundings")
    }

    suspend fun submitDemo(observation:DepthObservation)=onDepth(observation,allowDemo=true)

    private suspend fun onDepth(observation:DepthObservation,allowDemo:Boolean)=mutex.withLock{
        val survey=_status.value.activeSurvey;val offset=survey?.sounderOffsetMeters?:settings.settings.first().sounderOffsetMeters;val provenance=DepthProvenance.from(observation,offset);val measured=provenance.finalDepthMeters;val manualNormalized=survey?.takeIf{it.tideMode==TideMode.MANUAL.name}?.let{measured-it.manualTideOffsetMeters}
        fun live(message:String,disposition:DepthDisposition?=null){_status.value=_status.value.copy(lastRawDepthMeters=provenance.rawDepthMeters,lastNmeaOffsetMeters=provenance.nmeaOffsetMeters,lastUserOffsetMeters=provenance.userOffsetMeters,lastMeasuredDepthMeters=provenance.finalDepthMeters,lastDepthMeters=manualNormalized?:measured,lastDepthReference=DepthReference.BELOW_SURFACE,lastSentenceType=observation.sentenceType.name,lastDepthReceivedElapsedRealtime=observation.receivedElapsedRealtime,lastDepthIsDemo=allowDemo,lastDepthIsChartDatum=manualNormalized!=null,lastDisposition=disposition,message=message)}
        if(survey==null){live(if(allowDemo)"Ready · live Demo sonar received" else "Ready · live NMEA depth received");return@withLock}
        val demoPosition=acceptedPosition.state.value
        val realPosition=nmeaPosition.state.value
        val fix=if(allowDemo)demoPosition.acceptedFix else realPosition.acceptedFix
        when(SonarPositionPairingPolicy.evaluate(allowDemo,fix,observation.receivedElapsedRealtime)){
            SonarPositionPairingDecision.POSITION_MISSING->{live(if(allowDemo)"Demo sonar is waiting for Demo GPS" else "Depth received; waiting for same-stream NMEA GPS");return@withLock}
            SonarPositionPairingDecision.WRONG_POSITION_PROVIDER->{live(if(allowDemo)"Demo sonar was not paired with Demo GPS" else "Real sonar rejected a non-NMEA GPS source");return@withLock}
            SonarPositionPairingDecision.POSITION_STALE->{val age=fix?.let{kotlin.math.abs(observation.receivedElapsedRealtime-it.receivedElapsedRealtime)};live("Depth not recorded: NMEA position is ${age?:"missing"} ms away",DepthDisposition.REJECTED_STALE_POSITION);return@withLock}
            SonarPositionPairingDecision.ALLOWED->Unit
        }
        requireNotNull(fix)
        val age=abs(observation.receivedElapsedRealtime-fix.receivedElapsedRealtime)
        val wallTimestamp=wallClock.currentTimeMillis()-(monotonicClock.elapsedRealtime()-observation.receivedElapsedRealtime).coerceAtLeast(0L)
        val station=tideRepository.station(survey.tideStationId)
        if(survey.tideMode==TideMode.AUTO_PREDICTED.name&&station!=null){val instant=Instant.ofEpochMilli(wallTimestamp);val key=station.id to instant.atZone(ZoneId.of(station.zoneId)).year;if(attemptedTideYears.add(key))scope.launch{tideRepository.ensure(station,instant)}}
        val tide=if(survey.tideMode==TideMode.AUTO_PREDICTED.name&&station!=null)tideRepository.correctionAt(station,survey.tideStationDistanceMeters,Instant.ofEpochMilli(wallTimestamp))else null
        val normalized=when(survey.tideMode){TideMode.MANUAL.name->manualNormalized;TideMode.AUTO_PREDICTED.name->tide?.takeIf{it.status==TideCorrectionStatus.AVAILABLE}?.tideHeightMetersAboveChartDatum?.let(measured::minus);else->null}
        val gridDepth=normalized?:measured;val candidate=DepthCandidate(fix.latitude,fix.longitude,observation.receivedElapsedRealtime,observation.rawDepthMeters,gridDepth,fix.horizontalAccuracyMeters)
        lastRecorded?.let{previous->if(candidate.timestamp-previous.timestamp<1_000L)return@withLock;if(AnchorGeometry.distanceMeters(previous.latitude,previous.longitude,candidate.latitude,candidate.longitude)<1.5&&abs(candidate.normalizedDepthMeters-previous.normalizedDepthMeters)<.2)return@withLock}
        val result=integrity.evaluate(candidate);val trust=(if(allowDemo)demoPosition.trust else realPosition.trust)?:FixTrust.DEGRADED;val touched=mutableSetOf<GridCoordinate>()
        if(result.releasedElapsedTimestamps.isNotEmpty()){val released=dao.samplesByElapsed(survey.id,result.releasedElapsedTimestamps);dao.releaseSlopeSamples(survey.id,result.releasedElapsedTimestamps);touched+=released.map{GridCoordinate(it.baseGridX,it.baseGridY)}}
        val projected=SonarGrid.project(fix.latitude,fix.longitude);val coordinate=GridCoordinate(floor(projected.first/5.0).toLong(),floor(projected.second/5.0).toLong());touched+=coordinate
        dao.insertSampleAndIncrement(DepthSampleEntity(surveyId=survey.id,timestamp=wallTimestamp,latitude=fix.latitude,longitude=fix.longitude,baseGridX=coordinate.baseGridX,baseGridY=coordinate.baseGridY,sourceElapsedRealtime=observation.receivedElapsedRealtime,rawDepthMeters=observation.rawDepthMeters,measuredDepthMeters=measured,normalizedDepthMeters=normalized,depthReference=DepthReference.BELOW_SURFACE.name,sentenceType=observation.sentenceType.name,nmeaOffsetMeters=observation.offsetMeters,horizontalAccuracyMeters=fix.horizontalAccuracyMeters,gpsSource=if(allowDemo)"DEMO" else "NMEA_SERVER",positionProvider=fix.positionProvider.name,hdop=fix.hdop,sogKnots=fix.sogKnots,fixTrust=trust.name,positionAgeMillis=age,disposition=result.disposition.name,usable=result.usable,integrityReason=result.reason,positionCorrectionApplied=false,positionCorrectionMethod="NONE",tideHeightMetersApplied=when(survey.tideMode){TideMode.MANUAL.name->survey.manualTideOffsetMeters;TideMode.AUTO_PREDICTED.name->tide?.tideHeightMetersAboveChartDatum;else->null},tideCorrectionMode=survey.tideMode,tideStationId=tide?.stationId?:survey.tideStationId,tideStationName=tide?.stationName?:survey.tideStationName,tideStationDistanceMeters=tide?.stationDistanceMeters?:survey.tideStationDistanceMeters,tidePredictionYear=tide?.predictionYear,tideCorrectionMethod=tide?.method,tideSource=when{tide!=null->"LINZ_DAILY_PREDICTION";survey.tideMode==TideMode.MANUAL.name->"USER_MANUAL";else->null},tideSourceUpdatedAt=tide?.sourceUpdatedAt,tideCorrectionStatus=tide?.status?.name?:if(survey.tideMode==TideMode.OFF.name)"NOT_REQUESTED" else if(survey.tideMode==TideMode.MANUAL.name)"MANUAL" else TideCorrectionStatus.DATA_MISSING.name))
        val diagnostics=gridUpdater.updateCells(survey.id,touched);lastRecorded=candidate;_status.value=_status.value.copy(activeSurvey=survey.copy(sampleCount=survey.sampleCount+1),lastRawDepthMeters=provenance.rawDepthMeters,lastNmeaOffsetMeters=provenance.nmeaOffsetMeters,lastUserOffsetMeters=provenance.userOffsetMeters,lastMeasuredDepthMeters=provenance.finalDepthMeters,lastDepthMeters=gridDepth,lastDepthReference=DepthReference.BELOW_SURFACE,lastSentenceType=observation.sentenceType.name,lastDepthReceivedElapsedRealtime=observation.receivedElapsedRealtime,lastDepthIsDemo=allowDemo,lastDepthIsChartDatum=normalized!=null,lastDisposition=result.disposition,lastTideCorrection=tide,gridDiagnostics=diagnostics,message=if(result.usable)if(survey.tideMode==TideMode.AUTO_PREDICTED.name&&normalized==null)"Raw depth recorded; predicted tide unavailable" else "Depth sample recorded" else result.reason?:"Depth quarantined")
    }
}
