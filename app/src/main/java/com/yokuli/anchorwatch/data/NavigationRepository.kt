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
 @Volatile private var requireChecksum=true
 private var headingTrue:Pair<Double,Long>?=null;private var headingMag:Pair<Double,Long>?=null;private var depth:Pair<Double,Long>?=null;private var sog:Pair<Double,Long>?=null;private var cog:Pair<Double,Long>?=null
 init{scope.launch{connection.lines.collect{accept(it,requireChecksum)}}}
 fun connect(p:ConnectionProfile)=synchronized(requestGuard){appConnectionRequested=true;requireChecksum=p.requireChecksum;connection.connect(p)}
 fun disconnect()=synchronized(requestGuard){appConnectionRequested=false;if(!backgroundConnectionRequested)connection.disconnect()}
 /** Acquire the shared NMEA stream for a foreground service without replacing a
  * connection that the user already opened from the Connect page. */
 fun acquireBackgroundConnection(p:ConnectionProfile)=synchronized(requestGuard){
  backgroundConnectionRequested=true
  connection.ensureConnected(p).also{started->if(started)requireChecksum=p.requireChecksum}
 }
 fun releaseBackgroundConnection()=synchronized(requestGuard){
  backgroundConnectionRequested=false
  if(!appConnectionRequested)connection.disconnect()
 }
 /** Explicit safety decision: release every owner and close the transport. */
 fun disconnectAll()=synchronized(requestGuard){
  appConnectionRequested=false
  backgroundConnectionRequested=false
  connection.disconnect()
 }
 fun clearDiagnostics(){_diagnostics.value=NmeaDiagnostics()}
 fun accept(line:String,requireChecksum:Boolean=true){
  val now=SystemClock.elapsedRealtime();val u=parser.parse(line,requireChecksum,now);val old=_diagnostics.value;val raw=(old.raw+line).takeLast(200)
  if(u==null){val checksumBad=line.contains('*')&&!NmeaChecksum.validate(line,false);_diagnostics.value=old.copy(bytes=old.bytes+line.length+1,invalidSentences=old.invalidSentences+1,checksumErrors=old.checksumErrors+if(checksumBad)1 else 0,lastPacketElapsed=now,raw=raw);return}
  u.trueHeading?.let{headingTrue=it to now};u.magneticHeading?.let{headingMag=it to now};u.depth?.let{depth=it to now};u.sog?.let{sog=it to now};u.cog?.let{cog=it to now}
  u.position?.let{position->
   val merged=position.copy(sogKnots=sog.fresh(now),cogTrueDegrees=cog.fresh(now),headingTrueDegrees=headingTrue.fresh(now),headingMagneticDegrees=headingMag.fresh(now),depthMeters=depth.fresh(now))
   _fix.value=merged
   if(merged.valid){val cutoff=now-10*60_000L;_recentFixes.value=(_recentFixes.value+merged).filter{it.receivedElapsedRealtime>=cutoff}.takeLast(1_200)}
  }
  _diagnostics.value=old.copy(bytes=old.bytes+line.length+1,validSentences=old.validSentences+1,lastPacketElapsed=now,lastFixElapsed=if(u.position?.valid==true)now else old.lastFixElapsed,lastByType=old.lastByType+(u.type to line),raw=raw)
 }
 private fun Pair<Double,Long>?.fresh(now:Long)=this?.takeIf{now-it.second<=10_000}?.first
}
