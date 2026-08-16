package com.yokuli.anchorwatch.data.sonar

import android.os.SystemClock
import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.data.database.DepthSampleEntity
import com.yokuli.anchorwatch.data.database.GridCoordinate
import com.yokuli.anchorwatch.data.database.SonarDao
import com.yokuli.anchorwatch.data.database.SonarSurveyEntity
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import com.yokuli.anchorwatch.domain.model.FixTrust
import com.yokuli.anchorwatch.domain.model.PositionProvider
import com.yokuli.anchorwatch.domain.sonar.DepthCandidate
import com.yokuli.anchorwatch.domain.sonar.DepthDisposition
import com.yokuli.anchorwatch.domain.sonar.DepthIntegrityFilter
import com.yokuli.anchorwatch.domain.sonar.DepthObservation
import com.yokuli.anchorwatch.domain.sonar.DepthReference
import com.yokuli.anchorwatch.domain.sonar.TideMode
import com.yokuli.anchorwatch.domain.sonar.SonarGrid
import com.yokuli.anchorwatch.location.AcceptedPositionRepository
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.floor

data class SonarRecorderStatus(
    val activeSurvey:SonarSurveyEntity?=null,
    val lastRawDepthMeters:Double?=null,
    val lastDepthMeters:Double?=null,
    val lastDepthReference:DepthReference?=null,
    val lastSentenceType:String?=null,
    val lastDepthReceivedElapsedRealtime:Long?=null,
    val lastDepthIsDemo:Boolean=false,
    val lastDepthIsChartDatum:Boolean=false,
    val lastDisposition:DepthDisposition?=null,
    val gridDiagnostics:SonarGridUpdateDiagnostics=SonarGridUpdateDiagnostics(),
    val message:String="Not recording",
){
    fun hasFreshDepth(nowElapsed:Long,maxAgeMillis:Long=2_000L)=lastDepthReceivedElapsedRealtime?.let{nowElapsed-it in 0..maxAgeMillis}==true
    fun hasFreshRealDepth(nowElapsed:Long,maxAgeMillis:Long=2_000L)=!lastDepthIsDemo&&hasFreshDepth(nowElapsed,maxAgeMillis)
}

/** Pairs depth only with process-wide Accepted Position and updates local 5 m cells. */
@Singleton
class SonarSurveyRecorder @Inject constructor(
    private val navigation:NavigationRepository,
    private val acceptedPosition:AcceptedPositionRepository,
    private val dao:SonarDao,
    private val settings:SettingsRepository,
    private val gridUpdater:SonarIncrementalGridUpdater,
){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);private val mutex=Mutex();private val integrity=DepthIntegrityFilter()
    private val _status=MutableStateFlow(SonarRecorderStatus());val status=_status.asStateFlow();private var lastRecorded:DepthCandidate?=null

    init{
        scope.launch{mutex.withLock{dao.active()?.let{_status.value=SonarRecorderStatus(activeSurvey=it,message="Recording restored")}}}
        scope.launch{navigation.depthObservations.collect{onDepth(it,allowDemo=false)}}
        scope.launch{_status.value=_status.value.copy(gridDiagnostics=_status.value.gridDiagnostics.copy(rebuilding=true),message="Rebuilding sonar map…");val diagnostics=gridUpdater.rebuildMissing();_status.value=_status.value.copy(gridDiagnostics=diagnostics,message=if(_status.value.activeSurvey==null)"Not recording" else "Recording restored")}
    }

    suspend fun start(name:String,tideMode:TideMode,manualTideOffsetMeters:Double,sounderOffsetMeters:Double):Long=mutex.withLock{
        _status.value.activeSurvey?.let{return@withLock it.id};dao.active()?.let{existing->_status.value=_status.value.copy(activeSurvey=existing,message="Recording restored");return@withLock existing.id}
        integrity.reset();lastRecorded=null;val now=System.currentTimeMillis();val entity=SonarSurveyEntity(name=name.trim().ifBlank{DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(now))},startedAt=now,tideMode=tideMode.name,manualTideOffsetMeters=manualTideOffsetMeters,configuredDepthReference=DepthReference.BELOW_SURFACE.name,sounderOffsetMeters=sounderOffsetMeters.coerceIn(-20.0,20.0))
        val saved=entity.copy(id=dao.insertSurvey(entity));_status.value=_status.value.copy(activeSurvey=saved,message="Waiting for fresh depth and accepted position");saved.id
    }

    suspend fun stop()=mutex.withLock{val active=_status.value.activeSurvey?:dao.active()?:return@withLock;val now=System.currentTimeMillis();dao.refreshSampleCount(active.id);dao.finish(active.id,now);_status.value=_status.value.copy(activeSurvey=null,message="Survey saved");integrity.reset();lastRecorded=null}
    suspend fun rename(surveyId:Long,name:String){if(name.isNotBlank())dao.rename(surveyId,name.trim())}
    suspend fun delete(surveyId:Long)=gridUpdater.deleteSurvey(surveyId)

    suspend fun rebuild(surveyId:Long)=mutex.withLock{
        val survey=dao.survey(surveyId)?:return@withLock;if(survey.active){_status.value=_status.value.copy(message="Stop the survey before rebuilding its map");return@withLock};val filter=DepthIntegrityFilter();var afterTimestamp=Long.MIN_VALUE;var afterId=Long.MIN_VALUE
        while(true){
            val page=dao.samplesPage(surveyId,afterTimestamp,afterId,1_000);if(page.isEmpty())break
            val rebuilt=mutableListOf<DepthSampleEntity>()
            page.forEach{sample->
                val measured=(sample.rawDepthMeters+(sample.nmeaOffsetMeters?:0.0)+survey.sounderOffsetMeters).coerceAtLeast(.01);val normalized=if(survey.tideMode==TideMode.MANUAL.name)measured-survey.manualTideOffsetMeters else null;val candidate=DepthCandidate(sample.latitude,sample.longitude,sample.sourceElapsedRealtime,sample.rawDepthMeters,normalized?:measured,sample.horizontalAccuracyMeters);val result=filter.evaluate(candidate)
                if(result.releasedElapsedTimestamps.isNotEmpty()){dao.releaseSlopeSamples(surveyId,result.releasedElapsedTimestamps);for(index in rebuilt.indices)if(rebuilt[index].sourceElapsedRealtime in result.releasedElapsedTimestamps)rebuilt[index]=rebuilt[index].copy(disposition=DepthDisposition.ACCEPTED_STEEP_SLOPE.name,usable=true,integrityReason="Released by coherent three-point slope")}
                rebuilt+=sample.copy(measuredDepthMeters=measured,normalizedDepthMeters=normalized,depthReference=DepthReference.BELOW_SURFACE.name,disposition=result.disposition.name,usable=result.usable,integrityReason=result.reason,positionCorrectionApplied=false,positionCorrectionMethod="NONE")
            }
            dao.updateSamples(rebuilt);val last=page.last();afterTimestamp=last.timestamp;afterId=last.id
        }
        dao.refreshSampleCount(surveyId);val diagnostics=gridUpdater.rebuildSurvey(surveyId);_status.value=_status.value.copy(gridDiagnostics=diagnostics,message="Survey grid rebuilt from raw soundings")
    }

    suspend fun submitDemo(observation:DepthObservation)=onDepth(observation,allowDemo=true)

    private suspend fun onDepth(observation:DepthObservation,allowDemo:Boolean)=mutex.withLock{
        val survey=_status.value.activeSurvey;val offset=survey?.sounderOffsetMeters?:settings.settings.first().sounderOffsetMeters;val measured=(observation.rawDepthMeters+(observation.offsetMeters?:0.0)+offset).coerceAtLeast(.01);val normalized=survey?.takeIf{it.tideMode==TideMode.MANUAL.name}?.let{measured-it.manualTideOffsetMeters}
        fun live(message:String,disposition:DepthDisposition?=null){_status.value=_status.value.copy(lastRawDepthMeters=observation.rawDepthMeters,lastDepthMeters=measured,lastDepthReference=DepthReference.BELOW_SURFACE,lastSentenceType=observation.sentenceType.name,lastDepthReceivedElapsedRealtime=observation.receivedElapsedRealtime,lastDepthIsDemo=allowDemo,lastDepthIsChartDatum=normalized!=null,lastDisposition=disposition,message=message)}
        if(survey==null){live(if(allowDemo)"Ready · live Demo sonar received" else "Ready · live NMEA depth received");return@withLock}
        val positionState=acceptedPosition.state.value;val fix=positionState.acceptedFix?:run{live("Depth received; waiting for Accepted Position");return@withLock}
        if(fix.positionProvider==PositionProvider.DEMO&&!allowDemo){live("Real sonar was not paired with Demo GPS");return@withLock};if(fix.positionProvider!=PositionProvider.DEMO&&allowDemo){live("Demo sonar is waiting for Demo GPS");return@withLock}
        val age=abs(observation.receivedElapsedRealtime-fix.receivedElapsedRealtime);if(age>2_000L){live("Depth not recorded: position is ${age} ms away",DepthDisposition.REJECTED_STALE_POSITION);return@withLock}
        val gridDepth=normalized?:measured;val candidate=DepthCandidate(fix.latitude,fix.longitude,observation.receivedElapsedRealtime,observation.rawDepthMeters,gridDepth,fix.horizontalAccuracyMeters)
        lastRecorded?.let{previous->if(candidate.timestamp-previous.timestamp<1_000L)return@withLock;if(AnchorGeometry.distanceMeters(previous.latitude,previous.longitude,candidate.latitude,candidate.longitude)<1.5&&abs(candidate.normalizedDepthMeters-previous.normalizedDepthMeters)<.2)return@withLock}
        val result=integrity.evaluate(candidate);val trust=positionState.trust?:FixTrust.DEGRADED;val touched=mutableSetOf<GridCoordinate>()
        if(result.releasedElapsedTimestamps.isNotEmpty()){val released=dao.samplesByElapsed(survey.id,result.releasedElapsedTimestamps);dao.releaseSlopeSamples(survey.id,result.releasedElapsedTimestamps);touched+=released.map{GridCoordinate(it.baseGridX,it.baseGridY)}}
        val projected=SonarGrid.project(fix.latitude,fix.longitude);val coordinate=GridCoordinate(floor(projected.first/5.0).toLong(),floor(projected.second/5.0).toLong());touched+=coordinate;val wallTimestamp=System.currentTimeMillis()-(SystemClock.elapsedRealtime()-observation.receivedElapsedRealtime).coerceAtLeast(0L)
        dao.insertSampleAndIncrement(DepthSampleEntity(surveyId=survey.id,timestamp=wallTimestamp,latitude=fix.latitude,longitude=fix.longitude,baseGridX=coordinate.baseGridX,baseGridY=coordinate.baseGridY,sourceElapsedRealtime=observation.receivedElapsedRealtime,rawDepthMeters=observation.rawDepthMeters,measuredDepthMeters=measured,normalizedDepthMeters=normalized,depthReference=DepthReference.BELOW_SURFACE.name,sentenceType=observation.sentenceType.name,nmeaOffsetMeters=observation.offsetMeters,horizontalAccuracyMeters=fix.horizontalAccuracyMeters,gpsSource=positionState.selectedSource.name,positionProvider=fix.positionProvider.name,hdop=fix.hdop,sogKnots=fix.sogKnots,fixTrust=trust.name,positionAgeMillis=age,disposition=result.disposition.name,usable=result.usable,integrityReason=result.reason,positionCorrectionApplied=false,positionCorrectionMethod="NONE"))
        val diagnostics=gridUpdater.updateCells(survey.id,touched);lastRecorded=candidate;_status.value=_status.value.copy(activeSurvey=survey.copy(sampleCount=survey.sampleCount+1),lastRawDepthMeters=observation.rawDepthMeters,lastDepthMeters=gridDepth,lastDepthReference=DepthReference.BELOW_SURFACE,lastSentenceType=observation.sentenceType.name,lastDepthReceivedElapsedRealtime=observation.receivedElapsedRealtime,lastDepthIsDemo=allowDemo,lastDepthIsChartDatum=normalized!=null,lastDisposition=result.disposition,gridDiagnostics=diagnostics,message=if(result.usable)"Depth sample recorded" else result.reason?:"Depth quarantined")
    }
}
