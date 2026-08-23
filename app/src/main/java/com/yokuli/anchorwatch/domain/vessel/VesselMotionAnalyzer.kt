package com.yokuli.anchorwatch.domain.vessel

import kotlin.math.abs
import kotlin.math.sqrt

data class VesselMotionPoint(
    val elapsedRealtime:Long,
    val heelDegrees:Double,
    val pitchDegrees:Double,
    val rollRateDegreesPerSecond:Double,
    val pitchRateDegreesPerSecond:Double,
    val verticalAccelerationG:Double,
)

/** Bounded in-memory analytics. Raw IMU samples are deliberately never persisted. */
class VesselMotionAnalyzer(
    private val retentionMillis:Long=5*60_000L,
    private val maxSamples:Int=15_000,
){
    private val points=ArrayDeque<VesselMotionPoint>()
    private val impacts=ArrayDeque<Long>()
    private var lastImpactElapsed:Long?=null

    @Synchronized fun add(value:VesselMotionPoint):VesselMotion{
        points.addLast(value)
        while(points.size>maxSamples)points.removeFirst()
        val cutoff=value.elapsedRealtime-retentionMillis
        while(points.firstOrNull()?.elapsedRealtime?.let{it<cutoff}==true)points.removeFirst()
        while(impacts.firstOrNull()?.let{it<cutoff}==true)impacts.removeFirst()
        val impact=value.verticalAccelerationG.takeIf{it>=IMPACT_THRESHOLD_G&&lastImpactElapsed?.let{last->value.elapsedRealtime-last>=IMPACT_REFRACTORY_MILLIS}!=false}
        if(impact!=null){lastImpactElapsed=value.elapsedRealtime;impacts.addLast(value.elapsedRealtime)}
        return calculate(value.elapsedRealtime).copy(impactCandidateElapsedRealtime=value.elapsedRealtime.takeIf{impact!=null},impactPeakG=impact,impactCandidateCount=impacts.size)
    }

    @Synchronized fun reset(){points.clear();impacts.clear();lastImpactElapsed=null}

    private fun calculate(now:Long):VesselMotion{
        val minute=points.filter{it.elapsedRealtime>=now-60_000L}
        if(minute.size<20)return VesselMotion()
        val rollRms=rmsDetrended(minute.map{it.heelDegrees})
        val pitchRms=rmsDetrended(minute.map{it.pitchDegrees})
        val rollRateRms=rms(minute.map{it.rollRateDegreesPerSecond})
        val pitchRateRms=rms(minute.map{it.pitchRateDegreesPerSecond})
        val verticalRms=rms(minute.map{it.verticalAccelerationG})
        val score=100.0*(.30*unit(rollRms/10.0)+.25*unit(rollRateRms/20.0)+.15*unit(pitchRms/6.0)+.15*unit(pitchRateRms/15.0)+.15*unit(verticalRms/.25))
        val period=rollPeriod(points.filter{it.elapsedRealtime>=now-120_000L})
        return VesselMotion(score,rollRms,pitchRms,rollRateRms,pitchRateRms,verticalRms,period.first,period.second)
    }

    private fun rollPeriod(window:List<VesselMotionPoint>):Pair<Double?,MotionPeriodConfidence>{
        if(window.size<80||(window.last().elapsedRealtime-window.first().elapsedRealtime)<100_000L)return null to MotionPeriodConfidence.UNAVAILABLE
        val mean=window.map{it.heelDegrees}.average()
        val amplitude=(window.maxOf{it.heelDegrees}-window.minOf{it.heelDegrees})/2.0
        if(amplitude<1.0)return null to MotionPeriodConfidence.LOW
        val crossings=mutableListOf<Long>()
        var previous=window.first().heelDegrees-mean
        window.drop(1).forEach{point->val current=point.heelDegrees-mean;if(previous<=0&&current>0)crossings+=point.elapsedRealtime;previous=current}
        val periods=crossings.zipWithNext{a,b->(b-a)/1000.0}.filter{it in 2.0..20.0}
        if(periods.size<4)return null to MotionPeriodConfidence.LOW
        val median=periods.sorted().let{it[it.size/2]}
        val average=periods.average()
        val cv=sqrt(periods.sumOf{(it-average)*(it-average)}/periods.size)/average.coerceAtLeast(.001)
        val confidence=when{periods.size>=8&&cv<=.20->MotionPeriodConfidence.HIGH;cv<=.35->MotionPeriodConfidence.MEDIUM;else->MotionPeriodConfidence.LOW}
        return (if(confidence==MotionPeriodConfidence.LOW)null else median) to confidence
    }

    private fun rms(values:List<Double>)=sqrt(values.sumOf{it*it}/values.size)
    private fun rmsDetrended(values:List<Double>):Double{val mean=values.average();return sqrt(values.sumOf{(it-mean)*(it-mean)}/values.size)}
    private fun unit(value:Double)=value.coerceIn(0.0,1.0)

    companion object{const val ALGORITHM_VERSION="MOTION_SCORE_V1";const val IMPACT_THRESHOLD_G=.6;const val IMPACT_REFRACTORY_MILLIS=1_500L}
}
