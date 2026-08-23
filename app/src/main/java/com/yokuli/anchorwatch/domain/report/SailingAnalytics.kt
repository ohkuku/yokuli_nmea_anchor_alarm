package com.yokuli.anchorwatch.domain.report

import kotlin.math.abs

enum class TackSide { PORT, STARBOARD, UNKNOWN }
enum class PointOfSail { CLOSE_HAULED, CLOSE_REACH, BEAM_REACH, BROAD_REACH, RUNNING, UNKNOWN }

data class SailingAnalyticsSummary(
    val usableSampleCount:Long,
    val portTackMillis:Long,
    val starboardTackMillis:Long,
    val pointOfSailMillis:Map<PointOfSail,Long>,
    val tackCount:Int,
    val gybeCount:Int,
)

/**
 * Conservative time-weighted sailing classifier. It requires true-wind angle,
 * useful boat speed and a persistent side change before labelling a maneuver.
 * Short gaps and wind-angle jitter are deliberately ignored.
 */
class SailingAnalyticsAccumulator{
    private var previousTimestamp:Long?=null
    private var previousSide=TackSide.UNKNOWN
    private var previousPoint=PointOfSail.UNKNOWN
    private var stableSide=TackSide.UNKNOWN
    private var stablePoint=PointOfSail.UNKNOWN
    private var pendingSide=TackSide.UNKNOWN
    private var pendingSince:Long?=null
    private var pendingSamples=0
    private var usable=0L
    private var portMillis=0L
    private var starboardMillis=0L
    private val pointMillis=PointOfSail.entries.associateWith{0L}.toMutableMap()
    private var tacks=0
    private var gybes=0

    fun add(timestamp:Long,trueWindAngleDegrees:Double?,speedKnots:Double?){
        val signed=trueWindAngleDegrees?.takeIf{it.isFinite()}?.let(::signedAngle)
        val speed=speedKnots?.takeIf{it.isFinite()&&it>=MIN_SAILING_SPEED_KNOTS}
        val side=signed?.let(::side)?:TackSide.UNKNOWN
        val point=signed?.let{pointOfSail(abs(it))}?:PointOfSail.UNKNOWN
        val oldTimestamp=previousTimestamp
        if(oldTimestamp!=null){
            val elapsed=timestamp-oldTimestamp
            if(elapsed in 1..MAX_WEIGHTED_GAP_MILLIS&&speed!=null&&previousSide!=TackSide.UNKNOWN){
                when(previousSide){TackSide.PORT->portMillis+=elapsed;TackSide.STARBOARD->starboardMillis+=elapsed;else->Unit}
                pointMillis[previousPoint]=(pointMillis[previousPoint]?:0L)+elapsed
            }
        }
        previousTimestamp=timestamp
        previousSide=if(speed==null)TackSide.UNKNOWN else side
        previousPoint=if(speed==null)PointOfSail.UNKNOWN else point
        if(speed==null||side==TackSide.UNKNOWN){resetPending();return}
        usable++
        if(stableSide==TackSide.UNKNOWN){stableSide=side;stablePoint=point;resetPending();return}
        if(side==stableSide){stablePoint=point;resetPending();return}
        if(side!=pendingSide){pendingSide=side;pendingSince=timestamp;pendingSamples=1;return}
        pendingSamples++
        val since=pendingSince?:timestamp
        if(pendingSamples>=MIN_TRANSITION_SAMPLES&&timestamp-since>=MIN_TRANSITION_MILLIS){
            val oldPoint=stablePoint
            if(oldPoint!=PointOfSail.UNKNOWN&&point!=PointOfSail.UNKNOWN){
                if(oldPoint.isUpwind&&point.isUpwind)tacks++
                else if(oldPoint.isDownwind&&point.isDownwind)gybes++
            }
            stableSide=side;stablePoint=point;resetPending()
        }
    }

    fun summary()=SailingAnalyticsSummary(usable,portMillis,starboardMillis,pointMillis.toMap(),tacks,gybes)

    private fun resetPending(){pendingSide=TackSide.UNKNOWN;pendingSince=null;pendingSamples=0}
    private fun signedAngle(value:Double)=((value+540.0)%360.0)-180.0
    private fun side(value:Double)=when{value>DEAD_BAND_DEGREES->TackSide.STARBOARD;value< -DEAD_BAND_DEGREES->TackSide.PORT;else->TackSide.UNKNOWN}
    private fun pointOfSail(value:Double)=when{
        value<30.0->PointOfSail.UNKNOWN
        value<55.0->PointOfSail.CLOSE_HAULED
        value<75.0->PointOfSail.CLOSE_REACH
        value<110.0->PointOfSail.BEAM_REACH
        value<150.0->PointOfSail.BROAD_REACH
        value<=180.0->PointOfSail.RUNNING
        else->PointOfSail.UNKNOWN
    }
    private val PointOfSail.isUpwind get()=this==PointOfSail.CLOSE_HAULED||this==PointOfSail.CLOSE_REACH
    private val PointOfSail.isDownwind get()=this==PointOfSail.BROAD_REACH||this==PointOfSail.RUNNING

    private companion object{
        const val MIN_SAILING_SPEED_KNOTS=.8
        const val DEAD_BAND_DEGREES=8.0
        const val MAX_WEIGHTED_GAP_MILLIS=5_000L
        const val MIN_TRANSITION_MILLIS=5_000L
        const val MIN_TRANSITION_SAMPLES=3
    }
}
