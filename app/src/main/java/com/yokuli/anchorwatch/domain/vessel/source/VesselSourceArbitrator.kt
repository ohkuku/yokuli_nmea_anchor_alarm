package com.yokuli.anchorwatch.domain.vessel.source

import com.yokuli.anchorwatch.domain.vessel.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

data class MetricSourcePreference(
    val preference:VesselSourcePreference=VesselSourcePreference.AUTO,
    val pinnedSourceId:String?=null,
    val allowPinnedFallback:Boolean=false,
)

object VesselSourceConflictPolicy{
    data class Threshold(val difference:Double,val sustainedMillis:Long)
    fun threshold(metric:VesselMetricId)=when(metric){
        VesselMetricId.HEADING_TRUE,VesselMetricId.HEADING_MAGNETIC->Threshold(15.0,3_000L)
        VesselMetricId.POSITION->Threshold(30.0,5_000L)
        VesselMetricId.SOG,VesselMetricId.SPEED_THROUGH_WATER->Threshold(2.0,5_000L)
        VesselMetricId.TRUE_WIND_DIRECTION,VesselMetricId.TRUE_WIND_ANGLE,VesselMetricId.APPARENT_WIND_ANGLE->Threshold(30.0,5_000L)
        VesselMetricId.TRUE_WIND_SPEED,VesselMetricId.APPARENT_WIND_SPEED->Threshold(5.0,5_000L)
        else->null
    }
    fun difference(metric:VesselMetricId,a:Any,b:Any):Double?=when{
        metric==VesselMetricId.POSITION&&a is VesselPosition&&b is VesselPosition->distanceMeters(a,b)
        a is Number&&b is Number&&metric in ANGULAR->abs(((a.toDouble()-b.toDouble()+540.0)%360.0)-180.0)
        a is Number&&b is Number->abs(a.toDouble()-b.toDouble())
        else->null
    }
    /** Position sources are only in conflict beyond both the fixed marine
     * guardrail and three times their combined reported GNSS uncertainty. */
    fun effectiveDifferenceThreshold(metric:VesselMetricId,a:Any,b:Any,base:Double):Double{
        if(metric!=VesselMetricId.POSITION||a !is VesselPosition||b !is VesselPosition)return base
        val combined=hypot(a.horizontalAccuracyMeters?.coerceAtLeast(0.0)?:0.0,b.horizontalAccuracyMeters?.coerceAtLeast(0.0)?:0.0)
        return max(base,combined*3.0)
    }
    private fun distanceMeters(a:VesselPosition,b:VesselPosition):Double{
        val lat1=Math.toRadians(a.latitude);val lat2=Math.toRadians(b.latitude);val dLat=lat2-lat1;val dLon=Math.toRadians(b.longitude-a.longitude)
        val h=kotlin.math.sin(dLat/2)*kotlin.math.sin(dLat/2)+kotlin.math.cos(lat1)*kotlin.math.cos(lat2)*kotlin.math.sin(dLon/2)*kotlin.math.sin(dLon/2)
        return 6_371_000.0*2*kotlin.math.atan2(kotlin.math.sqrt(h),kotlin.math.sqrt(1-h))
    }
    private val ANGULAR=setOf(VesselMetricId.HEADING_TRUE,VesselMetricId.HEADING_MAGNETIC,VesselMetricId.COG,VesselMetricId.TRUE_WIND_DIRECTION,VesselMetricId.TRUE_WIND_ANGLE,VesselMetricId.APPARENT_WIND_ANGLE,VesselMetricId.CURRENT_SET,VesselMetricId.WAYPOINT_BEARING)
}

/** Deterministic per-metric selector. Packet arrival order is never a priority. */
class VesselSourceArbitrator{
    private data class State(var selectedId:String?=null,var recoveryId:String?=null,var recoverySince:Long?=null,var conflictSince:Long?=null)
    private val states=mutableMapOf<VesselMetricId,State>()

    @Synchronized fun <T> select(metric:VesselMetricId,raw:List<VesselSourceCandidate<T>>,settings:MetricSourcePreference,now:Long):VesselSourceSelection<T>{
        val state=states.getOrPut(metric){State()};val candidates=raw.map{candidate->
            val age=now-candidate.receivedElapsedRealtime
            candidate.copy(validity=when{candidate.validity in setOf(CandidateValidity.INVALID,CandidateValidity.DISABLED)->candidate.validity;age<0||age>staleMillis(metric)->CandidateValidity.STALE;candidate.quality==VesselDataQuality.UNKNOWN->CandidateValidity.LOW_QUALITY;else->CandidateValidity.ELIGIBLE})
        }
        val eligible=candidates.filter{it.validity==CandidateValidity.ELIGIBLE&&matchesPreference(it,settings.preference)}
        val pinned=settings.pinnedSourceId?.let{id->candidates.firstOrNull{it.source.id==id&&it.validity==CandidateValidity.ELIGIBLE}}
        val pool=if(settings.pinnedSourceId!=null&&!settings.allowPinnedFallback)listOfNotNull(pinned) else eligible
        val ranked=pool.sortedWith(compareByDescending<VesselSourceCandidate<T>>{priority(metric,it)}.thenBy{it.source.id})
        val current=state.selectedId?.let{id->ranked.firstOrNull{it.source.id==id}}
        val best=ranked.firstOrNull()
        val selected=when{
            settings.pinnedSourceId!=null&&!settings.allowPinnedFallback->pinned
            current==null->{state.recoveryId=null;state.recoverySince=null;best}
            best==null||best.source.id==current.source.id->{state.recoveryId=null;state.recoverySince=null;current}
            priority(metric,best)<=priority(metric,current)->{state.recoveryId=null;state.recoverySince=null;current}
            state.recoveryId!=best.source.id->{state.recoveryId=best.source.id;state.recoverySince=now;current}
            now-(state.recoverySince?:now)>=recoveryMillis(metric)->{state.recoveryId=null;state.recoverySince=null;best}
            else->current
        }
        state.selectedId=selected?.source?.id
        val threshold=VesselSourceConflictPolicy.threshold(metric)
        val conflicting=if(selected==null||threshold==null)emptyList() else eligible.filter{candidate->
            if(candidate.source.id==selected.source.id)return@filter false
            val selectedValue=selected.value as Any;val candidateValue=candidate.value as Any
            val difference=VesselSourceConflictPolicy.difference(metric,selectedValue,candidateValue)?:return@filter false
            difference>VesselSourceConflictPolicy.effectiveDifferenceThreshold(metric,selectedValue,candidateValue,threshold.difference)
        }
        if(conflicting.isEmpty())state.conflictSince=null else if(state.conflictSince==null)state.conflictSince=now
        val conflictActive=conflicting.isNotEmpty()&&state.conflictSince?.let{now-it>=threshold!!.sustainedMillis}==true
        val conflict=VesselSourceConflict(conflictActive,selected?.source,if(conflictActive)conflicting.map{it.source}else emptyList(),if(conflictActive)"Eligible sources disagree" else "")
        val reason=when{settings.pinnedSourceId!=null&&pinned==null->"PINNED_SOURCE_UNAVAILABLE";selected==null->"NO_ELIGIBLE_SOURCE";settings.pinnedSourceId!=null->"PINNED_SOURCE";else->"AUTO_SELECTED"}
        return VesselSourceSelection(selected,candidates,conflict,reason,settings.pinnedSourceId!=null&&pinned==null)
    }

    @Synchronized fun reset(){states.clear()}
    private fun <T> matchesPreference(candidate:VesselSourceCandidate<T>,preference:VesselSourcePreference)=when(preference){
        VesselSourcePreference.AUTO->true
        VesselSourcePreference.BOAT->candidate.sourceClass==VesselSourceClass.BOAT_NMEA
        VesselSourcePreference.PHONE->candidate.sourceClass in setOf(VesselSourceClass.PHONE_GNSS,VesselSourceClass.PHONE_DEVICE_COMPASS,VesselSourceClass.PHONE_VESSEL_HEADING,VesselSourceClass.PHONE_IMU,VesselSourceClass.PHONE_BAROMETER)
        VesselSourcePreference.DERIVED->candidate.sourceClass in setOf(VesselSourceClass.DERIVED_WATER,VesselSourceClass.DERIVED_GROUND)
    }
    private fun <T> priority(metric:VesselMetricId,candidate:VesselSourceCandidate<T>):Int{
        if(metric in setOf(VesselMetricId.HEADING_TRUE,VesselMetricId.HEADING_MAGNETIC))return when{
            candidate.source.sentenceType=="HDT"->500
            candidate.source.sentenceType=="HDG"->400
            candidate.sourceClass==VesselSourceClass.PHONE_VESSEL_HEADING->300
            candidate.source.sentenceType=="VHW"->200
            candidate.source.sentenceType=="HDM"->100
            else->50
        }
        return when(candidate.sourceClass){VesselSourceClass.BOAT_NMEA->400;VesselSourceClass.PHONE_GNSS,VesselSourceClass.PHONE_VESSEL_HEADING,VesselSourceClass.PHONE_DEVICE_COMPASS,VesselSourceClass.PHONE_IMU,VesselSourceClass.PHONE_BAROMETER->300;VesselSourceClass.DERIVED_WATER->200;VesselSourceClass.DERIVED_GROUND->100;VesselSourceClass.DEMO->50;VesselSourceClass.NONE->0}
    }
    private fun recoveryMillis(metric:VesselMetricId)=when(metric){VesselMetricId.HEADING_TRUE,VesselMetricId.HEADING_MAGNETIC,VesselMetricId.DEPTH->3_000L;else->5_000L}
    private fun staleMillis(metric:VesselMetricId)=when(metric){VesselMetricId.POSITION,VesselMetricId.SOG,VesselMetricId.COG,VesselMetricId.HEADING_TRUE,VesselMetricId.HEADING_MAGNETIC,VesselMetricId.SPEED_THROUGH_WATER,VesselMetricId.RATE_OF_TURN->5_000L;VesselMetricId.DEPTH->60_000L;VesselMetricId.PRESSURE->10*60_000L;else->60_000L}
}
