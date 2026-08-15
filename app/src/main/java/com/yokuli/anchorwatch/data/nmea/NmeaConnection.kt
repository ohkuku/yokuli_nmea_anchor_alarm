package com.yokuli.anchorwatch.data.nmea

import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.Closeable
import java.io.EOFException
import java.net.*

enum class Protocol { TCP, UDP }
data class ConnectionProfile(val name:String="Boat",val protocol:Protocol=Protocol.TCP,val host:String="192.168.1.100",val port:Int=10110,val requireChecksum:Boolean=true,val autoReconnect:Boolean=true,val connectAutomatically:Boolean=false,val noDataTimeoutSeconds:Int=10)

class NmeaConnectionManager(private val scope:CoroutineScope) {
 private val guard=Any()
 private var job:Job?=null
 private var generation=0L
 private var profile:ConnectionProfile?=null
 private var transport:Closeable?=null
 private val _state=MutableStateFlow(NmeaConnectionState.DISCONNECTED); val state=_state.asStateFlow()
 private val _lines=MutableSharedFlow<String>(extraBufferCapacity=256); val lines=_lines.asSharedFlow()
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
 fun disconnect(){synchronized(guard){generation++;profile=null;job?.cancel();job=null;closeTransportLocked();_state.value=NmeaConnectionState.DISCONNECTED}}
 private fun startLocked(p:ConnectionProfile):Boolean{
  generation++;val mine=generation;job?.cancel();closeTransportLocked();profile=p
  job=scope.launch(Dispatchers.IO){runConnection(p,mine)}
  return true
 }
 private suspend fun runConnection(p:ConnectionProfile,mine:Long){
  var attempt=0
  try{while(currentCoroutineContext().isActive&&isCurrent(mine)){
   try{setState(mine,if(attempt==0)NmeaConnectionState.CONNECTING else NmeaConnectionState.RECONNECTING);if(p.protocol==Protocol.TCP)tcp(p,mine)else udp(p,mine);if(!p.autoReconnect)break;throw EOFException("NMEA source closed")}
   catch(e:CancellationException){throw e}
   catch(_:Exception){if(!currentCoroutineContext().isActive||!isCurrent(mine))break;setState(mine,NmeaConnectionState.ERROR);if(!p.autoReconnect)break;val delays=listOf(1000L,2000,5000,10000,15000);delay(delays[attempt.coerceAtMost(delays.lastIndex)]);attempt++}
  }}finally{finish(mine)}
 }
 private suspend fun tcp(p:ConnectionProfile,mine:Long){val socket=Socket();register(mine,socket);try{socket.connect(InetSocketAddress(p.host,p.port),5000);socket.soTimeout=30_000;setState(mine,NmeaConnectionState.CONNECTED);val split=NmeaStreamSplitter();val b=ByteArray(4096);while(currentCoroutineContext().isActive){val n=socket.getInputStream().read(b);if(n<0)throw EOFException("NMEA source closed");split.feed(b,n).forEach{_lines.emit(it)}}}finally{unregister(socket);runCatching{socket.close()}}}
 private suspend fun udp(p:ConnectionProfile,mine:Long){val socket=DatagramSocket(null);register(mine,socket);try{socket.reuseAddress=true;socket.bind(InetSocketAddress(p.port));setState(mine,NmeaConnectionState.CONNECTED);val split=NmeaStreamSplitter();val b=ByteArray(8192);while(currentCoroutineContext().isActive){val packet=DatagramPacket(b,b.size);socket.receive(packet);split.feed(packet.data,packet.length).forEach{_lines.emit(it)}}}finally{unregister(socket);runCatching{socket.close()}}}
 private fun register(mine:Long,value:Closeable){synchronized(guard){if(mine!=generation){runCatching{value.close()};throw CancellationException("Superseded NMEA connection")};transport=value}}
 private fun unregister(value:Closeable){synchronized(guard){if(transport===value)transport=null}}
 private fun closeTransportLocked(){runCatching{transport?.close()};transport=null}
 private fun isCurrent(mine:Long)=synchronized(guard){mine==generation}
 private fun setState(mine:Long,value:NmeaConnectionState){synchronized(guard){if(mine==generation)_state.value=value}}
 private fun finish(mine:Long){synchronized(guard){if(mine==generation){closeTransportLocked();profile=null;job=null;_state.value=NmeaConnectionState.DISCONNECTED}}}
}
