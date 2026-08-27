package com.yokuli.anchorwatch.data.nmea

import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.Closeable
import java.io.EOFException
import java.net.*

enum class Protocol { TCP, UDP }
data class ConnectionProfile(val name:String="Boat",val protocol:Protocol=Protocol.TCP,val host:String="",val port:Int=0,val requireChecksum:Boolean=true,val autoReconnect:Boolean=false,val connectAutomatically:Boolean=false,val noDataTimeoutSeconds:Int=10,val stableId:String="boat-primary")

data class NmeaConnectionRetryPolicy(
 val openFailureRetryMillis:Long=15_000L,
 val peerDisconnectRetryMillis:Long=15_000L,
 val reconnectCoalesceMillis:Long=1_500L,
 val manualReconnectCooldownMillis:Long=15_000L,
 val maxContinuousFailures:Int=3,
)

data class NmeaTransportDiagnostics(
 val connectionGeneration:Long=0,
 val connectedAtElapsedRealtime:Long?=null,
 val lastByteReceivedElapsedRealtime:Long?=null,
 val lastSentenceReceivedElapsedRealtime:Long?=null,
 val lastDisconnectReason:String?=null,
 val lastFailureCategory:String?=null,
 val lastFailureElapsedRealtime:Long?=null,
 val reconnectAttempt:Int=0,
 val nextRetryElapsedRealtime:Long?=null,
 val circuitOpen:Boolean=false,
 val desiredConnected:Boolean=false,
 val lastOperation:String="IDLE",
 val safetyOwnedRetry:Boolean=false,
 val retryPolicyName:String="IDLE_BOUNDED",
)

object NmeaSafetyRetryPolicy{
 private val sequence=listOf(2_000L,5_000L,10_000L,15_000L,30_000L)
 fun delayMillis(attempt:Int)=sequence[(attempt-1).coerceIn(0,sequence.lastIndex)]
}

enum class NmeaTransportWriteFailure { NONE, TRANSPORT_UNAVAILABLE, STALE_TRANSPORT_GENERATION, WRITE_FAILED }

data class NmeaTransportWriteResult(
 val success:Boolean,
 val expectedGeneration:Long?,
 val actualGeneration:Long,
 val attemptedSentenceCount:Int,
 val writtenSentenceCount:Int=0,
 val failure:NmeaTransportWriteFailure=if(success)NmeaTransportWriteFailure.NONE else NmeaTransportWriteFailure.WRITE_FAILED,
 val error:String?=null,
)

/** A complete scheduler tick becomes one contiguous socket payload. */
object NmeaWireBatch{
 fun encode(sentences:List<String>):ByteArray=sentences.joinToString(separator="").toByteArray(Charsets.US_ASCII)
}

class NmeaConnectionManager(
 private val scope:CoroutineScope,
 private val retryPolicy:NmeaConnectionRetryPolicy=NmeaConnectionRetryPolicy(),
 private val onGenerationStarted:()->Unit={},
) {
 private val guard=Any()
 private var job:Job?=null
 /** Cancels/replaces a connection coroutine. */
 private var generation=0L
 /** Monotonic identity of the actual transport attempt, including auto reconnects. */
 private var transportGeneration=0L
 private var profile:ConnectionProfile?=null
 private var transport:Closeable?=null
 private var lastManualReconnectElapsed=Long.MIN_VALUE
 @Volatile private var safetyOwnedRetry=false
 private val _state=MutableStateFlow(NmeaConnectionState.DISCONNECTED); val state=_state.asStateFlow()
 private val _lines=MutableSharedFlow<String>(extraBufferCapacity=256); val lines=_lines.asSharedFlow()
 private val _diagnostics=MutableStateFlow(NmeaTransportDiagnostics());val diagnostics=_diagnostics.asStateFlow()
 fun setSafetyOwnedRetry(enabled:Boolean)=synchronized(guard){safetyOwnedRetry=enabled;_diagnostics.value=_diagnostics.value.copy(safetyOwnedRetry=enabled,retryPolicyName=if(enabled)"SAFETY_CONTINUOUS_BOUNDED" else "IDLE_BOUNDED")}
 fun connect(p:ConnectionProfile):Boolean=synchronized(guard){
  // Connect is the one live transport attempt, not a disposable test followed
  // by a second socket. Even a coroutine already reporting ERROR must finish
  // closing its transport before another generation is allowed to start.
  if(job?.isActive==true)return@synchronized false
  startLocked(p,operation="USER_CONNECT")
 }
 /** Starts [p] only when there is no live connection job. Unlike [connect], this
  * never replaces a connection that another app component is already using. */
 fun ensureConnected(p:ConnectionProfile):Boolean=synchronized(guard){
  if(job?.isActive==true)return@synchronized false
  startLocked(p,operation="BACKGROUND_ACQUIRE")
 }
 /** Explicit user reconnect. Unlike [connect], the same profile is replaced.
  * Repeated taps are debounced to avoid creating a generation storm. */
 fun reconnect(p:ConnectionProfile):Boolean=synchronized(guard){
  val now=monotonicMillis()
  if(lastManualReconnectElapsed!=Long.MIN_VALUE&&now-lastManualReconnectElapsed<retryPolicy.manualReconnectCooldownMillis)return@synchronized false
  lastManualReconnectElapsed=now
  startLocked(p,retryPolicy.reconnectCoalesceMillis,NmeaConnectionState.RECONNECTING,"USER_RECONNECT")
 }
 fun write(sentences:List<String>):Boolean=writeExpected(sentences,null).success
 /** Writes only to the transport generation that produced the queued batch.
  * A reconnect must never make an old batch appear on the replacement socket. */
 fun writeExpected(sentences:List<String>,expectedGeneration:Long?):NmeaTransportWriteResult{
  val lease=synchronized(guard){
   val actual=transportGeneration
   if(expectedGeneration!=null&&expectedGeneration!=actual)return NmeaTransportWriteResult(false,expectedGeneration,actual,sentences.size,failure=NmeaTransportWriteFailure.STALE_TRANSPORT_GENERATION,error="Queued NMEA batch belongs to transport generation $expectedGeneration; current generation is $actual.")
   val socket=transport as? Socket?:return NmeaTransportWriteResult(false,expectedGeneration,actual,sentences.size,failure=NmeaTransportWriteFailure.TRANSPORT_UNAVAILABLE,error="The shared input TCP transport is not open.")
   socket to actual
  }
  val (socket,actual)=lease
  if(socket.isClosed||!socket.isConnected)return NmeaTransportWriteResult(false,expectedGeneration,actual,sentences.size,failure=NmeaTransportWriteFailure.TRANSPORT_UNAVAILABLE,error="The shared input TCP transport is closed.")
  return try{
   synchronized(socket){
    val stillCurrent=synchronized(guard){transport===socket&&transportGeneration==actual&&(expectedGeneration==null||expectedGeneration==actual)}
    if(!stillCurrent)return NmeaTransportWriteResult(false,expectedGeneration,synchronized(guard){transportGeneration},sentences.size,failure=NmeaTransportWriteFailure.STALE_TRANSPORT_GENERATION,error="The input TCP transport changed before this queued batch could be written.")
    val output=socket.getOutputStream()
    // One scheduler tick is one wire payload. Multiple per-sentence writes and
    // flushes amplify backpressure on small marine Wi-Fi/serial gateways.
    output.write(NmeaWireBatch.encode(sentences));output.flush()
   }
   NmeaTransportWriteResult(true,expectedGeneration,actual,sentences.size,sentences.size)
  }catch(error:Exception){
   NmeaTransportWriteResult(false,expectedGeneration,synchronized(guard){transportGeneration},sentences.size,failure=NmeaTransportWriteFailure.WRITE_FAILED,error=error.javaClass.simpleName+(error.message?.let{": $it"}.orEmpty()))
  }
 }
 fun hasOpenTransport():Boolean=synchronized(guard){transport!=null&&job?.isActive==true}
 fun disconnect(){synchronized(guard){generation++;transportGeneration++;onGenerationStarted();profile=null;job?.cancel();job=null;closeTransportLocked();_state.value=NmeaConnectionState.DISCONNECTED;_diagnostics.value=NmeaTransportDiagnostics(connectionGeneration=transportGeneration,lastDisconnectReason="USER_DISCONNECT",desiredConnected=false,lastOperation="USER_DISCONNECT")}}
 fun reportValidFix(){synchronized(guard){if(job?.isActive==true)_state.value=NmeaConnectionState.CONNECTED}}
 fun reportStaleFix(){synchronized(guard){if(job?.isActive==true&&_state.value!=NmeaConnectionState.CONNECTED_NO_DATA)_state.value=NmeaConnectionState.STALE}}
 private fun startLocked(p:ConnectionProfile,delayBeforeOpenMillis:Long=0L,initialState:NmeaConnectionState=NmeaConnectionState.CONNECTING,operation:String):Boolean{
  generation++;val mine=generation;transportGeneration++;onGenerationStarted();job?.cancel();closeTransportLocked();profile=p
  _diagnostics.value=NmeaTransportDiagnostics(connectionGeneration=transportGeneration,desiredConnected=true,lastOperation=operation)
  _state.value=initialState
  job=scope.launch(Dispatchers.IO){if(delayBeforeOpenMillis>0)delay(delayBeforeOpenMillis);runConnection(p,mine)}
  return true
 }
 private suspend fun runConnection(p:ConnectionProfile,mine:Long){
  var attempt=0;var continuousFailures=0
  try{while(currentCoroutineContext().isActive&&isCurrent(mine)){
   var opened=false
   try{
    if(attempt>0)beginAutomaticTransportGeneration(mine,attempt)
    setReconnectAttempt(mine,continuousFailures);clearScheduledRetry(mine)
    setState(mine,if(attempt==0)NmeaConnectionState.CONNECTING else NmeaConnectionState.RECONNECTING)
    val stable={continuousFailures=0;attempt=0;resetAttempt(mine)}
    val connected={opened=true}
    if(p.protocol==Protocol.TCP)tcp(p,mine,stable,connected)else udp(p,mine,stable,connected)
    throw EOFException("NMEA source closed")
   }
   catch(e:CancellationException){throw e}
   catch(error:Exception){
    if(!currentCoroutineContext().isActive||!isCurrent(mine))break
    continuousFailures++
    recordDisconnectReason(mine,error,continuousFailures)
    val safetyRetry=safetyOwnedRetry
    if(!safetyRetry&&!p.autoReconnect){stopAfterFailure(mine,continuousFailures);break}
    if(!safetyRetry&&continuousFailures>=retryPolicy.maxContinuousFailures){openCircuit(mine,continuousFailures);break}
    val retryDelay=if(safetyRetry)NmeaSafetyRetryPolicy.delayMillis(continuousFailures) else if(opened)retryPolicy.peerDisconnectRetryMillis else retryPolicy.openFailureRetryMillis
    scheduleRetry(mine,retryDelay,continuousFailures);setState(mine,NmeaConnectionState.RECONNECTING)
    delay(retryDelay);attempt++
   }
  }}finally{finish(mine)}
 }
 private suspend fun tcp(p:ConnectionProfile,mine:Long,onStable:()->Unit,onConnected:()->Unit)=coroutineScope{
  val socket=Socket();register(mine,socket)
  try{
   socket.connect(InetSocketAddress(p.host,p.port),5000)
   // A quiet NMEA stream is not a broken TCP connection. Keep blocking reads;
   // the side watchdog changes presentation state without tearing transport down.
   socket.soTimeout=0
   onConnected();markConnected(mine)
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
 private suspend fun udp(p:ConnectionProfile,mine:Long,onStable:()->Unit,onConnected:()->Unit){
  val socket=DatagramSocket(null);register(mine,socket)
  try{
   socket.reuseAddress=true;socket.soTimeout=1_000;socket.bind(InetSocketAddress(p.port));onConnected();markConnected(mine)
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
 private fun markConnected(mine:Long){synchronized(guard){if(mine==generation){val now=monotonicMillis();_diagnostics.value=_diagnostics.value.copy(connectedAtElapsedRealtime=now,lastByteReceivedElapsedRealtime=null,lastSentenceReceivedElapsedRealtime=null,lastDisconnectReason=null,lastFailureCategory=null,lastFailureElapsedRealtime=null,reconnectAttempt=0,nextRetryElapsedRealtime=null,circuitOpen=false,desiredConnected=true);_state.value=NmeaConnectionState.CONNECTED_NO_DATA}}}
 private fun markBytes(mine:Long,now:Long){synchronized(guard){if(mine==generation)_diagnostics.value=_diagnostics.value.copy(lastByteReceivedElapsedRealtime=now)}}
 private fun markSentence(mine:Long,now:Long){synchronized(guard){if(mine==generation)_diagnostics.value=_diagnostics.value.copy(lastSentenceReceivedElapsedRealtime=now)}}
 private fun markNoDataIfExpired(mine:Long,timeoutSeconds:Int){synchronized(guard){if(mine!=generation)return;val diagnostic=_diagnostics.value;val reference=diagnostic.lastByteReceivedElapsedRealtime?:diagnostic.connectedAtElapsedRealtime?:return;if(monotonicMillis()-reference>timeoutSeconds.coerceIn(3,120)*1_000L)_state.value=NmeaConnectionState.CONNECTED_NO_DATA}}
 private fun recordDisconnectReason(mine:Long,error:Throwable,failures:Int){synchronized(guard){
 if(mine!=generation)return@synchronized
  val current=_diagnostics.value
  _diagnostics.value=current.copy(
   lastDisconnectReason=error.javaClass.simpleName+(error.message?.let{": $it"}?:""),
   lastFailureCategory=failureCategory(error),
   lastFailureElapsedRealtime=monotonicMillis(),
   reconnectAttempt=failures,
  )
 }}
 private fun setReconnectAttempt(mine:Long,value:Int){synchronized(guard){if(mine==generation)_diagnostics.value=_diagnostics.value.copy(reconnectAttempt=value)}}
 private fun scheduleRetry(mine:Long,delayMillis:Long,attempt:Int){synchronized(guard){if(mine==generation)_diagnostics.value=_diagnostics.value.copy(reconnectAttempt=attempt,nextRetryElapsedRealtime=monotonicMillis()+delayMillis,circuitOpen=false)}}
 private fun clearScheduledRetry(mine:Long){synchronized(guard){if(mine==generation)_diagnostics.value=_diagnostics.value.copy(nextRetryElapsedRealtime=null)}}
 private fun openCircuit(mine:Long,attempt:Int){synchronized(guard){if(mine==generation){_diagnostics.value=_diagnostics.value.copy(reconnectAttempt=attempt,nextRetryElapsedRealtime=null,circuitOpen=true);_state.value=NmeaConnectionState.ERROR}}}
 private fun stopAfterFailure(mine:Long,attempt:Int){synchronized(guard){if(mine==generation){_diagnostics.value=_diagnostics.value.copy(reconnectAttempt=attempt,nextRetryElapsedRealtime=null,circuitOpen=false);_state.value=NmeaConnectionState.ERROR}}}
 private fun beginAutomaticTransportGeneration(mine:Long,attempt:Int){synchronized(guard){if(mine!=generation)return;transportGeneration++;onGenerationStarted();_diagnostics.value=_diagnostics.value.copy(connectionGeneration=transportGeneration,reconnectAttempt=attempt,connectedAtElapsedRealtime=null,lastByteReceivedElapsedRealtime=null,lastSentenceReceivedElapsedRealtime=null,nextRetryElapsedRealtime=null,lastOperation="AUTO_RETRY")}}
 private fun resetAttempt(mine:Long){setReconnectAttempt(mine,0)}
 private fun finish(mine:Long){synchronized(guard){if(mine==generation){closeTransportLocked();job=null;if(_state.value!=NmeaConnectionState.ERROR){profile=null;_state.value=NmeaConnectionState.DISCONNECTED;_diagnostics.value=_diagnostics.value.copy(desiredConnected=false,nextRetryElapsedRealtime=null)}}}}
 private fun failureCategory(error:Throwable)=when(error){
  is UnknownHostException->"HOST_NOT_FOUND"
  is ConnectException->if(error.message?.contains("refused",true)==true)"CONNECTION_REFUSED" else "CONNECT_FAILED"
  is NoRouteToHostException->"NETWORK_UNREACHABLE"
  is SocketTimeoutException->"CONNECT_TIMEOUT"
  is EOFException->"PEER_CLOSED"
  is SocketException->"SOCKET_ERROR"
  else->"TRANSPORT_ERROR"
 }
 private fun monotonicMillis()=System.nanoTime()/1_000_000L
 companion object{const val STABLE_CONNECTION_RESET_MILLIS=10_000L}
}
