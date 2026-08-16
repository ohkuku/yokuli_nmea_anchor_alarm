package com.yokuli.anchorwatch.data
import android.os.SystemClock
import com.yokuli.anchorwatch.data.nmea.*
import com.yokuli.anchorwatch.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton class NavigationRepository @Inject constructor(){
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);private val parser=Nmea0183Parser();private val connection=NmeaConnectionManager(scope)
 private val requestGuard=Any();private var appConnectionRequested=false;private var backgroundConnectionRequested=false
 private val _fix=MutableStateFlow<NavigationFix?>(null);val fix=_fix.asStateFlow();val connectionState=connection.state
 private val _recentFixes=MutableStateFlow<List<NavigationFix>>(emptyList());val recentFixes=_recentFixes.asStateFlow()
 private val _diagnostics=MutableStateFlow(NmeaDiagnostics());val diagnostics=_diagnostics.asStateFlow()
 private val _connectionStartedElapsed=MutableStateFlow<Long?>(null);val connectionStartedElapsed=_connectionStartedElapsed.asStateFlow()
 private val _validRawSentences=MutableSharedFlow<String>(extraBufferCapacity=512,onBufferOverflow=kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST);val validRawSentences=_validRawSentences.asSharedFlow()
 @Volatile private var requireChecksum=true
 private var headingTrue:Pair<Double,Long>?=null;private var headingMag:Pair<Double,Long>?=null;private var depth:Pair<Double,Long>?=null;private var sog:Pair<Double,Long>?=null;private var cog:Pair<Double,Long>?=null;private var windDirectionTrue:Pair<Double,Long>?=null;private var trueWindSpeed:Pair<Double,Long>?=null;private var apparentWindSpeed:Pair<Double,Long>?=null;private var apparentWindAngle:Pair<Double,Long>?=null;private var trueWindAngle:Pair<Double,Long>?=null
 private var headingSampleSequence=0L;private var windSampleSequence=0L
 init{
  scope.launch{connection.lines.collect{accept(it,requireChecksum)}}
  scope.launch{connection.state.collect{state->when(state){NmeaConnectionState.CONNECTING->if(_connectionStartedElapsed.value==null)_connectionStartedElapsed.value=SystemClock.elapsedRealtime();NmeaConnectionState.RECONNECTING->_connectionStartedElapsed.value=SystemClock.elapsedRealtime();else->Unit}}}
 }
 fun connect(p:ConnectionProfile)=synchronized(requestGuard){appConnectionRequested=true;requireChecksum=p.requireChecksum;val previous=_connectionStartedElapsed.value;_connectionStartedElapsed.value=SystemClock.elapsedRealtime();connection.connect(p).also{if(!it)_connectionStartedElapsed.value=previous}}
 fun disconnect()=synchronized(requestGuard){appConnectionRequested=false;if(!backgroundConnectionRequested){connection.disconnect();_connectionStartedElapsed.value=null}}
 /** Acquire the shared NMEA stream for a foreground service without replacing a
  * connection that the user already opened from the Connect page. */
 fun acquireBackgroundConnection(p:ConnectionProfile)=synchronized(requestGuard){
  backgroundConnectionRequested=true
  val previous=_connectionStartedElapsed.value;_connectionStartedElapsed.value=SystemClock.elapsedRealtime();connection.ensureConnected(p).also{started->if(started)requireChecksum=p.requireChecksum else _connectionStartedElapsed.value=previous}
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
  val normalized=line.trim();if(NmeaChecksum.validate(normalized,requireChecksum))_validRawSentences.tryEmit(normalized)
  val now=SystemClock.elapsedRealtime();val u=parser.parse(normalized,requireChecksum,now);val old=_diagnostics.value;val raw=(old.raw+normalized).takeLast(200)
  if(u==null){val checksumBad=line.contains('*')&&!NmeaChecksum.validate(line,false);_diagnostics.value=old.copy(bytes=old.bytes+line.length+1,invalidSentences=old.invalidSentences+1,checksumErrors=old.checksumErrors+if(checksumBad)1 else 0,lastPacketElapsed=now,raw=raw);return}
  if(u.trueHeading!=null){headingTrue=u.trueHeading to now;headingSampleSequence++};u.magneticHeading?.let{headingMag=it to now};u.depth?.let{depth=it to now};u.sog?.let{sog=it to now};u.cog?.let{cog=it to now}
  val newWind=u.trueWindDirection!=null||u.trueWindSpeedKnots!=null||u.apparentWindSpeedKnots!=null||u.apparentWindAngle!=null||u.trueWindAngle!=null
  if(newWind)windSampleSequence++;u.trueWindDirection?.let{windDirectionTrue=it to now};u.trueWindSpeedKnots?.let{trueWindSpeed=it to now};u.apparentWindSpeedKnots?.let{apparentWindSpeed=it to now};u.apparentWindAngle?.let{apparentWindAngle=it to now};u.trueWindAngle?.let{trueWindAngle=it to now}
  u.position?.let{position->
   val freshHeading=headingTrue.fresh(now);val freshTrueDirection=windDirectionTrue.fresh(now);val freshTrueSpeed=trueWindSpeed.fresh(now);val freshApparentSpeed=apparentWindSpeed.fresh(now);val freshApparentAngle=apparentWindAngle.fresh(now);val freshTrueAngle=trueWindAngle.fresh(now)
   val merged=position.copy(sogKnots=sog.fresh(now),cogTrueDegrees=cog.fresh(now),headingTrueDegrees=freshHeading,headingMagneticDegrees=headingMag.fresh(now),depthMeters=depth.fresh(now),horizontalAccuracyMeters=position.hdop?.times(3.0)?.coerceIn(2.5,50.0),positionProvider=PositionProvider.NMEA,headingSource=if(freshHeading!=null)HeadingSource.NMEA_PHYSICAL else HeadingSource.NONE,headingQuality=if(freshHeading!=null)HeadingQuality.STABLE else HeadingQuality.UNAVAILABLE,windDirectionTrueDegrees=freshTrueDirection,windSpeedKnots=freshTrueSpeed?:freshApparentSpeed,apparentWindAngleDegrees=freshApparentAngle,trueWindAngleDegrees=freshTrueAngle,trueWindSpeedKnots=freshTrueSpeed,apparentWindSpeedKnots=freshApparentSpeed,headingSampleSequence=headingSampleSequence.takeIf{freshHeading!=null},windSampleSequence=windSampleSequence.takeIf{freshTrueDirection!=null||freshTrueSpeed!=null||freshApparentSpeed!=null||freshApparentAngle!=null||freshTrueAngle!=null})
   _fix.value=merged
   if(merged.valid){val cutoff=now-10*60_000L;_recentFixes.value=(_recentFixes.value+merged).filter{it.receivedElapsedRealtime>=cutoff}.takeLast(1_200)}
  }
  _diagnostics.value=old.copy(bytes=old.bytes+line.length+1,validSentences=old.validSentences+1,lastPacketElapsed=now,lastFixElapsed=if(u.position?.valid==true)now else old.lastFixElapsed,lastByType=old.lastByType+(u.type to line),raw=raw)
 }
 private fun Pair<Double,Long>?.fresh(now:Long)=this?.takeIf{now-it.second<=10_000}?.first
}
