package com.yokuli.anchorwatch.location

import com.yokuli.anchorwatch.domain.model.FixTrust
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.domain.model.PositionProvider

data class AcceptedAnchorPositionReadiness(
    val ready:Boolean,
    val fix:NavigationFix?=null,
    val reason:String,
    val evidence:String,
)

/**
 * One safety boundary shared by Setup and Runtime. A provider fix is not an
 * anchor origin merely because it is visible on the map: it must have passed
 * the process-wide integrity repository and still belong to the selected live
 * source (and, for NMEA, the current transport generation).
 */
object AcceptedAnchorPositionPolicy {
    fun evaluate(
        state:AcceptedPositionState,
        requestedSource:GpsDataSource,
        nowElapsedRealtime:Long,
        maximumAgeMillis:Long,
        nmeaConnection:NmeaConnectionState,
        nmeaConnectionStartedElapsedRealtime:Long?,
        nmeaConnectionGeneration:Long,
        systemGpsBlocked:Boolean=false,
    ):AcceptedAnchorPositionReadiness {
        fun no(reason:String,evidence:String=reason)=AcceptedAnchorPositionReadiness(false,reason=reason,evidence=evidence)
        if(state.selectedSource!=requestedSource)return no("SOURCE_MISMATCH","Accepted source is ${state.selectedSource.name}; selected source is ${requestedSource.name}.")
        if(state.disposition!="ACCEPTED")return no("POSITION_${state.disposition}","Integrity disposition: ${state.disposition}${state.reason?.let{" · $it"}.orEmpty()}")
        if(state.trust in setOf(FixTrust.QUARANTINED,FixTrust.REJECTED))return no("POSITION_${state.trust}")
        val fix=state.acceptedFix?:return no("NO_ACCEPTED_POSITION","No position has passed integrity checks yet.")
        if(!fix.valid||!fix.latitude.isFinite()||!fix.longitude.isFinite()||fix.latitude !in -90.0..90.0||fix.longitude !in -180.0..180.0)return no("INVALID_ACCEPTED_POSITION")
        val age=nowElapsedRealtime-fix.receivedElapsedRealtime
        if(age !in 0L until maximumAgeMillis.coerceAtLeast(1L))return no("ACCEPTED_POSITION_STALE","Accepted position age: ${age.coerceAtLeast(0L)} ms.")
        return when(requestedSource){
            GpsDataSource.NMEA->{
                if(state.acceptedConnectionGeneration!=nmeaConnectionGeneration)return no("NMEA_CONNECTION_GENERATION_MISMATCH","Accepted generation ${state.acceptedConnectionGeneration?:"none"}; live generation $nmeaConnectionGeneration.")
                if(!NmeaSourceSelectionPolicy.isUsablePosition(nmeaConnection,fix,nmeaConnectionStartedElapsedRealtime,nowElapsedRealtime,maximumAgeMillis))return no("NMEA_POSITION_NOT_CURRENT_OR_QUALIFIED")
                AcceptedAnchorPositionReadiness(true,fix,"READY","Accepted NMEA position · generation $nmeaConnectionGeneration · age ${age} ms${fix.hdop?.let{" · HDOP $it"}.orEmpty()}")
            }
            GpsDataSource.SYSTEM->{
                if(systemGpsBlocked)return no("SYSTEM_GPS_BLOCKED_BY_PROXY")
                if(fix.positionProvider!=PositionProvider.ANDROID_GNSS)return no("SYSTEM_PROVIDER_NOT_GNSS","Provider is ${fix.positionProvider.name}; precise Android GNSS is required.")
                if(fix.isMockLocation)return no("MOCK_SYSTEM_POSITION")
                val accuracy=fix.horizontalAccuracyMeters?:Double.POSITIVE_INFINITY
                if(accuracy>30.0)return no("SYSTEM_ACCURACY_TOO_LOW","GNSS accuracy is ${if(accuracy.isFinite())"%.1f m".format(accuracy) else "unknown"}; 30 m or better is required.")
                AcceptedAnchorPositionReadiness(true,fix,"READY","Accepted Android GNSS · age ${age} ms · accuracy ${"%.1f m".format(accuracy)}")
            }
            GpsDataSource.DEMO->{
                if(fix.positionProvider!=PositionProvider.DEMO)return no("DEMO_POSITION_NOT_RUNNING")
                AcceptedAnchorPositionReadiness(true,fix,"READY","Accepted Demo position · age ${age} ms")
            }
        }
    }
}
