package com.yokuli.anchorwatch.testsupport

import com.yokuli.anchorwatch.data.nmea.Nmea0183Parser
import com.yokuli.anchorwatch.domain.anchor.AlarmEngine
import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import com.yokuli.anchorwatch.domain.model.AlarmSnapshot
import com.yokuli.anchorwatch.domain.model.AnchorConfig
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.PositionProvider
import com.yokuli.anchorwatch.location.PositionIntegrityFilter
import com.yokuli.anchorwatch.location.PositionIntegrityResult
import com.yokuli.anchorwatch.location.MockGpsPolicy
import com.yokuli.anchorwatch.domain.sonar.DepthCandidate
import com.yokuli.anchorwatch.domain.sonar.DepthDisposition
import com.yokuli.anchorwatch.domain.sonar.DepthIntegrityFilter
import com.yokuli.anchorwatch.runtime.health.BatteryHealthPolicy
import com.yokuli.anchorwatch.runtime.MonotonicClock
import com.yokuli.anchorwatch.runtime.WallClock

class FakeClock(var elapsed:Long=0,var wall:Long=1_700_000_000_000L):MonotonicClock,WallClock{
    override fun elapsedRealtime()=elapsed
    override fun currentTimeMillis()=wall
    fun advance(millis:Long){elapsed+=millis;wall+=millis}
}

sealed interface ScenarioEvent{
    data class Position(val northMeters:Double,val eastMeters:Double=0.0,val accuracyMeters:Double=3.0):ScenarioEvent
    data class GpsSpike(val northMeters:Double,val eastMeters:Double=0.0):ScenarioEvent
    data class NmeaLine(val line:String):ScenarioEvent
    data class Advance(val millis:Long):ScenarioEvent
    data object GpsDropoutTick:ScenarioEvent
    data class TimestampReversal(val northMeters:Double=0.0):ScenarioEvent
    data object NmeaDisconnect:ScenarioEvent
    data object NmeaReconnect:ScenarioEvent
    data class NoBytes(val millis:Long):ScenarioEvent
    data class Depth(val meters:Double,val northMeters:Double=0.0,val eastMeters:Double=0.0):ScenarioEvent
    data class DepthSpike(val meters:Double,val northMeters:Double=0.0,val eastMeters:Double=0.0):ScenarioEvent
    data object SonarStale:ScenarioEvent
    data class Battery(val percent:Int):ScenarioEvent
    data object ScreenOff:ScenarioEvent
    data object ScreenOn:ScenarioEvent
    data class SharingClients(val count:Int):ScenarioEvent
    data object ProxyStart:ScenarioEvent
    data object ProxyPermissionRevoked:ScenarioEvent
    data object ProxyWatchdog:ScenarioEvent
    data object ProcessRestart:ScenarioEvent
}

data class ScenarioResult(
    val alarms:List<AlarmSnapshot>,val acceptedFixes:Long,val quarantinedFixes:Long,val rejectedFixes:Long,val parsedLines:Long,
    val nmeaDisconnects:Long,val nmeaReconnects:Long,val depthAccepted:Long,val depthQuarantined:Long,val depthRejected:Long,
    val lowBatteryWarnings:Long,val screenOn:Boolean,val sharingClients:Int,val proxyActive:Boolean,val proxyStaleStops:Long,
    val watchPausedAfterRestart:Boolean,
)

/** Deterministic safety-chain driver; it never bypasses production filters. */
class FaultScenarioRunner(
    private val clock:FakeClock=FakeClock(),
    private val originLatitude:Double=-36.8485,
    private val originLongitude:Double=174.7633,
){
    private var filter=PositionIntegrityFilter()
    private val parser=Nmea0183Parser()
    private val config=AnchorConfig(originLatitude,originLongitude,0.0,warningRadiusMeters=40.0,alarmRadiusMeters=50.0)
    private var alarm=AlarmEngine(persistenceMillis=8_000,requiredFixes=3,gpsLossMillis=15_000,clock=clock).also{it.arm(config)}
    private var depthFilter=DepthIntegrityFilter();private val battery=BatteryHealthPolicy();private var proxyPolicy:MockGpsPolicy?=null
    private val alarms=ArrayDeque<AlarmSnapshot>();private var accepted=0L;private var quarantined=0L;private var rejected=0L;private var parsed=0L
    private var nmeaConnected=true;private var disconnects=0L;private var reconnects=0L;private var acceptedDepth=0L;private var quarantinedDepth=0L;private var rejectedDepth=0L
    private var lowBatteryWarnings=0L;private var screenOn=true;private var sharingClients=0;private var proxyActive=false;private var proxyStaleStops=0L;private var watchPausedAfterRestart=false

    fun run(events:Iterable<ScenarioEvent>):ScenarioResult{events.forEach(::accept);return ScenarioResult(alarms.toList(),accepted,quarantined,rejected,parsed,disconnects,reconnects,acceptedDepth,quarantinedDepth,rejectedDepth,lowBatteryWarnings,screenOn,sharingClients,proxyActive,proxyStaleStops,watchPausedAfterRestart)}
    private fun accept(event:ScenarioEvent){when(event){
        is ScenarioEvent.Advance->clock.advance(event.millis)
        ScenarioEvent.GpsDropoutTick->record(alarm.tick(clock.elapsedRealtime()))
        is ScenarioEvent.NmeaLine->{if(parser.parse(event.line,true,clock.elapsedRealtime())!=null)parsed++}
        is ScenarioEvent.Position->position(event.northMeters,event.eastMeters,event.accuracyMeters)
        is ScenarioEvent.GpsSpike->position(event.northMeters,event.eastMeters,3.0)
        is ScenarioEvent.TimestampReversal->position(event.northMeters,0.0,3.0,clock.wall-10_000)
        ScenarioEvent.NmeaDisconnect->{nmeaConnected=false;disconnects++}
        ScenarioEvent.NmeaReconnect->{if(!nmeaConnected)reconnects++;nmeaConnected=true}
        is ScenarioEvent.NoBytes->{clock.advance(event.millis);if(event.millis>=3_000){nmeaConnected=false;disconnects++}}
        is ScenarioEvent.Depth->depth(event.meters,event.northMeters,event.eastMeters)
        is ScenarioEvent.DepthSpike->depth(event.meters,event.northMeters,event.eastMeters)
        ScenarioEvent.SonarStale->clock.advance(2_001)
        is ScenarioEvent.Battery->if(battery.update(event.percent,true).newlyLow)lowBatteryWarnings++
        ScenarioEvent.ScreenOff->screenOn=false
        ScenarioEvent.ScreenOn->screenOn=true
        is ScenarioEvent.SharingClients->sharingClients=event.count.coerceAtLeast(0)
        ScenarioEvent.ProxyStart->{proxyPolicy=MockGpsPolicy(15_000,1).also{it.start(clock.elapsedRealtime())};proxyActive=true}
        ScenarioEvent.ProxyPermissionRevoked->{proxyPolicy=null;proxyActive=false}
        ScenarioEvent.ProxyWatchdog->{if(proxyActive&&proxyPolicy?.isStale(clock.elapsedRealtime())==true){proxyActive=false;proxyPolicy=null;proxyStaleStops++}}
        ScenarioEvent.ProcessRestart->{filter=PositionIntegrityFilter();depthFilter=DepthIntegrityFilter();alarm=AlarmEngine(persistenceMillis=8_000,requiredFixes=3,gpsLossMillis=15_000,clock=clock).also{it.arm(config)};proxyPolicy=null;proxyActive=false;sharingClients=0;watchPausedAfterRestart=true}
    }}
    private fun position(north:Double,east:Double,accuracy:Double,sourceTimestamp:Long=clock.wall){
        val point=AnchorGeometry.project(originLatitude,originLongitude,east,north)
        val fix=NavigationFix(point.first,point.second,timestampUtcMillis=sourceTimestamp,receivedElapsedRealtime=clock.elapsedRealtime(),horizontalAccuracyMeters=accuracy,positionProvider=PositionProvider.NMEA,sourceSentence="FAULT_SCENARIO",valid=true)
        when(val result=filter.evaluate(fix)){
            is PositionIntegrityResult.Accepted->result.fixes.forEach{accepted++;proxyPolicy?.onValidFix(clock.elapsedRealtime());record(alarm.onFix(it.fix,clock.elapsedRealtime()))}
            is PositionIntegrityResult.Quarantined->quarantined++
            is PositionIntegrityResult.Rejected->rejected++
        }
    }
    private fun depth(meters:Double,north:Double,east:Double){
        val point=AnchorGeometry.project(originLatitude,originLongitude,east,north)
        when(depthFilter.evaluate(DepthCandidate(point.first,point.second,clock.elapsedRealtime(),meters,meters,3.0)).disposition){
            DepthDisposition.ACCEPTED,DepthDisposition.ACCEPTED_STEEP_SLOPE->acceptedDepth++
            DepthDisposition.QUARANTINED_SPIKE->quarantinedDepth++
            else->rejectedDepth++
        }
    }
    private fun record(snapshot:AlarmSnapshot){if(snapshot.state==com.yokuli.anchorwatch.domain.model.AlarmState.ALARM){alarms+=snapshot;while(alarms.size>1_024)alarms.removeFirst()}}
}
