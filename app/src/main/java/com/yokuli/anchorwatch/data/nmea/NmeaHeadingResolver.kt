package com.yokuli.anchorwatch.data.nmea

import com.yokuli.anchorwatch.domain.vessel.*
import com.yokuli.anchorwatch.domain.vessel.source.MetricSourcePreference
import com.yokuli.anchorwatch.domain.vessel.source.VesselSourceArbitrator
import kotlin.math.abs

data class NmeaHeadingCandidate(
    val sourceId:String,
    val sentenceType:String,
    val trueDegrees:Double?,
    val magneticDegrees:Double?,
    val receivedElapsedRealtime:Long,
)

data class NmeaHeadingResolution(
    val selected:NmeaHeadingCandidate?=null,
    val candidates:List<NmeaHeadingCandidate> = emptyList(),
    val conflict:Boolean=false,
    val conflictDegrees:Double?=null,
    val pinnedSourceUnavailable:Boolean=false,
)

/** Physical-NMEA adapter for the canonical [VesselSourceArbitrator]. It owns no
 * source priority, freshness, pin or conflict rules of its own. */
class NmeaHeadingResolver(
    private val freshMillis:Long=5_000L,
    @Suppress("UNUSED_PARAMETER") conflictHoldMillis:Long=3_000L,
    @Suppress("UNUSED_PARAMETER") conflictThresholdDegrees:Double=15.0,
){
    private val candidates=linkedMapOf<String,NmeaHeadingCandidate>()
    private var pinnedId:String?=null
    private var allowPinnedFallback=false
    private val arbitrator=VesselSourceArbitrator()

    @Synchronized fun pin(sourceId:String?,allowFallback:Boolean=false){
        pinnedId=sourceId?.trim()?.uppercase()?.takeIf{it.isNotBlank()}
        allowPinnedFallback=allowFallback
        arbitrator.reset()
    }

    @Synchronized fun accept(update:NmeaUpdate,now:Long):NmeaHeadingResolution{
        val id=update.sentenceId.ifBlank{update.type}.uppercase()
        if(update.type in HEADING_TYPES&&!update.holdAllowed)candidates.remove(id)
        else if(update.type in HEADING_TYPES&&(update.trueHeading!=null||update.magneticHeading!=null)){
            val measuredAt=listOfNotNull(update.measuredAt(NmeaMetric.TRUE_HEADING),update.measuredAt(NmeaMetric.MAGNETIC_HEADING)).maxOrNull()?:now
            candidates[id]=NmeaHeadingCandidate(id,update.type,update.trueHeading?.normalized(),update.magneticHeading?.normalized(),measuredAt)
        }
        return resolve(now)
    }

    @Synchronized fun resolve(now:Long):NmeaHeadingResolution{
        candidates.entries.removeAll{now-it.value.receivedElapsedRealtime>freshMillis}
        val available=candidates.values.sortedBy{it.sourceId}
        fun source(value:NmeaHeadingCandidate)=VesselSourceIdentity(
            id=value.sourceId,sourceType=VesselSourceType.NMEA_INPUT,sentenceType=value.sentenceType,
            fullSentenceId=value.sourceId,displayName=value.sourceId,stableKey=value.sourceId,
        )
        val trueSources=available.mapNotNull{value->value.trueDegrees?.let{VesselSourceCandidate(VesselMetricId.HEADING_TRUE,it,source(value),VesselSourceClass.BOAT_NMEA,VesselReference.TrueNorth,value.receivedElapsedRealtime)}}
        val magneticSources=available.mapNotNull{value->value.magneticDegrees?.let{VesselSourceCandidate(VesselMetricId.HEADING_MAGNETIC,it,source(value),VesselSourceClass.BOAT_NMEA,VesselReference.MagneticNorth,value.receivedElapsedRealtime)}}
        fun <T> pinFor(values:List<VesselSourceCandidate<T>>)=pinnedId?.let{stored->VesselSourcePinPolicy.resolve(values,stored)?:stored}
        val trueSelection=arbitrator.select(VesselMetricId.HEADING_TRUE,trueSources,MetricSourcePreference(VesselSourcePreference.BOAT,pinFor(trueSources),allowPinnedFallback),now)
        val magneticSelection=arbitrator.select(VesselMetricId.HEADING_MAGNETIC,magneticSources,MetricSourcePreference(VesselSourcePreference.BOAT,pinFor(magneticSources),allowPinnedFallback),now)
        val selectedId=trueSelection.selected?.source?.id?:magneticSelection.selected?.source?.id
        val selected=selectedId?.let(candidates::get)
        val trueValues=available.mapNotNull{candidate->candidate.trueDegrees?.let{candidate to it}}
        val maximumDifference=trueValues.indices.flatMap{i->(i+1 until trueValues.size).map{j->angularDifference(trueValues[i].second,trueValues[j].second)}}.maxOrNull()
        return NmeaHeadingResolution(
            selected,available,trueSelection.conflict.active,maximumDifference,
            pinnedId!=null&&trueSelection.pinnedSourceUnavailable&&magneticSelection.pinnedSourceUnavailable,
        )
    }

    @Synchronized fun reset(){candidates.clear();arbitrator.reset()}
    private fun Double.normalized()=(this%360.0+360.0)%360.0
    private fun angularDifference(a:Double,b:Double)=abs(((a-b+540.0)%360.0)-180.0)

    companion object{private val HEADING_TYPES=setOf("HDT","HDG","VHW","HDM")}
}
