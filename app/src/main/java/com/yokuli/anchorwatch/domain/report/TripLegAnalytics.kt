package com.yokuli.anchorwatch.domain.report

import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import kotlin.math.abs

data class TripLegBoundary(val timestamp:Long,val name:String)
data class TripLegPoint(val timestamp:Long,val latitude:Double?,val longitude:Double?,val sogKnots:Double?,val boatSpeedKnots:Double?,val trueWindKnots:Double?,val heelDegrees:Double?)
data class TripLegSummary(val name:String,val startedAt:Long,val endedAt:Long,val durationMillis:Long,val distanceMeters:Double,val averageSogKnots:Double?,val averageBoatSpeedKnots:Double?,val averageTrueWindKnots:Double?,val averageAbsHeelDegrees:Double?)

/** Streaming leg statistics; waypoint count, not sample count, bounds memory. */
class TripLegAccumulator(startedAt:Long,waypoints:List<TripLegBoundary>){
    private data class Working(val boundary:TripLegBoundary,val sog:StreamingStatistics=StreamingStatistics(),val bsp:StreamingStatistics=StreamingStatistics(),val wind:StreamingStatistics=StreamingStatistics(),val heel:StreamingStatistics=StreamingStatistics(),var distance:Double=0.0,var last:TripLegPoint?=null,var lastTimestamp:Long?=null)
    private val legs=(listOf(TripLegBoundary(startedAt,"Start"))+waypoints.filter{it.timestamp>startedAt}.sortedBy{it.timestamp}).distinctBy{it.timestamp}.map(::Working)
    private var currentIndex=0

    fun add(point:TripLegPoint){
        while(currentIndex<legs.lastIndex&&point.timestamp>=legs[currentIndex+1].boundary.timestamp)currentIndex++
        val leg=legs[currentIndex];leg.lastTimestamp=point.timestamp
        point.sogKnots?.takeIf{it.isFinite()&&it>=0}?.let(leg.sog::add)
        point.boatSpeedKnots?.takeIf{it.isFinite()&&it>=0}?.let(leg.bsp::add)
        point.trueWindKnots?.takeIf{it.isFinite()&&it>=0}?.let(leg.wind::add)
        point.heelDegrees?.takeIf{it.isFinite()}?.let{leg.heel.add(abs(it))}
        if(point.latitude==null||point.longitude==null){leg.last=null;return}
        leg.last?.takeIf{point.timestamp-it.timestamp in 1..MAX_GAP_MILLIS}?.let{old->
            val distance=AnchorGeometry.distanceMeters(old.latitude!!,old.longitude!!,point.latitude,point.longitude)
            if(distance<=MAX_SEGMENT_METERS)leg.distance+=distance
        }
        leg.last=point
    }

    fun summaries(endedAt:Long):List<TripLegSummary> = legs.mapIndexedNotNull{index,leg->
        val end=legs.getOrNull(index+1)?.boundary?.timestamp?:leg.lastTimestamp?:endedAt
        if(end<=leg.boundary.timestamp||leg.lastTimestamp==null)return@mapIndexedNotNull null
        TripLegSummary(
            name=if(index==0)"Start → ${legs.getOrNull(1)?.boundary?.name?:"End"}" else "${leg.boundary.name} → ${legs.getOrNull(index+1)?.boundary?.name?:"End"}",
            startedAt=leg.boundary.timestamp,endedAt=end,durationMillis=(end-leg.boundary.timestamp).coerceAtLeast(0),distanceMeters=leg.distance,
            averageSogKnots=leg.sog.mean(),averageBoatSpeedKnots=leg.bsp.mean(),averageTrueWindKnots=leg.wind.mean(),averageAbsHeelDegrees=leg.heel.mean(),
        )
    }

    private companion object{const val MAX_GAP_MILLIS=10_000L;const val MAX_SEGMENT_METERS=500.0}
}
