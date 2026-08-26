package com.yokuli.anchorwatch.domain.report

import kotlin.math.abs
import kotlin.math.max

/** A report-only filter for a very short handset-handling spike.
 *
 * Runtime recording is controlled by the user and is never stopped by this
 * policy. A point is rejected only when it is an isolated discontinuity whose
 * two neighbours agree, and GPS does not show a simultaneous vessel turn.
 * Sustained heel, gradual motion and tacks therefore remain report evidence. */
data class TripAttitudeFilterPoint(
    val timestamp:Long,
    val heelDegrees:Double?,
    val pitchDegrees:Double?,
    val rollRateDegreesPerSecond:Double?,
    val pitchRateDegreesPerSecond:Double?,
    val cogDegrees:Double?,
    val cogFresh:Boolean,
    val usable:Boolean,
)

object TripAttitudeArtifactPolicy{
    fun isShortHandlingArtifact(previous:TripAttitudeFilterPoint,current:TripAttitudeFilterPoint,next:TripAttitudeFilterPoint):Boolean{
        if(!previous.usable||!current.usable||!next.usable)return false
        val total=next.timestamp-previous.timestamp
        if(total !in 1..MAX_WINDOW_MILLIS||current.timestamp<=previous.timestamp||current.timestamp>=next.timestamp)return false
        val previousHeel=previous.heelDegrees?:return false
        val currentHeel=current.heelDegrees?:return false
        val nextHeel=next.heelDegrees?:return false
        val previousPitch=previous.pitchDegrees?:0.0
        val currentPitch=current.pitchDegrees?:0.0
        val nextPitch=next.pitchDegrees?:0.0
        if(abs(nextHeel-previousHeel)>NEIGHBOUR_HEEL_AGREEMENT_DEGREES||abs(nextPitch-previousPitch)>NEIGHBOUR_PITCH_AGREEMENT_DEGREES)return false

        // A tack/gybe is vessel motion. GPS COG is deliberately independent
        // evidence and protects that point even if attitude changes quickly.
        if(previous.cogFresh&&next.cogFresh&&previous.cogDegrees!=null&&next.cogDegrees!=null&&circularDifference(previous.cogDegrees,next.cogDegrees)>=VESSEL_TURN_DEGREES)return false

        val ratio=(current.timestamp-previous.timestamp).toDouble()/total
        val expectedHeel=previousHeel+(nextHeel-previousHeel)*ratio
        val expectedPitch=previousPitch+(nextPitch-previousPitch)*ratio
        val discontinuity=abs(currentHeel-expectedHeel)>=HEEL_SPIKE_DEGREES||abs(currentPitch-expectedPitch)>=PITCH_SPIKE_DEGREES
        if(!discontinuity)return false
        val rate=max(abs(current.rollRateDegreesPerSecond?:0.0),abs(current.pitchRateDegreesPerSecond?:0.0))
        val leftSeconds=(current.timestamp-previous.timestamp).coerceAtLeast(1L)/1_000.0
        val rightSeconds=(next.timestamp-current.timestamp).coerceAtLeast(1L)/1_000.0
        val observedSlope=max(abs(currentHeel-previousHeel)/leftSeconds,abs(nextHeel-currentHeel)/rightSeconds)
        return rate>=HANDLING_RATE_DEGREES_PER_SECOND||observedSlope>=HANDLING_RATE_DEGREES_PER_SECOND
    }

    private fun circularDifference(first:Double,second:Double)=abs(((second-first+540.0)%360.0)-180.0)

    private const val MAX_WINDOW_MILLIS=4_000L
    private const val NEIGHBOUR_HEEL_AGREEMENT_DEGREES=5.0
    private const val NEIGHBOUR_PITCH_AGREEMENT_DEGREES=5.0
    private const val HEEL_SPIKE_DEGREES=12.0
    private const val PITCH_SPIKE_DEGREES=10.0
    private const val HANDLING_RATE_DEGREES_PER_SECOND=25.0
    private const val VESSEL_TURN_DEGREES=12.0
}
