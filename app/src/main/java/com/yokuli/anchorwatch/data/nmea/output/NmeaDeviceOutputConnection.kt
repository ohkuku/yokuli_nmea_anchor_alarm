package com.yokuli.anchorwatch.data.nmea.output

import android.os.SystemClock
import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.nmea.Protocol
import com.yokuli.anchorwatch.data.sharing.NmeaSharingServer
import com.yokuli.anchorwatch.data.sharing.SharingServerState
import com.yokuli.anchorwatch.data.vessel.NmeaDeviceOutputSettings
import com.yokuli.anchorwatch.data.vessel.NmeaOutputTransportMode
import com.yokuli.anchorwatch.data.vessel.anyEnabled
import com.yokuli.anchorwatch.data.vessel.effectiveMotionPolicy
import com.yokuli.anchorwatch.data.vessel.effectivePressurePolicy
import com.yokuli.anchorwatch.domain.vessel.PublicationPolicy
import com.yokuli.anchorwatch.domain.vessel.PublisherOwnershipState
import com.yokuli.anchorwatch.domain.vessel.NmeaStreamReadiness
import com.yokuli.anchorwatch.runtime.output.PublicationDecision
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NmeaTxConnectionState { OFF, STOPPING, DISCONNECTED, CONNECTING, CONNECTED, ERROR }

object NmeaOutputEndpointPolicy{
    fun resolved(settings:NmeaDeviceOutputSettings,input:ConnectionProfile)=when(settings.transportMode){NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->input.host to input.port;NmeaOutputTransportMode.TCP_SERVER->"0.0.0.0" to settings.outputPort;else->settings.outputHost.trim() to settings.outputPort}
    fun isValid(settings:NmeaDeviceOutputSettings,input:ConnectionProfile)=!opensSecondTransportOnInputEndpoint(settings,input)&&when(settings.transportMode){NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->input.protocol==Protocol.TCP&&input.host.isNotBlank()&&input.port in 1..65535;NmeaOutputTransportMode.TCP_SERVER->settings.outputPort in 1024..65535;NmeaOutputTransportMode.DEDICATED_TCP,NmeaOutputTransportMode.UDP_UNICAST,NmeaOutputTransportMode.UDP_BROADCAST->settings.outputHost.isNotBlank()&&settings.outputPort in 1..65535}
    fun needsInputTransport(settings:NmeaDeviceOutputSettings)=settings.transportMode==NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION
    fun duplicateEndpointRisk(settings:NmeaDeviceOutputSettings,input:ConnectionProfile):Boolean{val resolved=resolved(settings,input);return when(settings.transportMode){NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->true;NmeaOutputTransportMode.TCP_SERVER->false;else->resolved.first.equals(input.host,true)&&resolved.second==input.port}}
    /** Independent TX may never open another client on the formal RX endpoint.
     * The rule is configuration based, so it also applies before RX has ever
     * been opened. SAME_AS_INPUT_CONNECTION is not a second transport: it may
     * write only through an already-owned RX socket and never opens one. */
    fun opensSecondTransportOnInputEndpoint(settings:NmeaDeviceOutputSettings,input:ConnectionProfile):Boolean{
        if(settings.transportMode in setOf(NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION,NmeaOutputTransportMode.TCP_SERVER))return false
        val outputHost=settings.outputHost.trim().trimEnd('.');val inputHost=input.host.trim().trimEnd('.')
        return outputHost.isNotBlank()&&inputHost.isNotBlank()&&outputHost.equals(inputHost,true)&&settings.outputPort==input.port
    }
    const val DUPLICATE_ENDPOINT_MESSAGE="Independent TX cannot use the formal RX host and port. Choose the server's separate receive port, or explicitly use the existing same-socket mode on a gateway that supports bidirectional traffic."
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
    val lastSessionGenerated:List<String> = emptyList(),
    val lastSessionTx:List<String> = emptyList(),
    val publicationGeneration:Long=0,
    val sessionId:String?=null,
    val lastSessionId:String?=null,
    val stoppedAtElapsed:Long?=null,
    val streams:Map<String,NmeaStreamTxStatus> = emptyMap(),
){
    /** Compatibility for the existing compact status surface. */
    val sentencesWritten:Long get()=writtenSentences
}
data class NmeaStreamTxStatus(val lastGeneratedElapsed:Long?=null,val lastWrittenElapsed:Long?=null,val suppressionReason:String?=null,val generatedCount:Long=0,val writtenCount:Long=0,val policy:PublicationPolicy=PublicationPolicy.OFF,val ownership:PublisherOwnershipState=PublisherOwnershipState.SUPPRESSED,val dataReady:Boolean=false,val readiness:NmeaStreamReadiness=NmeaStreamReadiness.STANDBY,val droppedCount:Long=0,val lastGeneratedSequence:Long=0,val lastWrittenSequence:Long=0,val generatedRateHz:Double=0.0,val socketWriteRateHz:Double=0.0)

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
    private val tcpServer:NmeaSharingServer,
){
    private val guard=Any()
    /** Writers hold a read lease for the complete socket operation. Stop first
     * closes every transport to interrupt blocking IO, then takes the write
     * lease; once Stop returns no old writer can still reach a socket. */
    private val lifecycle=ReentrantReadWriteLock(true)
    private var configured=NmeaDeviceOutputSettings()
    @Volatile private var lastInputProfile=ConnectionProfile()
    private var publicationGeneration=0L
    private var sessionId:String?=null
    private val recent=ArrayDeque<String>()
    private val generated=ArrayDeque<String>()
    private val _status=MutableStateFlow(NmeaTxStatus())
    val status=_status.asStateFlow()
    private var consecutiveFailures=0
    private var nextDedicatedAttemptElapsed=0L
    private var endpointBlocked=false
    private var dedicatedCircuitOpen=false
    private var observedServerWrites=0L

    fun recordGenerated(stream:String,sentences:List<String>,now:Long=SystemClock.elapsedRealtime(),generation:Long=publicationGeneration):Long?=synchronized(guard){
        if(generation!=publicationGeneration||!configured.anyOutputEnabled)return@synchronized null
        val old=_status.value.streams[stream]?:NmeaStreamTxStatus();val sequence=old.lastGeneratedSequence+1;val rate=old.lastGeneratedElapsed?.let{previous->(now-previous).takeIf{it>0}?.let{1_000.0/it}}?:old.generatedRateHz
        val timestamp=java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
        sentences.forEach{line->generated.addLast("$timestamp  [$stream] ${line.trim()}");while(generated.size>RECENT_LIMIT)generated.removeFirst()}
        _status.value=_status.value.copy(recentGenerated=generated.toList(),streams=_status.value.streams+(stream to old.copy(lastGeneratedElapsed=now,suppressionReason=null,generatedCount=old.generatedCount+1,lastGeneratedSequence=sequence,generatedRateHz=rate)));sequence
    }
    fun recordDropped(stream:String){synchronized(guard){val old=_status.value.streams[stream]?:NmeaStreamTxStatus();_status.value=_status.value.copy(streams=_status.value.streams+(stream to old.copy(droppedCount=old.droppedCount+1)))}}
    fun recordSuppressed(stream:String,reason:String){synchronized(guard){val old=_status.value.streams[stream]?:NmeaStreamTxStatus();_status.value=_status.value.copy(streams=_status.value.streams+(stream to old.copy(suppressionReason=reason)))}}
    fun recordDecision(stream:String,policy:PublicationPolicy,decision:PublicationDecision,dataReady:Boolean,readiness:NmeaStreamReadiness=if(dataReady)NmeaStreamReadiness.READY else NmeaStreamReadiness.STANDBY){synchronized(guard){val old=_status.value.streams[stream]?:NmeaStreamTxStatus();_status.value=_status.value.copy(streams=_status.value.streams+(stream to old.copy(policy=policy,ownership=decision.ownership,dataReady=dataReady,readiness=if(!decision.publish&&decision.ownership in setOf(PublisherOwnershipState.STANDBY_EXTERNAL_PRESENT,PublisherOwnershipState.TAKEOVER_PENDING))NmeaStreamReadiness.STANDBY else readiness,suppressionReason=decision.suppression?.name)))}}

    fun refreshTransportState(){synchronized(guard){
        if(!configured.anyOutputEnabled||configured.transportMode!=NmeaOutputTransportMode.TCP_SERVER)return
        val server=tcpServer.status.value
        captureServerWritesLocked(server)
        val state=when(server.state){SharingServerState.RUNNING->NmeaTxConnectionState.CONNECTED;SharingServerState.STARTING->NmeaTxConnectionState.CONNECTING;SharingServerState.ERROR->NmeaTxConnectionState.ERROR;SharingServerState.STOPPED->NmeaTxConnectionState.DISCONNECTED}
        _status.value=_status.value.copy(connectionState=state,lastError=server.message.takeIf{server.state==SharingServerState.ERROR},message=server.message.ifBlank{if(state==NmeaTxConnectionState.CONNECTED)"TCP output server is listening." else "TCP output server is not listening."})
    }}

    fun configure(value:NmeaDeviceOutputSettings,input:ConnectionProfile,generation:Long=publicationGeneration+1,newSessionId:String?=null){
        lastInputProfile=input
        if(!value.anyOutputEnabled){
            synchronized(guard){captureServerWritesLocked(tcpServer.status.value);_status.value=_status.value.copy(connectionState=NmeaTxConnectionState.STOPPING,message="Stopping NMEA output…")}
            // Close the registered connect candidate/active transports before
            // waiting for the writer lease. This is what makes Stop immediate
            // even when a fragile gateway never completes connect().
            cancelTransports()
        }
        lifecycle.write{
            synchronized(guard){
                val endpoint=NmeaOutputEndpointPolicy.resolved(value,input)
                val endpointChanged=configured.transportMode!=value.transportMode||configured.outputHost!=value.outputHost||configured.outputPort!=value.outputPort
                val previousSession=sessionId
                publicationGeneration=generation
                if(!value.anyOutputEnabled){
                    val previousGenerated=generated.toList();val previousTx=recent.toList()
                    generated.clear();recent.clear();configured=NmeaDeviceOutputSettings();sessionId=null;endpointBlocked=false
                    closeLocked()
                    _status.value=_status.value.copy(
                        enabled=false,mode=value.transportMode,endpointHost=endpoint.first,endpointPort=endpoint.second,
                        connectionState=NmeaTxConnectionState.OFF,message="Off",lastError=null,
                        recentGenerated=emptyList(),recentTx=emptyList(),streams=emptyMap(),
                        lastSessionGenerated=previousGenerated.ifEmpty{_status.value.lastSessionGenerated},
                        lastSessionTx=previousTx.ifEmpty{_status.value.lastSessionTx},
                        publicationGeneration=generation,sessionId=null,lastSessionId=previousSession?:_status.value.lastSessionId,
                        stoppedAtElapsed=SystemClock.elapsedRealtime(),
                    )
                    return@synchronized
                }
                configured=value;sessionId=newSessionId
                if(NmeaOutputEndpointPolicy.opensSecondTransportOnInputEndpoint(value,input)){
                    endpointBlocked=true;closeLocked()
                    _status.value=_status.value.copy(enabled=false,mode=value.transportMode,endpointHost=endpoint.first,endpointPort=endpoint.second,connectionState=NmeaTxConnectionState.ERROR,lastError=NmeaOutputEndpointPolicy.DUPLICATE_ENDPOINT_MESSAGE,message="TX blocked before opening a socket.",publicationGeneration=generation,sessionId=null)
                    return@synchronized
                }
                endpointBlocked=false
                if(endpointChanged)closeLocked()
                if(previousSession!=newSessionId){generated.clear();recent.clear()}
                if(value.transportMode==NmeaOutputTransportMode.TCP_SERVER){tcpServer.start(value.outputPort);observedServerWrites=tcpServer.status.value.sentSentences}
                val state=when{
                    value.transportMode==NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->NmeaTxConnectionState.DISCONNECTED
                    value.transportMode==NmeaOutputTransportMode.TCP_SERVER&&tcpServer.status.value.state==SharingServerState.RUNNING->NmeaTxConnectionState.CONNECTED
                    value.transportMode==NmeaOutputTransportMode.TCP_SERVER->NmeaTxConnectionState.CONNECTING
                    dedicatedClient.isConnected(endpoint.first,endpoint.second)->NmeaTxConnectionState.CONNECTED
                    else->NmeaTxConnectionState.DISCONNECTED
                }
                _status.value=_status.value.copy(enabled=true,mode=value.transportMode,endpointHost=endpoint.first,endpointPort=endpoint.second,connectionState=state,message=when(value.transportMode){NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->"Waiting for the input TCP connection.";NmeaOutputTransportMode.DEDICATED_TCP->"Dedicated NMEA TX ready.";NmeaOutputTransportMode.TCP_SERVER->"TCP output server is starting.";NmeaOutputTransportMode.UDP_UNICAST->"UDP unicast destination ready.";NmeaOutputTransportMode.UDP_BROADCAST->"UDP broadcast destination ready."},lastError=null,recentGenerated=generated.toList(),recentTx=recent.toList(),streams=if(previousSession==newSessionId)_status.value.streams else emptyMap(),publicationGeneration=generation,sessionId=newSessionId,stoppedAtElapsed=null)
            }
        }
    }

    fun write(input:ConnectionProfile,sentences:List<String>,sentenceTypes:Set<String>,logicalStream:String?=null,generationSequence:Long?=null,generation:Long=publicationGeneration):Boolean=lifecycle.read{
        if(sentences.isEmpty())return false
        val now=SystemClock.elapsedRealtime();val bytes=sentences.sumOf{it.toByteArray(Charsets.US_ASCII).size}.toLong()
        val settings=synchronized(guard){
            if(generation!=publicationGeneration||!configured.anyOutputEnabled||endpointBlocked)return false
            _status.value=_status.value.copy(attemptedSentences=_status.value.attemptedSentences+sentences.size,lastAttemptElapsed=now,sentenceTypes=_status.value.sentenceTypes+sentenceTypes)
            if(configured.transportMode==NmeaOutputTransportMode.DEDICATED_TCP&&dedicatedCircuitOpen){
                _status.value=_status.value.copy(connectionState=NmeaTxConnectionState.ERROR,message="Dedicated NMEA TX retries stopped to protect the server.",lastError="Three consecutive TX connection/write failures opened the safety circuit. Stop output and verify the dedicated TX port before starting again.")
                return false
            }
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
            NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->navigation.writeToBoat(sentences).let{success->TransportWriteResult(success,writtenSentenceCount=if(success)sentences.size else 0)}
            NmeaOutputTransportMode.DEDICATED_TCP->writeDedicated(settings,input,sentences)
            NmeaOutputTransportMode.TCP_SERVER->writeTcpServer(sentences)
            NmeaOutputTransportMode.UDP_UNICAST->writeUdp(settings,input,sentences,false)
            NmeaOutputTransportMode.UDP_BROADCAST->writeUdp(settings,input,sentences,true)
        }
        synchronized(guard){
        if(configured!=settings||generation!=publicationGeneration)return false
        if(result.success){
            consecutiveFailures=0;nextDedicatedAttemptElapsed=0L;dedicatedCircuitOpen=false
            if(result.writtenSentenceCount>0)loopGuard.record(sentences,now)
            val timestamp=java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
            val streamLabel=logicalStream?:sentenceTypes.sorted().joinToString("+").ifBlank{"SOCKET"}
            if(result.writtenSentenceCount>0)sentences.forEach{line->recent.addLast("$timestamp  [$streamLabel] ${line.trim()}");while(recent.size>RECENT_LIMIT)recent.removeFirst()}
            val logicalStreams=buildSet{if(sentenceTypes.any{it in setOf("RMC","GGA","VTG","ZDA")})add("POSITION");if(sentenceTypes.any{it in setOf("HDT","HDG","HDM")})add("HEADING");if("ROT" in sentenceTypes||("XDR" in sentenceTypes&&settings.effectiveMotionPolicy!=PublicationPolicy.OFF))add("MOTION");if("XDR" in sentenceTypes&&settings.effectivePressurePolicy!=PublicationPolicy.OFF)add("PRESSURE");if(sentenceTypes.any{it in setOf("MWD","MWV","VWT")})add("DERIVED_WIND");if("YOK" in sentenceTypes)add("STATUS")}
            val streamUpdates=(sentenceTypes+logicalStreams+listOfNotNull(logicalStream)).fold(_status.value.streams){map,type->val old=map[type]?:NmeaStreamTxStatus();val rate=old.lastWrittenElapsed?.let{previous->(now-previous).takeIf{it>0}?.let{1_000.0/it}}?:old.socketWriteRateHz;map+(type to old.copy(lastWrittenElapsed=now,suppressionReason=null,writtenCount=old.writtenCount+1,lastWrittenSequence=if(type==logicalStream&&generationSequence!=null)generationSequence else old.lastGeneratedSequence,socketWriteRateHz=rate,readiness=NmeaStreamReadiness.PUBLISHING))}
            val hasReceiverWrite=result.writtenSentenceCount>0
            _status.value=_status.value.copy(connectionState=NmeaTxConnectionState.CONNECTED,writtenSentences=_status.value.writtenSentences+result.writtenSentenceCount,bytesWritten=_status.value.bytesWritten+if(hasReceiverWrite)bytes else 0,lastWriteElapsed=now.takeIf{hasReceiverWrite}?:_status.value.lastWriteElapsed,reconnectCount=_status.value.reconnectCount+if(result.openedNewConnection)1 else 0,lastError=null,message=when{hasReceiverWrite->"Socket TX successful; server receipt is not confirmed.";result.acceptedReceivers>0->"Queued to ${result.acceptedReceivers} connected receiver(s); awaiting socket flush.";else->"TCP output server is listening; no receiver is connected."},recentTx=recent.toList(),streams=if(hasReceiverWrite)streamUpdates else _status.value.streams)
        }else if(settings.transportMode==NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION){
            _status.value=_status.value.copy(connectionState=NmeaTxConnectionState.DISCONNECTED,lastError="Input TCP connection is not writable.",message="Waiting for the input TCP connection.")
        }else{
            consecutiveFailures=(consecutiveFailures+1).coerceAtMost(RETRY_SECONDS.size)
            val dedicated=settings.transportMode==NmeaOutputTransportMode.DEDICATED_TCP
            dedicatedCircuitOpen=dedicated&&consecutiveFailures>=RETRY_SECONDS.size
            val delay=if(dedicated&&!dedicatedCircuitOpen)RETRY_SECONDS[consecutiveFailures-1]*1_000L else 0L
            if(delay>0)nextDedicatedAttemptElapsed=now+delay else if(dedicatedCircuitOpen)nextDedicatedAttemptElapsed=Long.MAX_VALUE
            _status.value=_status.value.copy(
                connectionState=NmeaTxConnectionState.ERROR,
                reconnectCount=_status.value.reconnectCount+if(result.openedNewConnection)1 else 0,
                lastError=result.error,
                message=when(settings.transportMode){
                    NmeaOutputTransportMode.DEDICATED_TCP->if(dedicatedCircuitOpen)"Dedicated NMEA TX stopped after three failures." else "Dedicated NMEA TX write failed; reconnect in ${delay/1_000}s."
                    NmeaOutputTransportMode.TCP_SERVER->"TCP output server is not listening yet."
                    NmeaOutputTransportMode.UDP_UNICAST,NmeaOutputTransportMode.UDP_BROADCAST->"UDP NMEA TX failed."
                    NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->"Waiting for the input TCP connection."
                },
            )
        }
        return result.success
        }
    }

    fun test(value:NmeaDeviceOutputSettings,input:ConnectionProfile,sentences:List<String>,path:String):Boolean{
        if(synchronized(guard){configured.anyOutputEnabled})return false
        val startGeneration=synchronized(guard){publicationGeneration+1}
        val enabled=value.copy(transportConfigured=true,publicationEnabled=true)
        configure(enabled,input,startGeneration,"diagnostic-${System.currentTimeMillis()}")
        return try{
            val sequence=recordGenerated(path,sentences,SystemClock.elapsedRealtime(),startGeneration)?:return false
            write(input,sentences,sentences.mapNotNull{line->line.trim().removePrefix("$").take(5).takeLast(3).takeIf{it.isNotBlank()}}.toSet(),path,sequence,startGeneration)
        }finally{stop(startGeneration+1)}
    }

    fun stop(generation:Long=publicationGeneration+1)=configure(NmeaDeviceOutputSettings(),lastInputProfile,generation,null)

    private fun writeDedicated(settings:NmeaDeviceOutputSettings,input:ConnectionProfile,sentences:List<String>):TransportWriteResult{
        val result=dedicatedClient.write(settings,input,sentences)
        return TransportWriteResult(result.success,result.openedNewConnection,result.error,if(result.success)sentences.size else 0)
    }
    private fun writeTcpServer(sentences:List<String>):TransportWriteResult{
        // Binding happens on the server's IO scope. Give an explicit endpoint
        // test (and only the first publication tick) a short chance to observe
        // the listener without creating a second socket or client.
        val deadline=SystemClock.elapsedRealtime()+1_000L
        while(tcpServer.status.value.state==SharingServerState.STARTING&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(20L)
        if(tcpServer.status.value.state!=SharingServerState.RUNNING)return TransportWriteResult(false,error=tcpServer.status.value.message.ifBlank{"TCP output server could not bind."})
        var receivers=0;sentences.forEach{receivers=maxOf(receivers,tcpServer.publish(it))}
        return TransportWriteResult(true,writtenSentenceCount=0,acceptedReceivers=receivers)
    }
    private fun writeUdp(settings:NmeaDeviceOutputSettings,input:ConnectionProfile,sentences:List<String>,broadcast:Boolean):TransportWriteResult{val endpoint=NmeaOutputEndpointPolicy.resolved(settings,input);val result=udpClient.write(endpoint.first,endpoint.second,sentences,broadcast);return TransportWriteResult(result.success,result.openedNewConnection,result.error,if(result.success)sentences.size else 0)}
    private fun captureServerWritesLocked(server:com.yokuli.anchorwatch.data.sharing.NmeaSharingStatus){
        val delta=(server.sentSentences-observedServerWrites).coerceAtLeast(0L);if(delta<=0)return
        observedServerWrites=server.sentSentences
        val timestamp=java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
        server.recentWritten.takeLast(delta.coerceAtMost(RECENT_LIMIT.toLong()).toInt()).forEach{line->recent.addLast("$timestamp  [TCP_SERVER] $line");while(recent.size>RECENT_LIMIT)recent.removeFirst()}
        _status.value=_status.value.copy(writtenSentences=_status.value.writtenSentences+delta,lastWriteElapsed=server.lastOutputElapsed,bytesWritten=_status.value.bytesWritten+server.recentWritten.takeLast(delta.coerceAtMost(RECENT_LIMIT.toLong()).toInt()).sumOf{it.toByteArray(Charsets.US_ASCII).size},recentTx=recent.toList())
    }
    private fun cancelTransports(){dedicatedClient.close();udpClient.close();tcpServer.stop()}
    private fun closeLocked(){cancelTransports();consecutiveFailures=0;nextDedicatedAttemptElapsed=0L;dedicatedCircuitOpen=false}
    private val NmeaDeviceOutputSettings.anyOutputEnabled get()=anyEnabled
    companion object{private const val RECENT_LIMIT=40;private val RETRY_SECONDS=listOf(15L,30L,60L)}
}

private data class TransportWriteResult(val success:Boolean,val openedNewConnection:Boolean=false,val error:String?=null,val writtenSentenceCount:Int=if(success)1 else 0,val acceptedReceivers:Int=0)

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
    private val guard=Any()
    private val writeGuard=Any()
    private var socket:Socket?=null
    private var connectingSocket:Socket?=null
    private var endpoint:Pair<String,Int>?=null
    internal var socketFactory:()->Socket={Socket()}

    fun isConnected(host:String,port:Int)=synchronized(guard){endpoint==(host to port)&&socket?.isConnected==true&&socket?.isClosed==false}
    /** Production entry point. Endpoint ownership is checked again here so a
     * future caller cannot bypass the higher-level UI/runtime guards. */
    fun write(settings:NmeaDeviceOutputSettings,input:ConnectionProfile,sentences:List<String>):DedicatedNmeaWriteResult{
        if(NmeaOutputEndpointPolicy.opensSecondTransportOnInputEndpoint(settings,input))return DedicatedNmeaWriteResult(false,error=NmeaOutputEndpointPolicy.DUPLICATE_ENDPOINT_MESSAGE)
        val endpoint=NmeaOutputEndpointPolicy.resolved(settings,input)
        if(endpoint.first.isBlank()||endpoint.second !in 1..65535)return DedicatedNmeaWriteResult(false,error="A valid dedicated TX host and port are required.")
        return write(endpoint.first,endpoint.second,sentences)
    }
    internal fun write(host:String,port:Int,sentences:List<String>):DedicatedNmeaWriteResult{
        var opened=false
        val target=host to port
        val existing=synchronized(guard){socket?.takeIf{it.isConnected&&!it.isClosed&&endpoint==target}}
        val active=existing?:run{
            // Register the candidate before connect(). Stop can therefore close
            // and interrupt a weak server's in-flight four-second handshake.
            val candidate=socketFactory()
            synchronized(guard){
                runCatching{socket?.close()};socket=null;endpoint=null
                runCatching{connectingSocket?.close()};connectingSocket=candidate
            }
            try{
                candidate.connect(InetSocketAddress(host,port),CONNECT_TIMEOUT_MILLIS);candidate.tcpNoDelay=true
                val accepted=synchronized(guard){
                    if(connectingSocket!==candidate||candidate.isClosed)false
                    else{connectingSocket=null;socket=candidate;endpoint=target;true}
                }
                if(!accepted){runCatching{candidate.close()};return DedicatedNmeaWriteResult(false,error="NMEA TX stopped during connect.")}
                opened=true;candidate
            }catch(error:Exception){
                synchronized(guard){if(connectingSocket===candidate)connectingSocket=null}
                runCatching{candidate.close()}
                return DedicatedNmeaWriteResult(false,error=error.javaClass.simpleName+(error.message?.let{": $it"}.orEmpty()))
            }
        }
        return synchronized(writeGuard){
            if(synchronized(guard){socket!==active||endpoint!=target})return@synchronized DedicatedNmeaWriteResult(false,error="NMEA TX session is no longer active.")
            try{val output=active.getOutputStream();sentences.forEach{output.write(it.toByteArray(Charsets.US_ASCII))};output.flush();DedicatedNmeaWriteResult(true,opened)}
            catch(error:Exception){close();DedicatedNmeaWriteResult(false,error=error.javaClass.simpleName+(error.message?.let{": $it"}.orEmpty()))}
        }
    }
    fun close(){
        val sockets=synchronized(guard){
            val values=listOfNotNull(connectingSocket,socket).distinct();connectingSocket=null;socket=null;endpoint=null;values
        }
        sockets.forEach{runCatching{it.close()}}
    }
    private companion object{const val CONNECT_TIMEOUT_MILLIS=4_000}
}
