package com.yokuli.anchorwatch.data.nmea.output

import android.os.SystemClock
import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.nmea.Protocol
import com.yokuli.anchorwatch.data.vessel.NmeaDeviceOutputSettings
import com.yokuli.anchorwatch.data.vessel.NmeaOutputTransportMode
import com.yokuli.anchorwatch.data.vessel.anyEnabled
import com.yokuli.anchorwatch.data.vessel.effectiveMotionPolicy
import com.yokuli.anchorwatch.data.vessel.effectivePressurePolicy
import com.yokuli.anchorwatch.domain.vessel.PublicationPolicy
import com.yokuli.anchorwatch.domain.vessel.PublisherOwnershipState
import com.yokuli.anchorwatch.runtime.output.PublicationDecision
import java.net.DatagramPacket
import java.net.DatagramSocket
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
    fun isValid(settings:NmeaDeviceOutputSettings,input:ConnectionProfile)=when(settings.transportMode){NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->input.protocol==Protocol.TCP&&input.host.isNotBlank()&&input.port in 1..65535;NmeaOutputTransportMode.DEDICATED_TCP,NmeaOutputTransportMode.UDP_UNICAST,NmeaOutputTransportMode.UDP_BROADCAST->settings.outputHost.isNotBlank()&&settings.outputPort in 1..65535}
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
    val recentGenerated:List<String> = emptyList(),
    val recentTx:List<String> = emptyList(),
    val streams:Map<String,NmeaStreamTxStatus> = emptyMap(),
){
    /** Compatibility for the existing compact status surface. */
    val sentencesWritten:Long get()=writtenSentences
}
data class NmeaStreamTxStatus(val lastGeneratedElapsed:Long?=null,val lastWrittenElapsed:Long?=null,val suppressionReason:String?=null,val generatedCount:Long=0,val writtenCount:Long=0,val policy:PublicationPolicy=PublicationPolicy.OFF,val ownership:PublisherOwnershipState=PublisherOwnershipState.SUPPRESSED,val dataReady:Boolean=false,val droppedCount:Long=0,val lastGeneratedSequence:Long=0,val lastWrittenSequence:Long=0,val generatedRateHz:Double=0.0,val socketWriteRateHz:Double=0.0)

/**
 * Short-lived exact-sentence quarantine. It prevents the App's own RMC/HDT/XDR
 * output from being accepted as independent boat evidence when a gateway echoes
 * traffic back to the input stream.
 */
@Singleton
class NmeaOutboundLoopGuard @Inject constructor(){
    private val sent=linkedMapOf<String,Long>()
    private val semantic=ArrayDeque<NmeaSemanticFingerprint>()
    private val semanticQuarantineStarted=linkedMapOf<String,Pair<NmeaSemanticFingerprint,Long>>()
    @Synchronized fun record(sentences:List<String>,nowElapsed:Long=SystemClock.elapsedRealtime()){
        prune(nowElapsed)
        sentences.forEach{sentence->
            sent[normalize(sentence)]=nowElapsed
            semanticFingerprint(sentence,nowElapsed)?.let(semantic::addLast)
        }
        while(sent.size>MAX_ENTRIES)sent.remove(sent.keys.first())
        while(semantic.size>MAX_ENTRIES)semantic.removeFirst()
    }
    @Synchronized fun isRecentOutbound(sentence:String,nowElapsed:Long=SystemClock.elapsedRealtime()):Boolean{
        prune(nowElapsed)
        if(sent[normalize(sentence)]?.let{nowElapsed-it in 0L..QUARANTINE_MILLIS}==true)return true
        val candidate=semanticFingerprint(sentence,nowElapsed)?:return false
        // Require an established outbound heartbeat before quarantining a
        // talker/checksum-transformed sentence. The lease deliberately starts
        // at the first inbound match and is never extended by a constant-value
        // publisher, so a real independent instrument reporting the same value
        // cannot be hidden forever by semantic coincidence.
        if(semantic.count{it.matches(candidate)}<2)return false
        val key=candidate.quarantineKey()
        val lease=semanticQuarantineStarted[key]
        val started=if(lease==null||!lease.first.matches(candidate))nowElapsed.also{semanticQuarantineStarted[key]=candidate to it}else lease.second
        return nowElapsed-started in 0L..QUARANTINE_MILLIS
    }
    private fun prune(now:Long){
        sent.entries.removeAll{now-it.value>QUARANTINE_MILLIS}
        while(semantic.firstOrNull()?.let{now-it.sentElapsedRealtime>QUARANTINE_MILLIS}==true)semantic.removeFirst()
        semanticQuarantineStarted.entries.removeAll{now-it.value.second>QUARANTINE_MILLIS*2}
    }
    private fun normalize(value:String)=value.trim().removeSuffix("\r").removeSuffix("\n")
    private fun semanticFingerprint(value:String,now:Long):NmeaSemanticFingerprint?{
        val fields=value.trim().removePrefix("$").substringBefore('*').split(',');val id=fields.firstOrNull()?:return null;val type=id.takeLast(3).uppercase()
        return when(type){
            "HDT"->fields.getOrNull(1)?.toDoubleOrNull()?.let{NmeaSemanticFingerprint(NmeaSemantic.TRUE_HEADING,listOf(it),null,now)}
            "HDG","HDM"->fields.getOrNull(1)?.toDoubleOrNull()?.let{NmeaSemanticFingerprint(NmeaSemantic.MAGNETIC_HEADING,listOf(it),null,now)}
            "ROT"->fields.getOrNull(1)?.toDoubleOrNull()?.let{NmeaSemanticFingerprint(NmeaSemantic.ROT,listOf(it),null,now)}
            "RMC"->nmeaPosition(fields,3,4,5,6)?.let{NmeaSemanticFingerprint(NmeaSemantic.POSITION,it,type,now)}
            "GGA"->nmeaPosition(fields,2,3,4,5)?.let{NmeaSemanticFingerprint(NmeaSemantic.POSITION,it,type,now)}
            "GLL"->nmeaPosition(fields,1,2,3,4)?.let{NmeaSemanticFingerprint(NmeaSemantic.POSITION,it,type,now)}
            "XDR"->{
                val groups=fields.drop(1).chunked(4).filter{it.size==4&&it[1].toDoubleOrNull()!=null}
                groups.takeIf{it.isNotEmpty()}?.let{NmeaSemanticFingerprint(NmeaSemantic.XDR,it.mapNotNull{group->group[1].toDoubleOrNull()},it.joinToString("|"){group->group[3].uppercase()},now)}
            }
            else->null
        }
    }
    private fun nmeaPosition(fields:List<String>,latIndex:Int,latHemisphereIndex:Int,lonIndex:Int,lonHemisphereIndex:Int):List<Double>?{
        fun coordinate(raw:String?,hemisphere:String?):Double?{val value=raw?.toDoubleOrNull()?:return null;val degrees=(value/100).toInt();val decimal=degrees+(value-degrees*100)/60.0;return if(hemisphere.equals("S",true)||hemisphere.equals("W",true))-decimal else decimal}
        val lat=coordinate(fields.getOrNull(latIndex),fields.getOrNull(latHemisphereIndex))?:return null
        val lon=coordinate(fields.getOrNull(lonIndex),fields.getOrNull(lonHemisphereIndex))?:return null
        return listOf(lat,lon)
    }
    companion object{const val QUARANTINE_MILLIS=5_000L;private const val MAX_ENTRIES=256}
}

enum class NmeaSemantic{TRUE_HEADING,MAGNETIC_HEADING,POSITION,ROT,XDR}
data class NmeaSemanticFingerprint(
    val semantic:NmeaSemantic,
    val values:List<Double>,
    val identityHint:String?,
    val sentElapsedRealtime:Long,
){
    fun matches(other:NmeaSemanticFingerprint):Boolean{
        if(semantic!=other.semantic||identityHint!=other.identityHint||values.size!=other.values.size)return false
        val tolerance=when(semantic){NmeaSemantic.TRUE_HEADING,NmeaSemantic.MAGNETIC_HEADING->0.3;NmeaSemantic.POSITION->0.00003;NmeaSemantic.ROT->0.2;NmeaSemantic.XDR->0.05}
        return values.zip(other.values).all{(left,right)->kotlin.math.abs(left-right)<=tolerance}
    }
    fun quarantineKey()="$semantic:${identityHint.orEmpty()}"
}

/** A write-only transport. Dedicated TX never participates in RX freshness. */
@Singleton
class NmeaDeviceOutputConnection @Inject constructor(
    private val navigation:NavigationRepository,
    private val loopGuard:NmeaOutboundLoopGuard,
    private val dedicatedClient:DedicatedNmeaTcpClient,
    private val udpClient:NmeaUdpClient,
){
    private val guard=Any()
    private var configured=NmeaDeviceOutputSettings()
    private val recent=ArrayDeque<String>()
    private val generated=ArrayDeque<String>()
    private val _status=MutableStateFlow(NmeaTxStatus())
    val status=_status.asStateFlow()
    private var consecutiveFailures=0
    private var nextDedicatedAttemptElapsed=0L

    fun recordGenerated(stream:String,sentences:List<String>,now:Long=SystemClock.elapsedRealtime()):Long=synchronized(guard){
        val old=_status.value.streams[stream]?:NmeaStreamTxStatus();val sequence=old.lastGeneratedSequence+1;val rate=old.lastGeneratedElapsed?.let{previous->(now-previous).takeIf{it>0}?.let{1_000.0/it}}?:old.generatedRateHz
        val timestamp=java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
        sentences.forEach{line->generated.addLast("$timestamp  [$stream] ${line.trim()}");while(generated.size>RECENT_LIMIT)generated.removeFirst()}
        _status.value=_status.value.copy(recentGenerated=generated.toList(),streams=_status.value.streams+(stream to old.copy(lastGeneratedElapsed=now,suppressionReason=null,generatedCount=old.generatedCount+1,lastGeneratedSequence=sequence,generatedRateHz=rate)));sequence
    }
    fun recordDropped(stream:String){synchronized(guard){val old=_status.value.streams[stream]?:NmeaStreamTxStatus();_status.value=_status.value.copy(streams=_status.value.streams+(stream to old.copy(droppedCount=old.droppedCount+1)))}}
    fun recordSuppressed(stream:String,reason:String){synchronized(guard){val old=_status.value.streams[stream]?:NmeaStreamTxStatus();_status.value=_status.value.copy(streams=_status.value.streams+(stream to old.copy(suppressionReason=reason)))}}
    fun recordDecision(stream:String,policy:PublicationPolicy,decision:PublicationDecision,dataReady:Boolean){synchronized(guard){val old=_status.value.streams[stream]?:NmeaStreamTxStatus();_status.value=_status.value.copy(streams=_status.value.streams+(stream to old.copy(policy=policy,ownership=decision.ownership,dataReady=dataReady,suppressionReason=decision.suppression?.name)))}}

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
        _status.value=_status.value.copy(enabled=true,mode=value.transportMode,endpointHost=endpoint.first,endpointPort=endpoint.second,connectionState=state,message=when(value.transportMode){NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->"Waiting for the input TCP connection.";NmeaOutputTransportMode.DEDICATED_TCP->"Dedicated NMEA TX ready.";NmeaOutputTransportMode.UDP_UNICAST->"UDP unicast destination ready.";NmeaOutputTransportMode.UDP_BROADCAST->"UDP broadcast destination ready."},lastError=null)
    }}

    fun write(input:ConnectionProfile,sentences:List<String>,sentenceTypes:Set<String>,logicalStream:String?=null,generationSequence:Long?=null):Boolean{
        if(sentences.isEmpty())return false
        val now=SystemClock.elapsedRealtime();val bytes=sentences.sumOf{it.toByteArray(Charsets.US_ASCII).size}.toLong()
        val settings=synchronized(guard){
            if(!configured.anyOutputEnabled)return false
            _status.value=_status.value.copy(attemptedSentences=_status.value.attemptedSentences+sentences.size,lastAttemptElapsed=now,sentenceTypes=_status.value.sentenceTypes+sentenceTypes)
            if(configured.transportMode==NmeaOutputTransportMode.DEDICATED_TCP&&now<nextDedicatedAttemptElapsed){
                _status.value=_status.value.copy(connectionState=NmeaTxConnectionState.ERROR,message="Dedicated NMEA TX is backing off before reconnect.")
                return false
            }
            if(configured.transportMode==NmeaOutputTransportMode.DEDICATED_TCP){
                val endpoint=NmeaOutputEndpointPolicy.resolved(configured,input)
                _status.value=_status.value.copy(connectionState=if(dedicatedClient.isConnected(endpoint.first,endpoint.second))NmeaTxConnectionState.CONNECTED else NmeaTxConnectionState.CONNECTING,message="Connecting dedicated NMEA TX…")
            }
            configured
        }
        // Network IO deliberately happens outside guard. A weak or silent
        // gateway may consume the full connect timeout, but it must never block
        // status reads, policy changes, Stop, or another runtime decision.
        val result=when(settings.transportMode){
            NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->TransportWriteResult(navigation.writeToBoat(sentences))
            NmeaOutputTransportMode.DEDICATED_TCP->writeDedicated(settings,input,sentences)
            NmeaOutputTransportMode.UDP_UNICAST->writeUdp(settings,input,sentences,false)
            NmeaOutputTransportMode.UDP_BROADCAST->writeUdp(settings,input,sentences,true)
        }
        synchronized(guard){
        if(configured!=settings)return false
        if(result.success){
            consecutiveFailures=0;nextDedicatedAttemptElapsed=0L
            loopGuard.record(sentences,now)
            val timestamp=java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
            sentences.forEach{line->recent.addLast("$timestamp  ${line.trim()}");while(recent.size>RECENT_LIMIT)recent.removeFirst()}
            val logicalStreams=buildSet{if(sentenceTypes.any{it in setOf("RMC","GGA","VTG","ZDA")})add("POSITION");if(sentenceTypes.any{it in setOf("HDT","HDG","HDM")})add("HEADING");if("ROT" in sentenceTypes||("XDR" in sentenceTypes&&settings.effectiveMotionPolicy!=PublicationPolicy.OFF))add("MOTION");if("XDR" in sentenceTypes&&settings.effectivePressurePolicy!=PublicationPolicy.OFF)add("PRESSURE");if(sentenceTypes.any{it in setOf("MWD","MWV","VWT")})add("DERIVED_WIND");if("YOK" in sentenceTypes)add("STATUS")}
            val streamUpdates=(sentenceTypes+logicalStreams).fold(_status.value.streams){map,type->val old=map[type]?:NmeaStreamTxStatus();val rate=old.lastWrittenElapsed?.let{previous->(now-previous).takeIf{it>0}?.let{1_000.0/it}}?:old.socketWriteRateHz;map+(type to old.copy(lastWrittenElapsed=now,suppressionReason=null,writtenCount=old.writtenCount+1,lastWrittenSequence=if(type==logicalStream&&generationSequence!=null)generationSequence else old.lastGeneratedSequence,socketWriteRateHz=rate))}
            _status.value=_status.value.copy(connectionState=NmeaTxConnectionState.CONNECTED,writtenSentences=_status.value.writtenSentences+sentences.size,bytesWritten=_status.value.bytesWritten+bytes,lastWriteElapsed=now,reconnectCount=_status.value.reconnectCount+if(result.openedNewConnection)1 else 0,lastError=null,message="Socket TX successful; server receipt is not confirmed.",recentTx=recent.toList(),streams=streamUpdates)
        }else if(settings.transportMode==NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION){
            _status.value=_status.value.copy(connectionState=NmeaTxConnectionState.DISCONNECTED,lastError="Input TCP connection is not writable.",message="Waiting for the input TCP connection.")
        }else{
            consecutiveFailures=(consecutiveFailures+1).coerceAtMost(RETRY_SECONDS.size)
            val delay=if(settings.transportMode==NmeaOutputTransportMode.DEDICATED_TCP)RETRY_SECONDS[consecutiveFailures-1]*1_000L else 0L
            if(delay>0)nextDedicatedAttemptElapsed=now+delay
            _status.value=_status.value.copy(
                connectionState=NmeaTxConnectionState.ERROR,
                reconnectCount=_status.value.reconnectCount+if(result.openedNewConnection)1 else 0,
                lastError=result.error,
                message=when(settings.transportMode){
                    NmeaOutputTransportMode.DEDICATED_TCP->"Dedicated NMEA TX write failed; reconnect in ${delay/1_000}s."
                    NmeaOutputTransportMode.UDP_UNICAST,NmeaOutputTransportMode.UDP_BROADCAST->"UDP NMEA TX failed."
                    NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->"Waiting for the input TCP connection."
                },
            )
        }
        return result.success
        }
    }

    fun stop(){synchronized(guard){closeLocked();configured=NmeaDeviceOutputSettings();_status.value=_status.value.copy(enabled=false,connectionState=NmeaTxConnectionState.OFF,message="Off")}}

    private fun writeDedicated(settings:NmeaDeviceOutputSettings,input:ConnectionProfile,sentences:List<String>):TransportWriteResult{
        val endpoint=NmeaOutputEndpointPolicy.resolved(settings,input)
        if(endpoint.first.isBlank()||endpoint.second !in 1..65535)return TransportWriteResult(false,error="A valid dedicated TX host and port are required.")
        val result=dedicatedClient.write(endpoint.first,endpoint.second,sentences)
        return TransportWriteResult(result.success,result.openedNewConnection,result.error)
    }
    private fun writeUdp(settings:NmeaDeviceOutputSettings,input:ConnectionProfile,sentences:List<String>,broadcast:Boolean):TransportWriteResult{val endpoint=NmeaOutputEndpointPolicy.resolved(settings,input);val result=udpClient.write(endpoint.first,endpoint.second,sentences,broadcast);return TransportWriteResult(result.success,result.openedNewConnection,result.error)}
    private fun closeLocked(){dedicatedClient.close();udpClient.close();consecutiveFailures=0;nextDedicatedAttemptElapsed=0L}
    private val NmeaDeviceOutputSettings.anyOutputEnabled get()=anyEnabled
    companion object{private const val RECENT_LIMIT=40;private val RETRY_SECONDS=listOf(1L,2L,5L,10L,15L)}
}

private data class TransportWriteResult(val success:Boolean,val openedNewConnection:Boolean=false,val error:String?=null)

@Singleton class NmeaUdpClient @Inject constructor(){
    private var socket:DatagramSocket?=null
    @Synchronized fun write(host:String,port:Int,sentences:List<String>,broadcast:Boolean):DedicatedNmeaWriteResult{
        if(host.isBlank()||port !in 1..65535)return DedicatedNmeaWriteResult(false,error="Invalid UDP destination")
        return try{val active=socket?:DatagramSocket().also{socket=it};active.broadcast=broadcast;val address=java.net.InetAddress.getByName(host);sentences.forEach{line->val bytes=line.toByteArray(Charsets.US_ASCII);active.send(DatagramPacket(bytes,bytes.size,address,port))};DedicatedNmeaWriteResult(true)}catch(error:Exception){DedicatedNmeaWriteResult(false,error=error.javaClass.simpleName+(error.message?.let{": $it"}.orEmpty()))}
    }
    @Synchronized fun close(){runCatching{socket?.close()};socket=null}
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
