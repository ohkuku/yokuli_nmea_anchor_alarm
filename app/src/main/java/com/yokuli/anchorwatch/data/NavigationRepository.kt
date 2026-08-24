package com.yokuli.anchorwatch.data
import android.os.SystemClock
import com.yokuli.anchorwatch.data.nmea.*
import com.yokuli.anchorwatch.data.nmea.output.NmeaOutboundLoopGuard
import com.yokuli.anchorwatch.domain.model.*
import com.yokuli.anchorwatch.domain.sonar.DepthObservation
import com.yokuli.anchorwatch.data.condition.LiveDepthRepository
import com.yokuli.anchorwatch.data.condition.LiveWindRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

data class NmeaInstrumentState(
 val headingTrue:Pair<Double,Long>?=null,
 val headingMagnetic:Pair<Double,Long>?=null,
 val speedOverGroundKnots:Pair<Double,Long>?=null,
 val courseOverGroundTrue:Pair<Double,Long>?=null,
 val speedThroughWaterKnots:Pair<Double,Long>?=null,
)

@Singleton class NavigationRepository @Inject constructor(
 private val liveDepth:LiveDepthRepository,
 private val liveWind:LiveWindRepository,
 private val outboundLoopGuard:NmeaOutboundLoopGuard,
){
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);private val parser=Nmea0183Parser();private val connection=NmeaConnectionManager(scope,::resetHeldMeasurements)
 private val requestGuard=Any();private var appConnectionRequested=false;private var backgroundConnectionRequested=false
 private val _fix=MutableStateFlow<NavigationFix?>(null);val fix=_fix.asStateFlow();val connectionState=connection.state
 private val _recentFixes=MutableStateFlow<List<NavigationFix>>(emptyList());val recentFixes=_recentFixes.asStateFlow()
 private val _diagnostics=MutableStateFlow(NmeaDiagnostics());val diagnostics=_diagnostics.asStateFlow()
 private val _connectionStartedElapsed=MutableStateFlow<Long?>(null);val connectionStartedElapsed=_connectionStartedElapsed.asStateFlow()
 private val _validRawSentences=MutableSharedFlow<String>(extraBufferCapacity=512,onBufferOverflow=kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST);val validRawSentences=_validRawSentences.asSharedFlow()
 private val _depthObservations=MutableSharedFlow<DepthObservation>(extraBufferCapacity=64,onBufferOverflow=kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST);val depthObservations=_depthObservations.asSharedFlow()
 private val _instruments=MutableStateFlow(NmeaInstrumentState());val instruments=_instruments.asStateFlow()
 val transportDiagnostics=connection.diagnostics
 @Volatile private var requireChecksum=true
 private var headingTrue:Pair<Double,Long>?=null;private var headingMag:Pair<Double,Long>?=null;private var depth:Pair<Double,Long>?=null;private var speedThroughWater:Pair<Double,Long>?=null;private var sog:Pair<Double,Long>?=null;private var cog:Pair<Double,Long>?=null;private var hdop:Pair<Double,Long>?=null;private var fixQuality:Pair<Int,Long>?=null;private var satellites:Pair<Int,Long>?=null;private var altitude:Pair<Double,Long>?=null;private val wind=WindSnapshotAccumulator()
 @Volatile private var noDataTimeoutMillis=10_000L
 private var headingSampleSequence=0L
 init{
  scope.launch{connection.lines.collect{accept(it,requireChecksum)}}
  scope.launch{connection.state.collect{state->when(state){NmeaConnectionState.CONNECTING->if(_connectionStartedElapsed.value==null)_connectionStartedElapsed.value=SystemClock.elapsedRealtime();NmeaConnectionState.RECONNECTING->_connectionStartedElapsed.value=SystemClock.elapsedRealtime();else->Unit}}}
  scope.launch{while(isActive){delay(1_000);val now=SystemClock.elapsedRealtime();val last=_diagnostics.value.lastFixElapsed;val started=_connectionStartedElapsed.value;val noFreshFix=started!=null&&now-maxOf(started,last?.takeIf{it>=started}?:started)>noDataTimeoutMillis;if(noFreshFix&&connection.state.value in setOf(NmeaConnectionState.CONNECTED,NmeaConnectionState.CONNECTED_NO_FIX))connection.reportStaleFix()}}
 }
 fun connect(p:ConnectionProfile)=synchronized(requestGuard){appConnectionRequested=true;requireChecksum=p.requireChecksum;noDataTimeoutMillis=p.noDataTimeoutSeconds.coerceIn(3,120)*1_000L;val previous=_connectionStartedElapsed.value;_connectionStartedElapsed.value=SystemClock.elapsedRealtime();connection.connect(p).also{if(!it)_connectionStartedElapsed.value=previous}}
 fun reconnect(p:ConnectionProfile)=synchronized(requestGuard){appConnectionRequested=true;requireChecksum=p.requireChecksum;noDataTimeoutMillis=p.noDataTimeoutSeconds.coerceIn(3,120)*1_000L;val previous=_connectionStartedElapsed.value;_connectionStartedElapsed.value=SystemClock.elapsedRealtime();connection.reconnect(p).also{if(!it)_connectionStartedElapsed.value=previous}}
 fun writeToBoat(sentences:List<String>)=connection.write(sentences)
 fun disconnect()=synchronized(requestGuard){appConnectionRequested=false;if(!backgroundConnectionRequested){connection.disconnect();_connectionStartedElapsed.value=null}}
 /** Acquire the shared NMEA stream for a foreground service without replacing a
  * connection that the user already opened from the Connect page. */
 fun acquireBackgroundConnection(p:ConnectionProfile)=synchronized(requestGuard){
  backgroundConnectionRequested=true
  noDataTimeoutMillis=p.noDataTimeoutSeconds.coerceIn(3,120)*1_000L;val previous=_connectionStartedElapsed.value;_connectionStartedElapsed.value=SystemClock.elapsedRealtime();connection.ensureConnected(p).also{started->if(started)requireChecksum=p.requireChecksum else _connectionStartedElapsed.value=previous}
 }
 /** Claims ownership only when the user already has a live NMEA connection.
  * Source selection must never silently start a saved endpoint. */
 fun claimBackgroundConnectionIfConnected():Boolean=synchronized(requestGuard){
  if(connectionState.value!=NmeaConnectionState.CONNECTED)return@synchronized false
  backgroundConnectionRequested=true
  true
 }
 fun releaseBackgroundConnection()=synchronized(requestGuard){
  backgroundConnectionRequested=false
  if(!appConnectionRequested){connection.disconnect();_connectionStartedElapsed.value=null}
 }
 /** Explicit safety decision: release every owner and close the transport. */
 fun disconnectAll()=synchronized(requestGuard){
  appConnectionRequested=false
  backgroundConnectionRequested=false
  connection.disconnect()
  _connectionStartedElapsed.value=null
 }
 fun clearDiagnostics(){_diagnostics.value=NmeaDiagnostics()}
 fun accept(line:String,requireChecksum:Boolean=true){
  var reportValidFix=false
  synchronized(this){
   val normalized=line.trim();val now=SystemClock.elapsedRealtime();val checksumValid=NmeaChecksum.validate(normalized,requireChecksum);val old=_diagnostics.value;val raw=(old.raw+normalized).takeLast(200)
   // Preserve transport diagnostics, but never let an echoed App-generated
   // sentence become independent boat position/heading/wind evidence.
   if(checksumValid&&outboundLoopGuard.isRecentOutbound(normalized,now)){_diagnostics.value=old.copy(bytes=old.bytes+line.length+1,validSentences=old.validSentences+1,lastPacketElapsed=now,raw=raw);return}
   if(checksumValid)_validRawSentences.tryEmit(normalized)
   val u=parser.parse(normalized,requireChecksum,now)
   if(u==null){val checksumBad=line.contains('*')&&!NmeaChecksum.validate(line,false);_diagnostics.value=old.copy(bytes=old.bytes+line.length+1,invalidSentences=old.invalidSentences+1,checksumErrors=old.checksumErrors+if(checksumBad)1 else 0,lastPacketElapsed=now,raw=raw);return}
   liveWind.accept(u,now);u.depthObservation?.let{liveDepth.accept(it)}
   if(u.trueHeading!=null){headingTrue=u.trueHeading to now;headingSampleSequence++};u.magneticHeading?.let{headingMag=it to now};u.depth?.let{depth=it to now};u.depthObservation?.let(_depthObservations::tryEmit);u.speedThroughWaterKnots?.let{speedThroughWater=it to now};u.sog?.let{sog=it to now};u.cog?.let{cog=it to now};u.hdop?.let{hdop=it to now};u.fixQuality?.let{fixQuality=it to now};u.satellites?.let{satellites=it to now};u.position?.altitudeMeters?.let{altitude=it to now}
   wind.update(u,now)
   _instruments.value=NmeaInstrumentState(headingTrue,headingMag,sog,cog,speedThroughWater)
   u.position?.let{position->
    val freshHeading=headingTrue.fresh(now);val windSnapshot=wind.snapshot(now);val freshTrueDirection=windSnapshot.trueDirectionDegrees;val freshTrueSpeed=windSnapshot.trueSpeedKnots;val freshApparentSpeed=windSnapshot.apparentSpeedKnots;val freshApparentAngle=windSnapshot.apparentAngleDegrees;val freshTrueAngle=windSnapshot.trueAngleDegrees
    val heldHdop=hdop?.first;val heldQuality=fixQuality?.first;val heldSatellites=satellites?.first
    val freshHdop=hdop.fresh(now,5_000)
    // A null field in a position sentence means "not updated", not "invalid".
    // Values are held together with their original receive time; every consumer
    // applies its own freshness policy from that timestamp.
    val merged=position.copy(sogKnots=sog?.first,cogTrueDegrees=cog?.first,headingTrueDegrees=headingTrue?.first,headingMagneticDegrees=headingMag?.first,sogReceivedElapsedRealtime=sog?.second,cogReceivedElapsedRealtime=cog?.second,headingReceivedElapsedRealtime=headingTrue?.second,headingMagneticReceivedElapsedRealtime=headingMag?.second,depthMeters=depth?.first,depthReceivedElapsedRealtime=depth?.second,speedThroughWaterKnots=speedThroughWater?.first,speedThroughWaterReceivedElapsedRealtime=speedThroughWater?.second,hdop=heldHdop,fixQuality=heldQuality,satellites=heldSatellites,hdopReceivedElapsedRealtime=hdop?.second,fixQualityReceivedElapsedRealtime=fixQuality?.second,satellitesReceivedElapsedRealtime=satellites?.second,altitudeMeters=altitude?.first,altitudeReceivedElapsedRealtime=altitude?.second,horizontalAccuracyMeters=freshHdop?.times(3.0)?.coerceIn(2.5,80.0),positionProvider=PositionProvider.NMEA,headingSource=if(freshHeading!=null)HeadingSource.NMEA_PHYSICAL else HeadingSource.NONE,headingQuality=if(freshHeading!=null)HeadingQuality.STABLE else HeadingQuality.UNAVAILABLE,windDirectionTrueDegrees=freshTrueDirection,windSpeedKnots=freshTrueSpeed?:freshApparentSpeed,apparentWindAngleDegrees=freshApparentAngle,trueWindAngleDegrees=freshTrueAngle,trueWindSpeedKnots=freshTrueSpeed,apparentWindSpeedKnots=freshApparentSpeed,headingSampleSequence=headingSampleSequence.takeIf{freshHeading!=null},windSampleSequence=windSnapshot.sampleSequence)
    _fix.value=merged
    if(merged.valid){reportValidFix=true;val cutoff=now-10*60_000L;_recentFixes.value=(_recentFixes.value+merged).filter{it.receivedElapsedRealtime>=cutoff}.takeLast(1_200)}
   }
   _diagnostics.value=old.copy(bytes=old.bytes+line.length+1,validSentences=old.validSentences+1,lastPacketElapsed=now,lastFixElapsed=if(u.position?.valid==true)now else old.lastFixElapsed,lastByType=old.lastByType+(u.type to line),raw=raw)
  }
  // Never acquire the transport lock while holding the measurement-cache lock:
  // reconnect/disconnect clears that cache as a generation boundary.
  if(reportValidFix)connection.reportValidFix()
 }
 private fun Pair<Double,Long>?.fresh(now:Long)=this?.takeIf{now-it.second<=10_000}?.first
 private fun <T> Pair<T,Long>?.fresh(now:Long,maxAge:Long)=this?.takeIf{now-it.second<=maxAge}?.first
 @Synchronized private fun resetHeldMeasurements(){headingTrue=null;headingMag=null;depth=null;speedThroughWater=null;sog=null;cog=null;hdop=null;fixQuality=null;satellites=null;altitude=null;headingSampleSequence=0;wind.clear();liveDepth.clear();liveWind.clear();_fix.value=null;_instruments.value=NmeaInstrumentState()}
}
