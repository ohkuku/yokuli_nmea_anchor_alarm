package com.yokuli.anchorwatch.data.nmea

import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.Closeable
import java.io.EOFException
import java.net.*

enum class Protocol { TCP, UDP }
data class ConnectionProfile(val name:String="Boat",val protocol:Protocol=Protocol.TCP,val host:String="",val port:Int=0,val requireChecksum:Boolean=true,val autoReconnect:Boolean=true,val connectAutomatically:Boolean=false,val noDataTimeoutSeconds:Int=10,val stableId:String="boat-primary")

data class NmeaTransportDiagnostics(
 val connectionGeneration:Long=0,
 val connectedAtElapsedRealtime:Long?=null,
 val lastByteReceivedElapsedRealtime:Long?=null,
 val lastSentenceReceivedElapsedRealtime:Long?=null,
 val lastDisconnectReason:String?=null,
 val reconnectAttempt:Int=0,
)

class NmeaConnectionManager(private val scope:CoroutineScope,private val onGenerationStarted:()->Unit={} ) {
 private val guard=Any()
 private var job:Job?=null
 /** Cancels/replaces a connection coroutine. */
 private var generation=0L
 /** Monotonic identity of the actual transport attempt, including auto reconnects. */
 private var transportGeneration=0L
 private var profile:ConnectionProfile?=null
 private var transport:Closeable?=null
 private var lastManualReconnectElapsed=Long.MIN_VALUE
 private val _state=MutableStateFlow(NmeaConnectionState.DISCONNECTED); val state=_state.asStateFlow()
 private val _lines=MutableSharedFlow<String>(extraBufferCapacity=256); val lines=_lines.asSharedFlow()
 private val _diagnostics=MutableStateFlow(NmeaTransportDiagnostics());val diagnostics=_diagnostics.asStateFlow()
 fun connect(p:ConnectionProfile):Boolean=synchronized(guard){
  if(job?.isActive==true&&profile==p)return@synchronized false
  startLocked(p)
 }
 /** Starts [p] only when there is no live connection job. Unlike [connect], this
  * never replaces a connection that another app component is already using. */
 fun ensureConnected(p:ConnectionProfile):Boolean=synchronized(guard){
  if(job?.isActive==true)return@synchronized false
  startLocked(p)
 }
 /** Explicit user reconnect. Unlike [connect], the same profile is replaced.
  * Repeated taps are debounced to avoid creating a generation storm. */
 fun reconnect(p:ConnectionProfile):Boolean=synchronized(guard){
  val now=monotonicMillis()
  if(lastManualReconnectElapsed!=Long.MIN_VALUE&&now-lastManualReconnectElapsed<MANUAL_RECONNECT_DEBOUNCE_MILLIS)return@synchronized false
  lastManualReconnectElapsed=now
  startLocked(p)
 }
 fun write(sentences:List<String>):Boolean{
  val socket=synchronized(guard){transport as? Socket}?:return false
  if(socket.isClosed||!socket.isConnected)return false
  return runCatching{synchronized(socket){val output=socket.getOutputStream();sentences.forEach{line->output.write(line.toByteArray(Charsets.US_ASCII))};output.flush()};true}.getOrDefault(false)
 }
 fun hasOpenTransport():Boolean=synchronized(guard){transport!=null&&job?.isActive==true}
 fun disconnect(){synchronized(guard){generation++;transportGeneration++;onGenerationStarted();profile=null;job?.cancel();job=null;closeTransportLocked();_state.value=NmeaConnectionState.DISCONNECTED;_diagnostics.value=NmeaTransportDiagnostics(connectionGeneration=transportGeneration,lastDisconnectReason="USER_DISCONNECT")}}
 fun reportValidFix(){synchronized(guard){if(job?.isActive==true)_state.value=NmeaConnectionState.CONNECTED}}
 fun reportStaleFix(){synchronized(guard){if(job?.isActive==true&&_state.value!=NmeaConnectionState.CONNECTED_NO_DATA)_state.value=NmeaConnectionState.STALE}}
 private fun startLocked(p:ConnectionProfile):Boolean{
  generation++;val mine=generation;transportGeneration++;onGenerationStarted();job?.cancel();closeTransportLocked();profile=p
  _diagnostics.value=NmeaTransportDiagnostics(connectionGeneration=transportGeneration)
  job=scope.launch(Dispatchers.IO){runConnection(p,mine)}
  return true
 }
 private suspend fun runConnection(p:ConnectionProfile,mine:Long){
  var attempt=0
  try{while(currentCoroutineContext().isActive&&isCurrent(mine)){
   try{if(attempt>0)beginAutomaticTransportGeneration(mine,attempt);setReconnectAttempt(mine,attempt);setState(mine,if(attempt==0)NmeaConnectionState.CONNECTING else NmeaConnectionState.RECONNECTING);val stable={resetAttempt(mine);attempt=0};if(p.protocol==Protocol.TCP)tcp(p,mine,stable)else udp(p,mine,stable);if(!p.autoReconnect)break;throw EOFException("NMEA source closed")}
   catch(e:CancellationException){throw e}
   catch(error:Exception){if(!currentCoroutineContext().isActive||!isCurrent(mine))break;recordDisconnectReason(mine,error);setState(mine,NmeaConnectionState.ERROR);if(!p.autoReconnect)break;val delays=listOf(1000L,2000,5000,10000,15000);delay(delays[attempt.coerceAtMost(delays.lastIndex)]);attempt++}
  }}finally{finish(mine)}
 }
 private suspend fun tcp(p:ConnectionProfile,mine:Long,onStable:()->Unit)=coroutineScope{
  val socket=Socket();register(mine,socket)
  try{
   socket.connect(InetSocketAddress(p.host,p.port),5000)
   // A quiet NMEA stream is not a broken TCP connection. Keep blocking reads;
   // the side watchdog changes presentation state without tearing transport down.
   socket.soTimeout=0
   markConnected(mine)
   val watchdog=launch{noDataWatchdog(p,mine)}
   try{
    val split=NmeaStreamSplitter();val b=ByteArray(4096);var firstByteAt:Long?=null;var stableReported=false
    while(currentCoroutineContext().isActive){
     val n=socket.getInputStream().read(b)
     if(n<0)throw EOFException("NMEA source closed")
     val now=monotonicMillis();if(firstByteAt==null)firstByteAt=now
     markBytes(mine,now);setDataState(mine)
     split.feed(b,n).forEach{line->markSentence(mine,now);_lines.emit(line)}
     if(!stableReported&&now-(firstByteAt?:now)>=STABLE_CONNECTION_RESET_MILLIS){stableReported=true;onStable()}
    }
   }finally{watchdog.cancelAndJoin()}
  }finally{unregister(socket);runCatching{socket.close()}}
 }
 private suspend fun udp(p:ConnectionProfile,mine:Long,onStable:()->Unit){
  val socket=DatagramSocket(null);register(mine,socket)
  try{
   socket.reuseAddress=true;socket.soTimeout=1_000;socket.bind(InetSocketAddress(p.port));markConnected(mine)
   val split=NmeaStreamSplitter();val b=ByteArray(8192);var firstByteAt:Long?=null;var stableReported=false
   while(currentCoroutineContext().isActive){
    val packet=DatagramPacket(b,b.size)
    try{socket.receive(packet)}catch(_:SocketTimeoutException){markNoDataIfExpired(mine,p.noDataTimeoutSeconds);continue}
    val now=monotonicMillis();if(firstByteAt==null)firstByteAt=now;markBytes(mine,now);setDataState(mine)
    split.feed(packet.data,packet.length).forEach{line->markSentence(mine,now);_lines.emit(line)}
    if(!stableReported&&now-(firstByteAt?:now)>=STABLE_CONNECTION_RESET_MILLIS){stableReported=true;onStable()}
   }
  }finally{unregister(socket);runCatching{socket.close()}}
 }
 private suspend fun noDataWatchdog(p:ConnectionProfile,mine:Long){while(currentCoroutineContext().isActive){delay(500);markNoDataIfExpired(mine,p.noDataTimeoutSeconds)}}
 private fun register(mine:Long,value:Closeable){synchronized(guard){if(mine!=generation){runCatching{value.close()};throw CancellationException("Superseded NMEA connection")};transport=value}}
 private fun unregister(value:Closeable){synchronized(guard){if(transport===value)transport=null}}
 private fun closeTransportLocked(){runCatching{transport?.close()};transport=null}
 private fun isCurrent(mine:Long)=synchronized(guard){mine==generation}
 private fun setState(mine:Long,value:NmeaConnectionState){synchronized(guard){if(mine==generation)_state.value=value}}
 private fun setDataState(mine:Long){synchronized(guard){if(mine==generation&&_state.value!=NmeaConnectionState.CONNECTED)_state.value=NmeaConnectionState.CONNECTED_NO_FIX}}
 private fun markConnected(mine:Long){synchronized(guard){if(mine==generation){val now=monotonicMillis();_diagnostics.value=_diagnostics.value.copy(connectedAtElapsedRealtime=now,lastByteReceivedElapsedRealtime=null,lastSentenceReceivedElapsedRealtime=null,lastDisconnectReason=null);_state.value=NmeaConnectionState.CONNECTED_NO_DATA}}}
 private fun markBytes(mine:Long,now:Long){synchronized(guard){if(mine==generation)_diagnostics.value=_diagnostics.value.copy(lastByteReceivedElapsedRealtime=now)}}
 private fun markSentence(mine:Long,now:Long){synchronized(guard){if(mine==generation)_diagnostics.value=_diagnostics.value.copy(lastSentenceReceivedElapsedRealtime=now)}}
 private fun markNoDataIfExpired(mine:Long,timeoutSeconds:Int){synchronized(guard){if(mine!=generation)return;val diagnostic=_diagnostics.value;val reference=diagnostic.lastByteReceivedElapsedRealtime?:diagnostic.connectedAtElapsedRealtime?:return;if(monotonicMillis()-reference>timeoutSeconds.coerceIn(3,120)*1_000L)_state.value=NmeaConnectionState.CONNECTED_NO_DATA}}
 private fun recordDisconnectReason(mine:Long,error:Throwable){synchronized(guard){if(mine==generation)_diagnostics.value=_diagnostics.value.copy(lastDisconnectReason=error.javaClass.simpleName+(error.message?.let{": $it"}?:""))}}
 private fun setReconnectAttempt(mine:Long,value:Int){synchronized(guard){if(mine==generation)_diagnostics.value=_diagnostics.value.copy(reconnectAttempt=value)}}
 private fun beginAutomaticTransportGeneration(mine:Long,attempt:Int){synchronized(guard){if(mine!=generation)return;transportGeneration++;onGenerationStarted();_diagnostics.value=NmeaTransportDiagnostics(connectionGeneration=transportGeneration,reconnectAttempt=attempt,lastDisconnectReason=_diagnostics.value.lastDisconnectReason)}}
 private fun resetAttempt(mine:Long){setReconnectAttempt(mine,0)}
 private fun finish(mine:Long){synchronized(guard){if(mine==generation){closeTransportLocked();profile=null;job=null;_state.value=NmeaConnectionState.DISCONNECTED}}}
 private fun monotonicMillis()=System.nanoTime()/1_000_000L
 companion object{const val MANUAL_RECONNECT_DEBOUNCE_MILLIS=900L;const val STABLE_CONNECTION_RESET_MILLIS=10_000L}
}
