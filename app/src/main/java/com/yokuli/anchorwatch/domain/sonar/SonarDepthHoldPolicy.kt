package com.yokuli.anchorwatch.domain.sonar

import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState

enum class SonarDepthHoldState { NO_DEPTH, LIVE, HELD, WARNING, EXPIRED_TIME, EXPIRED_DISTANCE }
enum class SonarAutoStopReason { DEPTH_HOLD_EXPIRED_TIME, DEPTH_HOLD_EXPIRED_DISTANCE }

data class SonarDepthHoldDecision(val state:SonarDepthHoldState,val mayRecord:Boolean,val mustStop:Boolean)

object SonarDepthHoldPolicy {
    const val LIVE_MILLIS=2_000L
    const val WARNING_MILLIS=120_000L
    const val EXPIRE_MILLIS=300_000L
    const val WARNING_DISTANCE_METERS=200.0
    const val EXPIRE_DISTANCE_METERS=500.0
    const val MAX_ACCUMULATED_STEP_METERS=100.0

    fun isValidRealDepth(observation:DepthObservation):Boolean =
        observation.rawDepthMeters.isFinite()&&observation.rawDepthMeters>0.0&&observation.rawDepthMeters<=12_000.0

    fun belongsToCurrentConnection(observationElapsedRealtime:Long,connectionStartedElapsedRealtime:Long?,state:NmeaConnectionState):Boolean =
        connectionStartedElapsedRealtime!=null&&observationElapsedRealtime>=connectionStartedElapsedRealtime&&
            state in setOf(NmeaConnectionState.CONNECTING,NmeaConnectionState.CONNECTED,NmeaConnectionState.CONNECTED_NO_FIX,NmeaConnectionState.STALE)

    fun evaluate(hasDepth:Boolean,ageMillis:Long,travelledMeters:Double):SonarDepthHoldDecision{
        val state=when{
            !hasDepth->SonarDepthHoldState.NO_DEPTH
            ageMillis>EXPIRE_MILLIS->SonarDepthHoldState.EXPIRED_TIME
            travelledMeters>EXPIRE_DISTANCE_METERS->SonarDepthHoldState.EXPIRED_DISTANCE
            ageMillis>WARNING_MILLIS||travelledMeters>WARNING_DISTANCE_METERS->SonarDepthHoldState.WARNING
            ageMillis>LIVE_MILLIS->SonarDepthHoldState.HELD
            else->SonarDepthHoldState.LIVE
        }
        return SonarDepthHoldDecision(state,state in setOf(SonarDepthHoldState.LIVE,SonarDepthHoldState.HELD,SonarDepthHoldState.WARNING),state in setOf(SonarDepthHoldState.EXPIRED_TIME,SonarDepthHoldState.EXPIRED_DISTANCE))
    }
}

data class SonarHeldDepth(
    val observation:DepthObservation,
    val provenance:DepthProvenance,
    val depthElapsedRealtime:Long,
    val connectionGeneration:Long,
    var lastPositionLatitude:Double?=null,
    var lastPositionLongitude:Double?=null,
    var travelledMeters:Double=0.0,
)

data class SonarHeldPosition(val depth:SonarHeldDepth,val ageMillis:Long,val decision:SonarDepthHoldDecision,val ignoredLargeStepMeters:Double?=null)

/** Stateful provenance tracker kept independent from Room/grid recording. */
class SonarDepthHoldTracker {
    var current:SonarHeldDepth?=null
        private set

    fun acceptRealDepth(observation:DepthObservation,provenance:DepthProvenance,connectionGeneration:Long,initialPosition:NavigationFix?):SonarHeldDepth =
        SonarHeldDepth(observation,provenance,observation.receivedElapsedRealtime,connectionGeneration,initialPosition?.latitude,initialPosition?.longitude).also{current=it}

    fun acceptPosition(fix:NavigationFix):SonarHeldPosition?{
        val depth=current?:return null
        if(fix.receivedElapsedRealtime<depth.depthElapsedRealtime)return null
        val previousLat=depth.lastPositionLatitude;val previousLon=depth.lastPositionLongitude
        val step=if(previousLat!=null&&previousLon!=null)AnchorGeometry.distanceMeters(previousLat,previousLon,fix.latitude,fix.longitude)else null
        val ignored=step?.takeIf{it>SonarDepthHoldPolicy.MAX_ACCUMULATED_STEP_METERS}
        if(step!=null&&ignored==null)depth.travelledMeters+=step
        depth.lastPositionLatitude=fix.latitude;depth.lastPositionLongitude=fix.longitude
        val age=(fix.receivedElapsedRealtime-depth.depthElapsedRealtime).coerceAtLeast(0L)
        return SonarHeldPosition(depth,age,SonarDepthHoldPolicy.evaluate(true,age,depth.travelledMeters),ignored)
    }

    /** Returns true only when an older connection's actual held value was removed. */
    fun clearForConnectionGeneration(generation:Long):Boolean{
        val depth=current?:return false
        if(depth.connectionGeneration==generation)return false
        current=null
        return true
    }
}
