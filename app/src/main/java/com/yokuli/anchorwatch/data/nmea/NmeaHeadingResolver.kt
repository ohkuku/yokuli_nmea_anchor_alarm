package com.yokuli.anchorwatch.data.nmea

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

/**
 * Arbitrates physical boat-heading sentences without allowing packet order to
 * become source selection. Identity includes talker + sentence (IIHDT, HCHDG,
 * SDVHW). A higher-quality source is preferred, but the current source remains
 * sticky while fresh so alternating gateways cannot make the display flicker.
 */
class NmeaHeadingResolver(
    private val freshMillis:Long=5_000L,
    private val conflictHoldMillis:Long=3_000L,
    private val conflictThresholdDegrees:Double=15.0,
){
    private val candidates=linkedMapOf<String,NmeaHeadingCandidate>()
    private var selectedId:String?=null
    private var pinnedId:String?=null
    private var conflictStartedAt:Long?=null

    @Synchronized fun pin(sourceId:String?){pinnedId=sourceId?.trim()?.uppercase()?.takeIf{it.isNotBlank()};selectedId=null}

    @Synchronized fun accept(update:NmeaUpdate,now:Long):NmeaHeadingResolution{
        val id=update.sentenceId.ifBlank{update.type}.uppercase()
        if(update.type in HEADING_TYPES&&!update.holdAllowed){
            candidates.remove(id)
            if(selectedId==id)selectedId=null
        }else if(update.type in HEADING_TYPES&&(update.trueHeading!=null||update.magneticHeading!=null)){
            candidates[id]=NmeaHeadingCandidate(id,update.type,update.trueHeading?.normalized(),update.magneticHeading?.normalized(),now)
        }
        return resolve(now)
    }

    @Synchronized fun resolve(now:Long):NmeaHeadingResolution{
        candidates.entries.removeAll{now-it.value.receivedElapsedRealtime>freshMillis}
        val available=candidates.values.sortedWith(compareByDescending<NmeaHeadingCandidate>{priority(it)}.thenByDescending{it.receivedElapsedRealtime})
        val pinned=pinnedId?.let(candidates::get)
        val current=selectedId?.let(candidates::get)
        val best=when{
            pinnedId!=null->pinned
            current==null->available.firstOrNull()
            available.firstOrNull()?.let{priority(it)>priority(current)}==true->available.first()
            else->current
        }
        selectedId=best?.sourceId
        val trueCandidates=available.mapNotNull{candidate->candidate.trueDegrees?.let{candidate to it}}
        val maximumDifference=trueCandidates.indices.flatMap{i->(i+1 until trueCandidates.size).map{j->angularDifference(trueCandidates[i].second,trueCandidates[j].second)}}.maxOrNull()
        val conflicting=maximumDifference?.let{it>conflictThresholdDegrees}==true
        if(conflicting){if(conflictStartedAt==null)conflictStartedAt=now}else conflictStartedAt=null
        val conflict=conflicting&&conflictStartedAt?.let{now-it>=conflictHoldMillis}==true
        return NmeaHeadingResolution(best,available,conflict,maximumDifference,pinnedId!=null&&pinned==null)
    }

    @Synchronized fun reset(){candidates.clear();selectedId=null;conflictStartedAt=null}

    private fun priority(candidate:NmeaHeadingCandidate)=when{
        candidate.sentenceType=="HDT"&&candidate.trueDegrees!=null->300
        candidate.sentenceType=="HDG"&&candidate.trueDegrees!=null->200
        candidate.sentenceType=="VHW"&&candidate.trueDegrees!=null->100
        candidate.magneticDegrees!=null->10
        else->0
    }
    private fun Double.normalized()=(this%360.0+360.0)%360.0
    private fun angularDifference(a:Double,b:Double)=abs(((a-b+540.0)%360.0)-180.0)

    companion object{private val HEADING_TYPES=setOf("HDT","HDG","VHW","HDM")}
}
