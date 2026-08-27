package com.yokuli.anchorwatch.data
import android.os.SystemClock
import com.yokuli.anchorwatch.data.nmea.*
import com.yokuli.anchorwatch.data.nmea.input.NmeaCandidateMapper
import com.yokuli.anchorwatch.data.nmea.input.ParsedNmeaEnvelope
import com.yokuli.anchorwatch.data.nmea.output.NmeaOutboundLoopGuard
import com.yokuli.anchorwatch.data.vessel.VesselSourceRegistry
import com.yokuli.anchorwatch.domain.model.*
import com.yokuli.anchorwatch.domain.sonar.DepthObservation
import com.yokuli.anchorwatch.location.NmeaFixQualityPolicy
import com.yokuli.anchorwatch.data.condition.LiveDepthRepository
import com.yokuli.anchorwatch.data.condition.LiveWindRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

private val POSITION_SENTENCE_TYPES=setOf("RMC","GGA","GNS","GLL")
private fun nmeaSentenceType(line:String)=line.trim().removePrefix("$").substringBefore('*').substringBefore(',').takeLast(3).uppercase()

data class NmeaInstrumentState(
 val headingTrue:Pair<Double,Long>?=null,
 val headingMagnetic:Pair<Double,Long>?=null,
 val speedOverGroundKnots:Pair<Double,Long>?=null,
 val courseOverGroundTrue:Pair<Double,Long>?=null,
 val speedThroughWaterKnots:Pair<Double,Long>?=null,
 val selectedHeadingSourceId:String?=null,
 val headingCandidates:List<NmeaHeadingCandidate> = emptyList(),
 val headingConflict:Boolean=false,
 val headingConflictDegrees:Double?=null,
 val pinnedHeadingSourceUnavailable:Boolean=false,
)

@Singleton class NavigationRepository @Inject constructor(
 private val liveDepth:LiveDepthRepository,
 private val liveWind:LiveWindRepository,
 private val outboundLoopGuard:NmeaOutboundLoopGuard,
 private val sourceRegistry:VesselSourceRegistry,
){
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);private val parser=Nmea0183Parser();private val updateRetainer=NmeaUpdateRetainer();private val headingResolver=NmeaHeadingResolver();private val connection=NmeaConnectionManager(scope,onGenerationStarted=::resetHeldMeasurements)
 private val requestGuard=Any();private var appConnectionRequested=false;private var backgroundConnectionRequested=false;@Volatile private var userDisconnected=false
 private val _fix=MutableStateFlow<NavigationFix?>(null);val fix=_fix.asStateFlow();val connectionState=connection.state
 private val _recentFixes=MutableStateFlow<List<NavigationFix>>(emptyList());val recentFixes=_recentFixes.asStateFlow()
 private val _diagnostics=MutableStateFlow(NmeaDiagnostics());val diagnostics=_diagnostics.asStateFlow()
 private val _connectionStartedElapsed=MutableStateFlow<Long?>(null);val connectionStartedElapsed=_connectionStartedElapsed.asStateFlow()
 private val _validRawSentences=MutableSharedFlow<String>(extraBufferCapacity=512,onBufferOverflow=kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST);val validRawSentences=_validRawSentences.asSharedFlow()
 private val _parsedEnvelopes=MutableSharedFlow<ParsedNmeaEnvelope>(extraBufferCapacity=256,onBufferOverflow=kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST);val parsedEnvelopes=_parsedEnvelopes.asSharedFlow()
 private val _sourceInvalidations=MutableSharedFlow<NmeaSourceInvalidation>(extraBufferCapacity=64,onBufferOverflow=kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST);val sourceInvalidations=_sourceInvalidations.asSharedFlow()
 private val _depthObservations=MutableSharedFlow<DepthObservation>(extraBufferCapacity=64,onBufferOverflow=kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST);val depthObservations=_depthObservations.asSharedFlow()
 private val _instruments=MutableStateFlow(NmeaInstrumentState());val instruments=_instruments.asStateFlow()
 val transportDiagnostics=connection.diagnostics
 @Volatile private var requireChecksum=true
 @Volatile private var activeProfile=ConnectionProfile()
 private var headingTrue:Pair<Double,Long>?=null;private var headingMag:Pair<Double,Long>?=null;private var depth:Pair<Double,Long>?=null;private var speedThroughWater:Pair<Double,Long>?=null;private var sog:Pair<Double,Long>?=null;private var cog:Pair<Double,Long>?=null;private var hdop:Pair<Double,Long>?=null;private var fixQuality:Pair<Int,Long>?=null;private var satellites:Pair<Int,Long>?=null;private var altitude:Pair<Double,Long>?=null;private val wind=WindSnapshotAccumulator()
 @Volatile private var noDataTimeoutMillis=10_000L
 private var headingSampleSequence=0L
 init{
  scope.launch{connection.lines.collect{accept(it,requireChecksum)}}
  scope.launch{connection.state.collect{state->when(state){NmeaConnectionState.CONNECTING->if(_connectionStartedElapsed.value==null)_connectionStartedElapsed.value=SystemClock.elapsedRealtime();NmeaConnectionState.RECONNECTING->_connectionStartedElapsed.value=SystemClock.elapsedRealtime();else->Unit}}}
  scope.launch{while(isActive){delay(1_000);val now=SystemClock.elapsedRealtime();val last=_diagnostics.value.lastFixElapsed;val started=_connectionStartedElapsed.value;val noFreshFix=started!=null&&now-maxOf(started,last?.takeIf{it>=started}?:started)>noDataTimeoutMillis;if(noFreshFix&&connection.state.value in setOf(NmeaConnectionState.CONNECTED,NmeaConnectionState.CONNECTED_NO_FIX))connection.reportStaleFix()}}
 }
 fun connect(p:ConnectionProfile)=synchronized(requestGuard){userDisconnected=false;appConnectionRequested=true;activeProfile=p;requireChecksum=p.requireChecksum;noDataTimeoutMillis=p.noDataTimeoutSeconds.coerceIn(3,120)*1_000L;val previous=_connectionStartedElapsed.value;_connectionStartedElapsed.value=SystemClock.elapsedRealtime();connection.connect(p).also{if(!it)_connectionStartedElapsed.value=previous}}
 fun reconnect(p:ConnectionProfile)=synchronized(requestGuard){userDisconnected=false;appConnectionRequested=true;activeProfile=p;requireChecksum=p.requireChecksum;noDataTimeoutMillis=p.noDataTimeoutSeconds.coerceIn(3,120)*1_000L;val previous=_connectionStartedElapsed.value;_connectionStartedElapsed.value=SystemClock.elapsedRealtime();connection.reconnect(p).also{if(!it)_connectionStartedElapsed.value=previous}}
 fun writeToBoat(sentences:List<String>)=connection.write(sentences)
 fun writeToBoatExpected(sentences:List<String>,expectedGeneration:Long?)=connection.writeExpected(sentences,expectedGeneration)
 @Synchronized fun pinBoatHeadingSource(sourceId:String?,allowFallback:Boolean=false){headingResolver.pin(sourceId,allowFallback);publishInstruments(SystemClock.elapsedRealtime())}
 fun disconnect()=synchronized(requestGuard){appConnectionRequested=false;if(!backgroundConnectionRequested){userDisconnected=true;connection.disconnect();_connectionStartedElapsed.value=null}}
 /** Acquire the shared NMEA stream for a foreground service without replacing a
  * connection that the user already opened from the Connect page. */
 fun acquireBackgroundConnection(p:ConnectionProfile)=synchronized(requestGuard){
  if(userDisconnected)return@synchronized false
  backgroundConnectionRequested=true;activeProfile=p
  noDataTimeoutMillis=p.noDataTimeoutSeconds.coerceIn(3,120)*1_000L;val previous=_connectionStartedElapsed.value;_connectionStartedElapsed.value=SystemClock.elapsedRealtime();connection.ensureConnected(p).also{started->if(started)requireChecksum=p.requireChecksum else _connectionStartedElapsed.value=previous}
 }
 /** Claims ownership only when the user already has an open NMEA transport.
  * Source selection must never silently start a saved endpoint. */
 fun claimBackgroundConnectionIfConnected():Boolean=synchronized(requestGuard){
  if(userDisconnected||!connection.hasOpenTransport())return@synchronized false
  backgroundConnectionRequested=true
  true
 }
 fun releaseBackgroundConnection()=synchronized(requestGuard){
  backgroundConnectionRequested=false
  if(!appConnectionRequested){connection.disconnect();_connectionStartedElapsed.value=null}
 }
 /** Explicit safety decision: release every owner and close the transport. */
 fun disconnectAll()=synchronized(requestGuard){
  userDisconnected=true
  appConnectionRequested=false
  backgroundConnectionRequested=false
  connection.disconnect()
  _connectionStartedElapsed.value=null
 }
 fun clearUserDisconnectLatch()=synchronized(requestGuard){userDisconnected=false}
 fun setSafetyOwnedRetry(enabled:Boolean)=connection.setSafetyOwnedRetry(enabled)
 fun isUserDisconnected()=userDisconnected
 fun hasOpenTransport()=connection.hasOpenTransport()
 fun activeProfileStableId()=activeProfile.stableId
 fun connectionGeneration()=connection.diagnostics.value.connectionGeneration
 fun clearDiagnostics(){_diagnostics.value=NmeaDiagnostics()}
 fun accept(line:String,requireChecksum:Boolean=true){
  var reportValidFix=false
  synchronized(this){
   val normalized=line.trim();val now=SystemClock.elapsedRealtime();val checksumValid=NmeaChecksum.validate(normalized,requireChecksum);val old=_diagnostics.value;val sentenceType=nmeaSentenceType(normalized);val positionSentence=sentenceType in POSITION_SENTENCE_TYPES
   // Preserve transport diagnostics, but never let an echoed App-generated
   // sentence become independent boat position/heading/wind evidence.
   val echoed=checksumValid&&outboundLoopGuard.isRecentExactOutbound(normalized,now)
   val raw=(old.raw+(if(echoed)"[Echoed App TX] $normalized" else normalized)).takeLast(200)
   if(echoed){_diagnostics.value=old.copy(bytes=old.bytes+line.length+1,validSentences=old.validSentences+1,lastPacketElapsed=now,raw=raw,echoedAppTxSentences=old.echoedAppTxSentences+1,lastPositionRejectionReason=if(positionSentence)"EXACT_APP_TX_ECHO:$sentenceType" else old.lastPositionRejectionReason);return}
   if(checksumValid)_validRawSentences.tryEmit(normalized)
   val parsed=parser.parseEnvelope(normalized,requireChecksum,now)
   if(parsed==null){val checksumBad=line.contains('*')&&!NmeaChecksum.validate(line,false);val reason=if(positionSentence)when{requireChecksum&&!line.contains('*')->"CHECKSUM_REQUIRED:$sentenceType";checksumBad->"CHECKSUM_MISMATCH:$sentenceType";else->"MALFORMED_POSITION:$sentenceType"}else old.lastPositionRejectionReason;_diagnostics.value=old.copy(bytes=old.bytes+line.length+1,invalidSentences=old.invalidSentences+1,checksumErrors=old.checksumErrors+if(checksumBad)1 else 0,lastPacketElapsed=now,raw=raw,lastPositionRejectionReason=reason);return}
   val u=updateRetainer.accept(parsed.update,now,normalized);val envelope=parsed.copy(update=u);_parsedEnvelopes.tryEmit(envelope)
   val generation=connection.diagnostics.value.connectionGeneration
   val fieldHeartbeat=NmeaFieldDecoder.heartbeat(normalized)
   if(!u.holdAllowed||fieldHeartbeat?.allowsHold==false){
    val affected=NmeaInvalidationPolicy.affectedMetrics(envelope.sentenceType)
    if(affected.isNotEmpty()){
     val event=NmeaSourceInvalidation("nmea:${activeProfile.stableId}:$generation:${envelope.fullSentenceId}",affected,NmeaInvalidationReason.EXPLICIT_INVALID_STATUS,now,activeProfile.stableId,generation,envelope.fullSentenceId)
     // The registry can invalidate the exact sentence source. The legacy
     // aggregate fields below cannot: a multiplexed NMEA stream may contain a
     // valid RMC from one GPS and an invalid GGA/GLL from another. Clearing the
     // aggregate here made the map position flicker and could starve Anchor
     // Watch even while a different GPS kept publishing valid fixes. Preserve
     // the last valid aggregate and let its receive timestamp expire normally.
     sourceRegistry.invalidate(event);_sourceInvalidations.tryEmit(event)
    }
   }
   sourceRegistry.publishAll(NmeaCandidateMapper.map(envelope,activeProfile.stableId,generation))
   val headingResolution=headingResolver.accept(u,now)
   val selectedHeading=headingResolution.selected
   val previousHeadingSource=_instruments.value.selectedHeadingSourceId
   headingTrue=selectedHeading?.trueDegrees?.let{it to selectedHeading.receivedElapsedRealtime}
   headingMag=selectedHeading?.magneticDegrees?.let{it to selectedHeading.receivedElapsedRealtime}
   if(selectedHeading?.trueDegrees!=null&&(previousHeadingSource!=selectedHeading.sourceId||selectedHeading.receivedElapsedRealtime==now))headingSampleSequence++
   liveWind.accept(u.copy(trueHeading=selectedHeading?.takeIf{it.receivedElapsedRealtime==now}?.trueDegrees),now)
   if(u.isNumeric(NmeaMetric.DEPTH))u.depthObservation?.let{liveDepth.accept(it);_depthObservations.tryEmit(it)}
   fun measured(metric:NmeaMetric)=u.measuredAt(metric)?:now
   u.depth?.let{depth=it to measured(NmeaMetric.DEPTH)};u.speedThroughWaterKnots?.let{speedThroughWater=it to measured(NmeaMetric.SPEED_THROUGH_WATER)}
   u.sog?.let{sog=it to measured(NmeaMetric.SOG)};u.cog?.let{cog=it to measured(NmeaMetric.COG)}
   // Negative GGA/GNS validity clears its source above. Never write quality 0
   // straight back into the shared cache and poison later valid RMC/GLL fixes.
   if(u.holdAllowed){u.hdop?.let{hdop=it to measured(NmeaMetric.HDOP)};u.fixQuality?.let{fixQuality=it to measured(NmeaMetric.FIX_QUALITY)};u.satellites?.let{satellites=it to measured(NmeaMetric.SATELLITES)};u.position?.altitudeMeters?.let{altitude=it to measured(NmeaMetric.POSITION)}}
   wind.update(u,now)
   _instruments.value=NmeaInstrumentState(headingTrue,headingMag,sog,cog,speedThroughWater,selectedHeading?.sourceId,headingResolution.candidates,headingResolution.conflict,headingResolution.conflictDegrees,headingResolution.pinnedSourceUnavailable)
   u.position?.takeIf{it.valid}?.let{position->
    val freshHeading=headingTrue.fresh(now);val windSnapshot=wind.snapshot(now);val freshTrueDirection=windSnapshot.trueDirectionDegrees;val freshTrueSpeed=windSnapshot.trueSpeedKnots;val freshApparentSpeed=windSnapshot.apparentSpeedKnots;val freshApparentAngle=windSnapshot.apparentAngleDegrees;val freshTrueAngle=windSnapshot.trueAngleDegrees
    val heldHdop=hdop?.first;val heldQuality=fixQuality?.first;val heldSatellites=satellites?.first
    val freshHdop=hdop.fresh(now,5_000)
    // A null field in a valid heartbeat means "unchanged", not "invalid".
    // The same physical sentence source refreshes the held component so an
    // unchanged instrument remains live without borrowing from another source.
    val merged=position.copy(sogKnots=sog?.first,cogTrueDegrees=cog?.first,headingTrueDegrees=headingTrue?.first,headingMagneticDegrees=headingMag?.first,sogReceivedElapsedRealtime=sog?.second,cogReceivedElapsedRealtime=cog?.second,headingReceivedElapsedRealtime=headingTrue?.second,headingMagneticReceivedElapsedRealtime=headingMag?.second,depthMeters=depth?.first,depthReceivedElapsedRealtime=depth?.second,speedThroughWaterKnots=speedThroughWater?.first,speedThroughWaterReceivedElapsedRealtime=speedThroughWater?.second,hdop=heldHdop,fixQuality=heldQuality,satellites=heldSatellites,hdopReceivedElapsedRealtime=hdop?.second,fixQualityReceivedElapsedRealtime=fixQuality?.second,satellitesReceivedElapsedRealtime=satellites?.second,altitudeMeters=altitude?.first,altitudeReceivedElapsedRealtime=altitude?.second,horizontalAccuracyMeters=freshHdop?.times(3.0)?.coerceIn(2.5,80.0),positionProvider=PositionProvider.NMEA,headingSource=if(freshHeading!=null)HeadingSource.NMEA_PHYSICAL else HeadingSource.NONE,headingQuality=if(freshHeading!=null)HeadingQuality.STABLE else HeadingQuality.UNAVAILABLE,windDirectionTrueDegrees=freshTrueDirection,windSpeedKnots=freshTrueSpeed?:freshApparentSpeed,apparentWindAngleDegrees=freshApparentAngle,trueWindAngleDegrees=freshTrueAngle,trueWindSpeedKnots=freshTrueSpeed,apparentWindSpeedKnots=freshApparentSpeed,headingSampleSequence=headingSampleSequence.takeIf{freshHeading!=null},windSampleSequence=windSnapshot.sampleSequence)
    _fix.value=merged
    if(merged.valid){reportValidFix=true;val cutoff=now-10*60_000L;_recentFixes.value=(_recentFixes.value+merged).filter{it.receivedElapsedRealtime>=cutoff}.takeLast(1_200)}
   }
   val positionReason=if(!positionSentence)old.lastPositionRejectionReason else when{
    u.position?.valid==false->"EXPLICIT_NO_FIX:$sentenceType"
    u.position==null->"NO_POSITION_UPDATE:$sentenceType"
    !NmeaFixQualityPolicy.allowsContinuation(_fix.value,now)->when{
     _fix.value?.fixQuality?.takeIf{_fix.value?.fixQualityReceivedElapsedRealtime?.let{received->now-received in 0..NmeaFixQualityPolicy.QUALITY_FRESH_MILLIS}?:true}==0->"FIX_QUALITY_ZERO:$sentenceType"
     (_fix.value?.hdop?:0.0)>5.0&&(_fix.value?.hdopReceivedElapsedRealtime?.let{received->now-received in 0..NmeaFixQualityPolicy.QUALITY_FRESH_MILLIS}?:true)->"POOR_HDOP:${_fix.value?.hdop}"
     else->"QUALITY_REJECTED:$sentenceType"
    }
    else->null
   }
   _diagnostics.value=old.copy(bytes=old.bytes+line.length+1,validSentences=old.validSentences+1,lastPacketElapsed=now,lastFixElapsed=if(u.position?.valid==true)now else old.lastFixElapsed,lastByType=old.lastByType+(u.type to line),raw=raw,lastPositionRejectionReason=positionReason)
  }
  // Never acquire the transport lock while holding the measurement-cache lock:
  // reconnect/disconnect clears that cache as a generation boundary.
  if(reportValidFix)connection.reportValidFix()
 }
 private fun Pair<Double,Long>?.fresh(now:Long)=this?.takeIf{now-it.second<=10_000}?.first
 private fun <T> Pair<T,Long>?.fresh(now:Long,maxAge:Long)=this?.takeIf{now-it.second<=maxAge}?.first
 @Synchronized private fun publishInstruments(now:Long){val resolution=headingResolver.resolve(now);val selected=resolution.selected;headingTrue=selected?.trueDegrees?.let{it to selected.receivedElapsedRealtime};headingMag=selected?.magneticDegrees?.let{it to selected.receivedElapsedRealtime};_instruments.value=NmeaInstrumentState(headingTrue,headingMag,sog,cog,speedThroughWater,selected?.sourceId,resolution.candidates,resolution.conflict,resolution.conflictDegrees,resolution.pinnedSourceUnavailable)}
 @Synchronized private fun resetHeldMeasurements(){updateRetainer.clear();headingResolver.reset();sourceRegistry.clearNmea();headingTrue=null;headingMag=null;depth=null;speedThroughWater=null;sog=null;cog=null;hdop=null;fixQuality=null;satellites=null;altitude=null;headingSampleSequence=0;wind.clear();liveDepth.clear();liveWind.clear();_fix.value=null;_instruments.value=NmeaInstrumentState();_diagnostics.value=_diagnostics.value.copy(lastPacketElapsed=null,lastFixElapsed=null,lastByType=emptyMap(),raw=emptyList(),lastPositionRejectionReason=null)}
}
