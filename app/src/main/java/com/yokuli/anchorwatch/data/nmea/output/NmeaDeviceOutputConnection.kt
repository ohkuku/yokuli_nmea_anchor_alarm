package com.yokuli.anchorwatch.data.nmea.output

import android.os.SystemClock
import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.nmea.Protocol
import com.yokuli.anchorwatch.data.vessel.NmeaDeviceOutputSettings
import com.yokuli.anchorwatch.data.vessel.NmeaOutputTransportMode
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NmeaTxConnectionState { OFF, DISCONNECTED, CONNECTING, CONNECTED, ERROR }

object NmeaOutputEndpointPolicy{
    fun resolved(settings:NmeaDeviceOutputSettings,input:ConnectionProfile)=if(settings.transportMode==NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION)input.host to input.port else settings.outputHost.trim() to settings.outputPort
    fun isValid(settings:NmeaDeviceOutputSettings,input:ConnectionProfile)=when(settings.transportMode){NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->input.protocol==Protocol.TCP&&input.host.isNotBlank()&&input.port in 1..65535;NmeaOutputTransportMode.DEDICATED_TCP->settings.outputHost.isNotBlank()&&settings.outputPort in 1..65535}
    fun needsInputTransport(settings:NmeaDeviceOutputSettings)=settings.transportMode==NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION
    fun duplicateEndpointRisk(settings:NmeaDeviceOutputSettings,input:ConnectionProfile):Boolean{val resolved=resolved(settings,input);return settings.transportMode==NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION||(resolved.first.equals(input.host,true)&&resolved.second==input.port)}
}

data class NmeaTxStatus(
    val enabled:Boolean=false,
    val mode:NmeaOutputTransportMode=NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION,
    val endpointHost:String="",
    val endpointPort:Int=0,
    val connectionState:NmeaTxConnectionState=NmeaTxConnectionState.OFF,
    val attemptedSentences:Long=0,
    val writtenSentences:Long=0,
    val bytesWritten:Long=0,
    val lastAttemptElapsed:Long?=null,
    val lastWriteElapsed:Long?=null,
    val reconnectCount:Int=0,
    val lastError:String?=null,
    val message:String="Off",
    val sentenceTypes:Set<String> = emptySet(),
    val recentTx:List<String> = emptyList(),
){
    /** Compatibility for the existing compact status surface. */
    val sentencesWritten:Long get()=writtenSentences
}

/**
 * Short-lived exact-sentence quarantine. It prevents the App's own RMC/HDT/XDR
 * output from being accepted as independent boat evidence when a gateway echoes
 * traffic back to the input stream.
 */
@Singleton
class NmeaOutboundLoopGuard @Inject constructor(){
    private val sent=linkedMapOf<String,Long>()
    @Synchronized fun record(sentences:List<String>,nowElapsed:Long=SystemClock.elapsedRealtime()){
        prune(nowElapsed)
        sentences.forEach{sentence->sent[normalize(sentence)]=nowElapsed}
        while(sent.size>MAX_ENTRIES)sent.remove(sent.keys.first())
    }
    @Synchronized fun isRecentOutbound(sentence:String,nowElapsed:Long=SystemClock.elapsedRealtime()):Boolean{
        prune(nowElapsed)
        return sent[normalize(sentence)]?.let{nowElapsed-it in 0L..QUARANTINE_MILLIS}==true
    }
    private fun prune(now:Long){sent.entries.removeAll{now-it.value>QUARANTINE_MILLIS}}
    private fun normalize(value:String)=value.trim().removeSuffix("\r").removeSuffix("\n")
    companion object{const val QUARANTINE_MILLIS=5_000L;private const val MAX_ENTRIES=256}
}

/** A write-only transport. Dedicated TX never participates in RX freshness. */
@Singleton
class NmeaDeviceOutputConnection @Inject constructor(
    private val navigation:NavigationRepository,
    private val loopGuard:NmeaOutboundLoopGuard,
    private val dedicatedClient:DedicatedNmeaTcpClient,
){
    private val guard=Any()
    private var configured=NmeaDeviceOutputSettings()
    private val recent=ArrayDeque<String>()
    private val _status=MutableStateFlow(NmeaTxStatus())
    val status=_status.asStateFlow()

    fun configure(value:NmeaDeviceOutputSettings,input:ConnectionProfile){synchronized(guard){
        val endpoint=NmeaOutputEndpointPolicy.resolved(value,input)
        val endpointChanged=configured.transportMode!=value.transportMode||configured.outputHost!=value.outputHost||configured.outputPort!=value.outputPort
        configured=value
        if(!value.anyOutputEnabled){closeLocked();_status.value=_status.value.copy(enabled=false,mode=value.transportMode,endpointHost=endpoint.first,endpointPort=endpoint.second,connectionState=NmeaTxConnectionState.OFF,message="Off",lastError=null);return}
        if(endpointChanged)closeLocked()
        val state=when{
            value.transportMode==NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->NmeaTxConnectionState.DISCONNECTED
            dedicatedClient.isConnected(endpoint.first,endpoint.second)->NmeaTxConnectionState.CONNECTED
            else->NmeaTxConnectionState.DISCONNECTED
        }
        _status.value=_status.value.copy(enabled=true,mode=value.transportMode,endpointHost=endpoint.first,endpointPort=endpoint.second,connectionState=state,message=if(value.transportMode==NmeaOutputTransportMode.DEDICATED_TCP)"Dedicated NMEA TX ready." else "Waiting for the input TCP connection.",lastError=null)
    }}

    fun write(input:ConnectionProfile,sentences:List<String>,sentenceTypes:Set<String>):Boolean=synchronized(guard){
        if(!configured.anyOutputEnabled||sentences.isEmpty())return false
        val now=SystemClock.elapsedRealtime();val bytes=sentences.sumOf{it.toByteArray(Charsets.US_ASCII).size}.toLong()
        _status.value=_status.value.copy(attemptedSentences=_status.value.attemptedSentences+sentences.size,lastAttemptElapsed=now,sentenceTypes=_status.value.sentenceTypes+sentenceTypes)
        val success=when(configured.transportMode){
            NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->navigation.writeToBoat(sentences)
            NmeaOutputTransportMode.DEDICATED_TCP->writeDedicatedLocked(input,sentences)
        }
        if(success){
            loopGuard.record(sentences,now)
            val timestamp=java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
            sentences.forEach{line->recent.addLast("$timestamp  ${line.trim()}");while(recent.size>RECENT_LIMIT)recent.removeFirst()}
            _status.value=_status.value.copy(connectionState=NmeaTxConnectionState.CONNECTED,writtenSentences=_status.value.writtenSentences+sentences.size,bytesWritten=_status.value.bytesWritten+bytes,lastWriteElapsed=now,lastError=null,message="Socket TX successful; server receipt is not confirmed.",recentTx=recent.toList())
        }else if(configured.transportMode==NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION){
            _status.value=_status.value.copy(connectionState=NmeaTxConnectionState.DISCONNECTED,lastError="Input TCP connection is not writable.",message="Waiting for the input TCP connection.")
        }
        success
    }

    fun stop(){synchronized(guard){closeLocked();configured=NmeaDeviceOutputSettings();_status.value=_status.value.copy(enabled=false,connectionState=NmeaTxConnectionState.OFF,message="Off")}}

    private fun writeDedicatedLocked(input:ConnectionProfile,sentences:List<String>):Boolean{
        val endpoint=NmeaOutputEndpointPolicy.resolved(configured,input)
        if(endpoint.first.isBlank()||endpoint.second !in 1..65535){
            _status.value=_status.value.copy(connectionState=NmeaTxConnectionState.ERROR,lastError="A valid dedicated TX host and port are required.",message="Dedicated NMEA TX endpoint is invalid.");return false
        }
        _status.value=_status.value.copy(connectionState=if(dedicatedClient.isConnected(endpoint.first,endpoint.second))NmeaTxConnectionState.CONNECTED else NmeaTxConnectionState.CONNECTING,message="Connecting dedicated NMEA TX…")
        val result=dedicatedClient.write(endpoint.first,endpoint.second,sentences)
        if(!result.success){_status.value=_status.value.copy(connectionState=NmeaTxConnectionState.ERROR,lastError=result.error,message="Dedicated NMEA TX write failed; it will reconnect on the next sample.");return false}
        if(result.openedNewConnection)_status.value=_status.value.copy(reconnectCount=_status.value.reconnectCount+1)
        return true
    }
    private fun closeLocked(){dedicatedClient.close()}
    private val NmeaDeviceOutputSettings.anyOutputEnabled get()=phonePositionEnabled||phoneHeadingEnabled||phoneMotionEnabled||phonePressureEnabled||proprietaryStatusEnabled
    companion object{private const val RECENT_LIMIT=40}
}

data class DedicatedNmeaWriteResult(val success:Boolean,val openedNewConnection:Boolean=false,val error:String?=null)

/** Small write-only TCP primitive kept Android-free so endpoint behaviour can
 * be exercised with real local sockets in JVM tests. */
@Singleton
class DedicatedNmeaTcpClient @Inject constructor(){
    private var socket:Socket?=null
    private var endpoint:Pair<String,Int>?=null

    @Synchronized fun isConnected(host:String,port:Int)=endpoint==(host to port)&&socket?.isConnected==true&&socket?.isClosed==false
    @Synchronized fun write(host:String,port:Int,sentences:List<String>):DedicatedNmeaWriteResult{
        var opened=false
        val target=host to port
        val active=socket?.takeIf{it.isConnected&&!it.isClosed&&endpoint==target}?:run{
            close();val candidate=Socket()
            try{candidate.connect(InetSocketAddress(host,port),CONNECT_TIMEOUT_MILLIS);candidate.tcpNoDelay=true;socket=candidate;endpoint=target;opened=true;candidate}
            catch(error:Exception){runCatching{candidate.close()};return DedicatedNmeaWriteResult(false,error=error.javaClass.simpleName+(error.message?.let{": $it"}.orEmpty()))}
        }
        return try{val output=active.getOutputStream();sentences.forEach{output.write(it.toByteArray(Charsets.US_ASCII))};output.flush();DedicatedNmeaWriteResult(true,opened)}
        catch(error:Exception){close();DedicatedNmeaWriteResult(false,error=error.javaClass.simpleName+(error.message?.let{": $it"}.orEmpty()))}
    }
    @Synchronized fun close(){runCatching{socket?.close()};socket=null;endpoint=null}
    private companion object{const val CONNECT_TIMEOUT_MILLIS=4_000}
}
