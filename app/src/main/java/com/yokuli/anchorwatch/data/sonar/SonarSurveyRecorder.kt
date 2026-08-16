package com.yokuli.anchorwatch.data.sonar

import android.os.SystemClock
import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.data.database.DepthSampleEntity
import com.yokuli.anchorwatch.data.database.SonarDao
import com.yokuli.anchorwatch.data.database.SonarSurveyEntity
import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import com.yokuli.anchorwatch.domain.model.FixTrust
import com.yokuli.anchorwatch.domain.model.HeadingSource
import com.yokuli.anchorwatch.domain.model.PositionProvider
import com.yokuli.anchorwatch.domain.sonar.DepthCandidate
import com.yokuli.anchorwatch.domain.sonar.DepthDisposition
import com.yokuli.anchorwatch.domain.sonar.DepthIntegrityFilter
import com.yokuli.anchorwatch.domain.sonar.DepthObservation
import com.yokuli.anchorwatch.domain.sonar.TideMode
import com.yokuli.anchorwatch.domain.sonar.DepthSentenceType
import com.yokuli.anchorwatch.domain.sonar.DepthReference
import com.yokuli.anchorwatch.domain.sonar.DepthNormalizer
import com.yokuli.anchorwatch.domain.sonar.SonarGrid
import com.yokuli.anchorwatch.location.AcceptedPositionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.floor

data class SonarRecorderStatus(
    val activeSurvey: SonarSurveyEntity? = null,
    val lastDepthMeters: Double? = null,
    val lastDepthIsChartDatum: Boolean = false,
    val lastDisposition: DepthDisposition? = null,
    val message: String = "Not recording",
)

/**
 * Independent survey recorder. Depth is never paired with a raw provider fix:
 * it consumes only the process-wide Accepted Position and enforces a 2 s join.
 */
@Singleton
class SonarSurveyRecorder @Inject constructor(
    private val navigation: NavigationRepository,
    private val acceptedPosition: AcceptedPositionRepository,
    private val dao: SonarDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val integrity = DepthIntegrityFilter()
    private val _status = MutableStateFlow(SonarRecorderStatus())
    val status = _status.asStateFlow()
    private var lastRecorded: DepthCandidate? = null

    init {
        scope.launch { mutex.withLock { dao.active()?.let { _status.value = SonarRecorderStatus(activeSurvey=it,message="Recording restored") } } }
        scope.launch { navigation.depthObservations.collect(::onDepth) }
    }

    suspend fun start(
        name: String,
        tideMode: TideMode,
        manualTideOffsetMeters: Double,
        transducerDraftMeters: Double,
        keelOffsetMeters: Double,
        gpsToTransducerMeters: Double,
        configuredDepthReference: DepthReference,
    ): Long = mutex.withLock {
        _status.value.activeSurvey?.let { return@withLock it.id }
        dao.active()?.let { existing ->
            _status.value = SonarRecorderStatus(activeSurvey=existing,message="Recording restored")
            return@withLock existing.id
        }
        integrity.reset();lastRecorded=null
        val now=System.currentTimeMillis()
        val entity=SonarSurveyEntity(
            name=name.trim().ifBlank{DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(now))},startedAt=now,tideMode=tideMode.name,
            manualTideOffsetMeters=manualTideOffsetMeters,transducerDraftMeters=transducerDraftMeters.coerceAtLeast(0.0),
            keelOffsetMeters=keelOffsetMeters.coerceAtLeast(0.0),gpsToTransducerMeters=gpsToTransducerMeters.coerceAtLeast(0.0),
            configuredDepthReference=configuredDepthReference.name,
        )
        val saved=entity.copy(id=dao.insertSurvey(entity))
        _status.value=SonarRecorderStatus(activeSurvey=saved,message="Waiting for fresh depth and accepted position")
        saved.id
    }

    suspend fun stop() = mutex.withLock {
        val active=_status.value.activeSurvey?:dao.active()?:return@withLock
        val now=System.currentTimeMillis();dao.refreshSampleCount(active.id);dao.finish(active.id,now)
        _status.value=_status.value.copy(activeSurvey=null,message="Survey saved")
        integrity.reset();lastRecorded=null
    }

    suspend fun rename(surveyId:Long,name:String){if(name.isNotBlank())dao.rename(surveyId,name.trim())}
    suspend fun delete(surveyId:Long):Boolean=dao.deleteCompleted(surveyId)>0
    suspend fun rebuild(surveyId:Long){mutex.withLock{
        val survey=dao.survey(surveyId)?:return@withLock;val filter=DepthIntegrityFilter()
        val rebuilt=mutableListOf<DepthSampleEntity>()
        dao.samplesNow(surveyId).forEach{sample->
            val reference=runCatching{DepthReference.valueOf(sample.depthReference)}.getOrDefault(DepthReference.UNKNOWN)
            val normalized=if(survey.tideMode==TideMode.MANUAL.name)DepthNormalizer.surfaceDepth(sample.measuredDepthMeters,reference,survey.transducerDraftMeters,survey.keelOffsetMeters)?.minus(survey.manualTideOffsetMeters) else null
            val candidate=DepthCandidate(sample.latitude,sample.longitude,sample.sourceElapsedRealtime,sample.rawDepthMeters,normalized?:sample.measuredDepthMeters,sample.horizontalAccuracyMeters)
            val result=filter.evaluate(candidate)
            if(result.releasedElapsedTimestamps.isNotEmpty())for(index in rebuilt.indices)if(rebuilt[index].sourceElapsedRealtime in result.releasedElapsedTimestamps)rebuilt[index]=rebuilt[index].copy(disposition=DepthDisposition.ACCEPTED_STEEP_SLOPE.name,usable=true,integrityReason="Released by coherent three-point slope")
            rebuilt+=sample.copy(normalizedDepthMeters=normalized,disposition=result.disposition.name,usable=result.usable,integrityReason=result.reason)
        }
        if(rebuilt.isNotEmpty())dao.updateSamples(rebuilt)
        dao.refreshSampleCount(surveyId);_status.value=_status.value.copy(message="Survey grid rebuilt from raw soundings")
    }}

    private suspend fun onDepth(observation:DepthObservation)=mutex.withLock {
        val survey=_status.value.activeSurvey?:run{
            _status.value=_status.value.copy(lastDepthMeters=observation.rawDepthMeters,lastDepthIsChartDatum=false,lastDisposition=null,message="Ready · live NMEA depth received")
            return@withLock
        }
        val positionState=acceptedPosition.state.value
        val fix=positionState.acceptedFix?:run{
            _status.value=_status.value.copy(message="Depth received; waiting for Accepted Position")
            return@withLock
        }
        if(fix.positionProvider==PositionProvider.DEMO){_status.value=_status.value.copy(message="Demo positions are never written to personal sonar maps");return@withLock}
        val age=abs(observation.receivedElapsedRealtime-fix.receivedElapsedRealtime)
        if(age>2_000L){
            _status.value=_status.value.copy(lastDepthMeters=observation.rawDepthMeters,lastDisposition=DepthDisposition.REJECTED_STALE_POSITION,message="Depth not recorded: position is ${age} ms away")
            return@withLock
        }
        val configuredReference=runCatching{DepthReference.valueOf(survey.configuredDepthReference)}.getOrDefault(DepthReference.UNKNOWN)
        val normalizedDepth=DepthNormalizer.normalize(observation,configuredReference,survey.transducerDraftMeters,survey.keelOffsetMeters,runCatching{TideMode.valueOf(survey.tideMode)}.getOrDefault(TideMode.OFF),survey.manualTideOffsetMeters)
        val reference=normalizedDepth.measuredReference
        val measured=normalizedDepth.measuredDepthMeters
        val normalized=normalizedDepth.chartDatumDepthMeters
        val gridDepth=normalized?:measured
        val mappingHeading=fix.headingTrueDegrees.takeIf{fix.headingSource in setOf(HeadingSource.NMEA_PHYSICAL,HeadingSource.PHONE)}
        val mapped=if(survey.gpsToTransducerMeters>0.0&&mappingHeading!=null)AnchorGeometry.project(fix.latitude,fix.longitude,(mappingHeading+180.0)%360.0,survey.gpsToTransducerMeters)else fix.latitude to fix.longitude
        val mappedAccuracy=if(survey.gpsToTransducerMeters>0.0&&mappingHeading==null)hypot(fix.horizontalAccuracyMeters?:8.0,survey.gpsToTransducerMeters)else fix.horizontalAccuracyMeters
        val candidate=DepthCandidate(mapped.first,mapped.second,observation.receivedElapsedRealtime,observation.rawDepthMeters,gridDepth,mappedAccuracy)
        val previous=lastRecorded
        if(previous!=null){
            if(candidate.timestamp-previous.timestamp<1_000L)return@withLock
            val moved=AnchorGeometry.distanceMeters(previous.latitude,previous.longitude,candidate.latitude,candidate.longitude)
            if(moved<1.5&&abs(candidate.normalizedDepthMeters-previous.normalizedDepthMeters)<.2)return@withLock
        }
        val result=integrity.evaluate(candidate)
        val trust=positionState.trust?:FixTrust.DEGRADED
        if(result.releasedElapsedTimestamps.isNotEmpty())dao.releaseSlopeSamples(survey.id,result.releasedElapsedTimestamps)
        val projected=SonarGrid.project(mapped.first,mapped.second)
        val wallTimestamp=System.currentTimeMillis()-(SystemClock.elapsedRealtime()-observation.receivedElapsedRealtime).coerceAtLeast(0L)
        val correctionApplied=survey.gpsToTransducerMeters>0.0&&mappingHeading!=null
        dao.insertSample(DepthSampleEntity(
            surveyId=survey.id,timestamp=wallTimestamp,latitude=mapped.first,longitude=mapped.second,
            baseGridX=floor(projected.first/5.0).toLong(),baseGridY=floor(projected.second/5.0).toLong(),sourceElapsedRealtime=observation.receivedElapsedRealtime,
            rawDepthMeters=observation.rawDepthMeters,measuredDepthMeters=measured,normalizedDepthMeters=normalized,depthReference=reference.name,
            sentenceType=observation.sentenceType.name,nmeaOffsetMeters=observation.offsetMeters,horizontalAccuracyMeters=mappedAccuracy,
            gpsSource=positionState.selectedSource.name,positionProvider=fix.positionProvider.name,hdop=fix.hdop,sogKnots=fix.sogKnots,
            fixTrust=trust.name,positionAgeMillis=age,disposition=result.disposition.name,usable=result.usable,integrityReason=result.reason,
            positionCorrectionApplied=correctionApplied,positionCorrectionMethod=if(correctionApplied)"PHYSICAL_HEADING_${fix.headingSource.name}" else "NONE",
        ))
        dao.refreshSampleCount(survey.id)
        lastRecorded=candidate
        _status.value=_status.value.copy(activeSurvey=survey.copy(sampleCount=survey.sampleCount+1),lastDepthMeters=gridDepth,lastDepthIsChartDatum=normalized!=null,lastDisposition=result.disposition,message=if(result.usable)"Depth sample recorded" else result.reason?:"Depth quarantined")
    }
}
