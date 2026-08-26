package com.yokuli.anchorwatch.data.nmea.output

import android.os.SystemClock
import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.nmea.Protocol
import com.yokuli.anchorwatch.data.nmea.NmeaWireBatch
import com.yokuli.anchorwatch.data.vessel.NmeaDeviceOutputSettings
import com.yokuli.anchorwatch.data.vessel.NmeaOutputTransportMode
import com.yokuli.anchorwatch.data.vessel.anyEnabled
import com.yokuli.anchorwatch.data.vessel.effectiveMotionPolicy
import com.yokuli.anchorwatch.data.vessel.effectivePressurePolicy
import com.yokuli.anchorwatch.domain.vessel.PublicationPolicy
import com.yokuli.anchorwatch.domain.vessel.PublisherOwnershipState
import com.yokuli.anchorwatch.domain.vessel.NmeaStreamReadiness
import com.yokuli.anchorwatch.domain.vessel.PublicationDecision
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
enum class NmeaWriteBackpressureState { NORMAL, CONGESTED, STALLED }

object NmeaWriteBackpressurePolicy{
    fun evaluate(activeWriteMillis:Long)=when{
        activeWriteMillis>=STALLED_AFTER_MILLIS->NmeaWriteBackpressureState.STALLED
        activeWriteMillis>=CONGESTED_AFTER_MILLIS->NmeaWriteBackpressureState.CONGESTED
        else->NmeaWriteBackpressureState.NORMAL
    }
    const val CONGESTED_AFTER_MILLIS=500L
    const val STALLED_AFTER_MILLIS=3_000L
}
enum class NmeaPacketPath { LOCAL_SENSOR_INJECTION, LEGACY_SHARING, DIAGNOSTIC_TEST, RAW_REPEATER }
enum class NmeaPacketStage { SOURCE_CHANGED, GENERATED, WRITE_STARTED, QUEUED_TO_SERVER_CLIENT, WRITTEN, DROPPED }

data class NmeaPacketPathDiagnostic(
    val publisherSessionId:String?,
    val generation:Long,
    val path:NmeaPacketPath,
    val stage:NmeaPacketStage,
    val stream:String,
    val transport:NmeaOutputTransportMode,
    val destination:String,
    val sentenceType:String,
    val generatedAtElapsed:Long,
    val writeStartedAtElapsed:Long?=null,
    val writtenAtElapsed:Long?=null,
    val sourceStableKey:String?=null,
    val inputTransportGeneration:Long?=null,
    val normalizedSentence:String?=null,
    val byteLength:Int=0,
    val outcome:String?=null,
    val failureReason:String?=null,
)

object NmeaOutputEndpointPolicy{
    fun resolved(settings:NmeaDeviceOutputSettings,input:ConnectionProfile)=when(settings.transportMode){NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->input.host to input.port;NmeaOutputTransportMode.TCP_SERVER->"local-service" to settings.outputPort;else->settings.outputHost.trim() to settings.outputPort}
    fun isValid(settings:NmeaDeviceOutputSettings,input:ConnectionProfile)=!opensSecondTransportOnInputEndpoint(settings,input)&&when(settings.transportMode){NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->input.protocol==Protocol.TCP&&input.host.isNotBlank()&&input.port in 1..65535;NmeaOutputTransportMode.TCP_SERVER->false;NmeaOutputTransportMode.DEDICATED_TCP,NmeaOutputTransportMode.UDP_UNICAST,NmeaOutputTransportMode.UDP_BROADCAST->settings.outputHost.isNotBlank()&&settings.outputPort in 1..65535}
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
    val failedSentences:Long=0,
    val droppedBatches:Long=0,
    val suppressedDecisions:Long=0,
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
    val activeWriteStartedElapsed:Long?=null,
    val lastWriteDurationMillis:Long?=null,
    val maximumWriteDurationMillis:Long=0L,
    val backpressureState:NmeaWriteBackpressureState=NmeaWriteBackpressureState.NORMAL,
    val backpressureAbortCount:Long=0L,
    val streams:Map<String,NmeaStreamTxStatus> = emptyMap(),
    val packetPathDiagnostics:List<NmeaPacketPathDiagnostic> = emptyList(),
){
    /** Compatibility for the existing compact status surface. */
    val sentencesWritten:Long get()=writtenSentences
}
data class NmeaStreamTxStatus(val lastGeneratedElapsed:Long?=null,val lastWrittenElapsed:Long?=null,val suppressionReason:String?=null,val generatedCount:Long=0,val writtenCount:Long=0,val policy:PublicationPolicy=PublicationPolicy.OFF,val ownership:PublisherOwnershipState=PublisherOwnershipState.SUPPRESSED,val dataReady:Boolean=false,val readiness:NmeaStreamReadiness=NmeaStreamReadiness.STANDBY,val droppedCount:Long=0,val lastGeneratedSequence:Long=0,val lastWrittenSequence:Long=0,val generatedRateHz:Double=0.0,val socketWriteRateHz:Double=0.0,val sourceStableKey:String?=null,val sourceEpoch:Long=0)

/**
 * Short-lived exact-sentence quarantine. It prevents the App's own RMC/HDT/XDR
 * output from being accepted as independent boat evidence when a gateway echoes
 * traffic back to the input stream.
 */
@Singleton
class NmeaOutboundLoopGuard @Inject constructor(){
    private val sent=linkedMapOf<String,ArrayDeque<Long>>()
    private val exactIdentityLastSent=linkedMapOf<String,Long>()
    private val semantic=ArrayDeque<NmeaSemanticFingerprint>()
    private val semanticQuarantineStarted=linkedMapOf<String,Pair<NmeaSemanticFingerprint,Long>>()
    private val pending=linkedMapOf<Long,PendingOutboundAttempt>()
    private var nextAttemptId=0L

    /**
     * Installs the quarantine before the bytes enter the socket. On a fast
     * full-duplex gateway an echo can reach the RX coroutine before write()
     * returns; recording only after flush therefore admits the first replay as
     * apparent Boat data. Even though local publication no longer performs
     * BACKUP arbitration, provenance must still keep that echo out of every
     * Boat-data consumer and diagnostic source list.
     */
    @Synchronized fun beginWrite(sentences:List<String>,nowElapsed:Long=SystemClock.elapsedRealtime()):Long{
        prune(nowElapsed)
        val id=++nextAttemptId
        pending[id]=PendingOutboundAttempt(sentences.map(::normalize),nowElapsed)
        while(pending.size>MAX_PENDING_ATTEMPTS)pending.remove(pending.keys.first())
        return id
    }

    /** Promote only bytes that really reached a transport. Failed attempts stop
     * quarantining immediately, so a genuine Boat source with the same value is
     * never hidden for the full converter replay window. */
    @Synchronized fun completeWrite(attemptId:Long,written:Boolean,nowElapsed:Long=SystemClock.elapsedRealtime()){
        val attempt=pending.remove(attemptId)?:return
        if(written)record(attempt.sentences,nowElapsed)
    }
    @Synchronized fun record(sentences:List<String>,nowElapsed:Long=SystemClock.elapsedRealtime()){
        prune(nowElapsed)
        sentences.forEach{sentence->
            val key=normalize(sentence);sent.getOrPut(key){ArrayDeque()}.addLast(nowElapsed);exactIdentityLastSent[key]=nowElapsed
            semanticFingerprint(sentence,nowElapsed)?.let(semantic::addLast)
        }
        while(sent.values.sumOf{it.size}>MAX_ENTRIES){val first=sent.entries.firstOrNull()?:break;first.value.pollFirst();if(first.value.isEmpty())sent.remove(first.key)}
        while(exactIdentityLastSent.size>MAX_ENTRIES){val key=exactIdentityLastSent.keys.first();exactIdentityLastSent.remove(key);if(sent[key]?.isEmpty()==true)sent.remove(key)}
        while(semantic.size>MAX_ENTRIES)semantic.removeFirst()
    }
    @Synchronized fun isRecentOutbound(sentence:String,nowElapsed:Long=SystemClock.elapsedRealtime()):Boolean{
        prune(nowElapsed)
        val exactKey=normalize(sentence)
        sent[exactKey]?.let{occurrences->
            while(occurrences.firstOrNull()?.let{nowElapsed-it>QUARANTINE_MILLIS}==true)occurrences.removeFirst()
            if(occurrences.isNotEmpty()){
                occurrences.removeFirst()
                semanticFingerprint(sentence,nowElapsed)?.let{candidate->semantic.firstOrNull{it.matches(candidate)}?.let(semantic::remove)}
                return true
            }
        }
        if(pending.values.any{attempt->nowElapsed-attempt.startedElapsedRealtime in 0L..PENDING_WRITE_MILLIS&&exactKey in attempt.sentences})return true
        // The exact App frame is known, but every transmitted occurrence has
        // already been matched. Do not let the broader semantic fallback hide
        // a third identical sentence from a real instrument.
        if(exactIdentityLastSent[exactKey]?.let{nowElapsed-it in 0L..QUARANTINE_MILLIS}==true)return false
        val candidate=semanticFingerprint(sentence,nowElapsed)?:return false
        if(pending.values.any{attempt->
            nowElapsed-attempt.startedElapsedRealtime in 0L..PENDING_WRITE_MILLIS&&
                attempt.sentences.mapNotNull{semanticFingerprint(it,attempt.startedElapsedRealtime)}.any{it.matches(candidate)}
        }){
            // The first transformed echo can race ahead of write() returning.
            // Start the same bounded semantic lease now; completeWrite() will
            // promote only a successful transport attempt into the normal
            // occurrence store, while a failed attempt leaves no occurrence.
            semanticQuarantineStarted.putIfAbsent(candidate.quarantineKey(),candidate to nowElapsed)
            return true
        }
        // Require an established outbound heartbeat before quarantining a
        // talker/checksum-transformed sentence. The lease deliberately starts
        // at the first inbound match and is never extended by a constant-value
        // publisher, so a real independent instrument reporting the same value
        // cannot be hidden forever by semantic coincidence.
        val key=candidate.quarantineKey()
        val lease=semanticQuarantineStarted[key]
        val matching=semantic.filter{it.matches(candidate)}
        val established=lease!=null&&lease.first.matches(candidate)
        if((!established&&matching.size<2)||(established&&matching.isEmpty()))return false
        val started=if(!established)nowElapsed.also{semanticQuarantineStarted[key]=candidate to it}else lease!!.second
        if(nowElapsed-started !in 0L..QUARANTINE_MILLIS)return false
        semantic.remove(matching.first())
        return true
    }
    private fun prune(now:Long){
        sent.entries.forEach{(_,occurrences)->while(occurrences.firstOrNull()?.let{now-it>QUARANTINE_MILLIS}==true)occurrences.removeFirst()}
        exactIdentityLastSent.entries.removeAll{now-it.value>QUARANTINE_MILLIS}
        sent.entries.removeAll{it.value.isEmpty()&&it.key !in exactIdentityLastSent}
        while(semantic.firstOrNull()?.let{now-it.sentElapsedRealtime>QUARANTINE_MILLIS}==true)semantic.removeFirst()
        semanticQuarantineStarted.entries.removeAll{now-it.value.second>QUARANTINE_MILLIS*2}
        pending.entries.removeAll{now-it.value.startedElapsedRealtime>PENDING_WRITE_MILLIS}
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
    companion object{
        const val QUARANTINE_MILLIS=5_000L
        const val PENDING_WRITE_MILLIS=5_000L
        private const val MAX_ENTRIES=256
        private const val MAX_PENDING_ATTEMPTS=16
    }
}

private data class PendingOutboundAttempt(val sentences:List<String>,val startedElapsedRealtime:Long)

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

/**
 * Hard publication boundary shared by every NMEA destination.
 *
 * A writer owns the read lease for the entire socket operation. Stop owns the
 * write lease before it is allowed to return. Consequently an in-flight frame
 * may finish while Stop is waiting, but it cannot reach an OutputStream after
 * Stop has returned. Keeping this primitive named and directly testable avoids
 * confusing a post-write generation check with an actual byte barrier.
 */
internal class NmeaOutputStopBarrier{
    @PublishedApi internal val lifecycle=ReentrantReadWriteLock(true)
    internal inline fun <T> withWriteLease(block:()->T):T=lifecycle.read(block)
    internal inline fun <T> stopAndJoin(block:()->T):T=lifecycle.write(block)
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
    /** Writers hold a read lease for the complete socket operation. Stop first
     * closes every transport to interrupt blocking IO, then takes the write
     * lease; once Stop returns no old writer can still reach a socket. */
    private val lifecycle=NmeaOutputStopBarrier()
    private var configured=NmeaDeviceOutputSettings()
    @Volatile private var lastInputProfile=ConnectionProfile()
    private var publicationGeneration=0L
    private var sessionId:String?=null
    private val recent=ArrayDeque<String>()
    private val generated=ArrayDeque<String>()
    private val packetDiagnostics=ArrayDeque<NmeaPacketPathDiagnostic>()
    private val _status=MutableStateFlow(NmeaTxStatus())
    val status=_status.asStateFlow()
    private var consecutiveFailures=0
    private var nextDedicatedAttemptElapsed=0L
    private var endpointBlocked=false
    private var dedicatedCircuitOpen=false
    private var activeWriteInputGeneration:Long?=null
    private var abortRequestedInputGeneration:Long?=null

    fun recordGenerated(stream:String,sentences:List<String>,now:Long=SystemClock.elapsedRealtime(),generation:Long=publicationGeneration,sourceStableKey:String?=null,path:NmeaPacketPath=NmeaPacketPath.LOCAL_SENSOR_INJECTION,inputTransportGeneration:Long?=null):Long?=synchronized(guard){
        if(generation!=publicationGeneration||!configured.anyOutputEnabled)return@synchronized null
        val old=_status.value.streams[stream]?:NmeaStreamTxStatus();val sequence=old.lastGeneratedSequence+1;val rate=old.lastGeneratedElapsed?.let{previous->(now-previous).takeIf{it>0}?.let{1_000.0/it}}?:old.generatedRateHz
        val timestamp=java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
        sentences.forEach{line->generated.addLast("$timestamp  [$stream] ${line.trim()}");while(generated.size>RECENT_LIMIT)generated.removeFirst()}
        val sourceEpoch=if(sourceStableKey!=null&&old.sourceStableKey!=null&&old.sourceStableKey!=sourceStableKey)old.sourceEpoch+1 else old.sourceEpoch
        if(sourceEpoch>old.sourceEpoch)appendPacketDiagnosticLocked(NmeaPacketPathDiagnostic(sessionId,generation,path,NmeaPacketStage.SOURCE_CHANGED,stream,configured.transportMode,destinationLocked(configured,lastInputProfile),"PUBLISHED_${stream}_SOURCE_CHANGED",now,sourceStableKey=sourceStableKey))
        val inputGeneration=inputTransportGeneration?:navigation.connectionGeneration().takeIf{configured.transportMode==NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION}
        sentences.forEach{line->appendPacketDiagnosticLocked(NmeaPacketPathDiagnostic(sessionId,generation,path,NmeaPacketStage.GENERATED,stream,configured.transportMode,destinationLocked(configured,lastInputProfile),line.trim().removePrefix("$").substringBefore(',').takeLast(3),now,sourceStableKey=sourceStableKey,inputTransportGeneration=inputGeneration,normalizedSentence=line.trim(),byteLength=line.toByteArray(Charsets.US_ASCII).size,outcome="GENERATED"))}
        _status.value=_status.value.copy(recentGenerated=generated.toList(),packetPathDiagnostics=packetDiagnostics.toList(),streams=_status.value.streams+(stream to old.copy(lastGeneratedElapsed=now,suppressionReason=null,generatedCount=old.generatedCount+1,lastGeneratedSequence=sequence,generatedRateHz=rate,sourceStableKey=sourceStableKey?:old.sourceStableKey,sourceEpoch=sourceEpoch)));sequence
    }
    fun recordDropped(stream:String,reason:String?=null){synchronized(guard){val old=_status.value.streams[stream]?:NmeaStreamTxStatus();_status.value=_status.value.copy(lastError=reason?:_status.value.lastError,droppedBatches=_status.value.droppedBatches+1,streams=_status.value.streams+(stream to old.copy(droppedCount=old.droppedCount+1)))}}
    fun recordSuppressed(stream:String,reason:String){synchronized(guard){val old=_status.value.streams[stream]?:NmeaStreamTxStatus();_status.value=_status.value.copy(suppressedDecisions=_status.value.suppressedDecisions+1,streams=_status.value.streams+(stream to old.copy(suppressionReason=reason)))}}
    fun recordDecision(stream:String,policy:PublicationPolicy,decision:PublicationDecision,dataReady:Boolean,readiness:NmeaStreamReadiness=if(dataReady)NmeaStreamReadiness.READY else NmeaStreamReadiness.STANDBY){synchronized(guard){val old=_status.value.streams[stream]?:NmeaStreamTxStatus();_status.value=_status.value.copy(streams=_status.value.streams+(stream to old.copy(policy=policy,ownership=decision.ownership,dataReady=dataReady,readiness=if(!decision.publish&&decision.ownership in setOf(PublisherOwnershipState.STANDBY_EXTERNAL_PRESENT,PublisherOwnershipState.TAKEOVER_PENDING))NmeaStreamReadiness.STANDBY else readiness,suppressionReason=decision.suppression?.name)))}}

    fun refreshTransportState(){
        var abortGeneration:Long?=null
        synchronized(guard){
            val activeSince=_status.value.activeWriteStartedElapsed
            if(activeSince!=null){
                val elapsed=(SystemClock.elapsedRealtime()-activeSince).coerceAtLeast(0L)
                val pressure=NmeaWriteBackpressurePolicy.evaluate(elapsed)
                if(pressure!=_status.value.backpressureState){
                    _status.value=_status.value.copy(
                        backpressureState=pressure,
                        connectionState=if(pressure==NmeaWriteBackpressureState.STALLED)NmeaTxConnectionState.ERROR else _status.value.connectionState,
                        lastError=if(pressure==NmeaWriteBackpressureState.STALLED)"The shared NMEA socket write has been blocked for ${elapsed} ms." else _status.value.lastError,
                        message=when(pressure){NmeaWriteBackpressureState.NORMAL->_status.value.message;NmeaWriteBackpressureState.CONGESTED->"NMEA output is congested; only the latest 1 Hz values are retained.";NmeaWriteBackpressureState.STALLED->"NMEA output write stalled; aborting this transport generation once."},
                    )
                }
                val expected=activeWriteInputGeneration
                if(pressure==NmeaWriteBackpressureState.STALLED&&configured.transportMode==NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION&&expected!=null&&abortRequestedInputGeneration!=expected){
                    abortRequestedInputGeneration=expected;abortGeneration=expected
                    _status.value=_status.value.copy(backpressureAbortCount=_status.value.backpressureAbortCount+1)
                }
            }
        }
        abortGeneration?.let{generation->navigation.abortBoatWriteStall(generation,"Shared NMEA output write exceeded ${NmeaWriteBackpressurePolicy.STALLED_AFTER_MILLIS} ms.")}
    }

    fun configure(value:NmeaDeviceOutputSettings,input:ConnectionProfile,generation:Long=publicationGeneration+1,newSessionId:String?=null){
        lastInputProfile=input
        if(!value.anyOutputEnabled){
            synchronized(guard){_status.value=_status.value.copy(connectionState=NmeaTxConnectionState.STOPPING,message="Stopping Phone/App boat output…")}
            // Close the registered connect candidate/active transports before
            // waiting for the writer lease. This is what makes Stop immediate
            // even when a fragile gateway never completes connect().
            cancelTransports()
        }
        lifecycle.stopAndJoin{
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
                        activeWriteStartedElapsed=null,backpressureState=NmeaWriteBackpressureState.NORMAL,
                        recentGenerated=emptyList(),recentTx=emptyList(),streams=emptyMap(),packetPathDiagnostics=packetDiagnostics.toList(),
                        lastSessionGenerated=previousGenerated.ifEmpty{_status.value.lastSessionGenerated},
                        lastSessionTx=previousTx.ifEmpty{_status.value.lastSessionTx},
                        publicationGeneration=generation,sessionId=null,lastSessionId=previousSession?:_status.value.lastSessionId,
                        stoppedAtElapsed=SystemClock.elapsedRealtime(),
                    )
                    activeWriteInputGeneration=null;abortRequestedInputGeneration=null
                    return@synchronized
                }
                configured=value;sessionId=newSessionId
                if(value.transportMode==NmeaOutputTransportMode.TCP_SERVER){
                    endpointBlocked=true;closeLocked()
                    _status.value=_status.value.copy(enabled=false,mode=value.transportMode,endpointHost=endpoint.first,endpointPort=endpoint.second,connectionState=NmeaTxConnectionState.ERROR,lastError="A listening TCP server is configured in Phone NMEA service, not Phone/App boat output.",message="Legacy TCP-server route blocked.",publicationGeneration=generation,sessionId=null)
                    return@synchronized
                }
                if(NmeaOutputEndpointPolicy.opensSecondTransportOnInputEndpoint(value,input)){
                    endpointBlocked=true;closeLocked()
                    _status.value=_status.value.copy(enabled=false,mode=value.transportMode,endpointHost=endpoint.first,endpointPort=endpoint.second,connectionState=NmeaTxConnectionState.ERROR,lastError=NmeaOutputEndpointPolicy.DUPLICATE_ENDPOINT_MESSAGE,message="TX blocked before opening a socket.",publicationGeneration=generation,sessionId=null)
                    return@synchronized
                }
                endpointBlocked=false
                if(endpointChanged)closeLocked()
                if(previousSession!=newSessionId){generated.clear();recent.clear()}
                val state=when{
                    value.transportMode==NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->NmeaTxConnectionState.DISCONNECTED
                    dedicatedClient.isConnected(endpoint.first,endpoint.second)->NmeaTxConnectionState.CONNECTED
                    else->NmeaTxConnectionState.DISCONNECTED
                }
                activeWriteInputGeneration=null;abortRequestedInputGeneration=null
                _status.value=_status.value.copy(enabled=true,mode=value.transportMode,endpointHost=endpoint.first,endpointPort=endpoint.second,connectionState=state,message=when(value.transportMode){NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->"Waiting for the input TCP connection.";NmeaOutputTransportMode.DEDICATED_TCP->"Dedicated boat-network TX ready.";NmeaOutputTransportMode.TCP_SERVER->"Legacy TCP-server route blocked.";NmeaOutputTransportMode.UDP_UNICAST->"UDP unicast destination ready.";NmeaOutputTransportMode.UDP_BROADCAST->"UDP broadcast destination ready."},lastError=null,activeWriteStartedElapsed=null,backpressureState=NmeaWriteBackpressureState.NORMAL,recentGenerated=generated.toList(),recentTx=recent.toList(),streams=if(previousSession==newSessionId)_status.value.streams else emptyMap(),publicationGeneration=generation,sessionId=newSessionId,stoppedAtElapsed=null)
            }
        }
    }

    fun currentInputTransportGeneration()=navigation.connectionGeneration()

    fun write(input:ConnectionProfile,sentences:List<String>,sentenceTypes:Set<String>,logicalStream:String?=null,generationSequence:Long?=null,generation:Long=publicationGeneration,sourceStableKey:String?=null,path:NmeaPacketPath=NmeaPacketPath.LOCAL_SENSOR_INJECTION,expectedInputTransportGeneration:Long?=null):Boolean=lifecycle.withWriteLease{
        if(sentences.isEmpty())return false
        val now=SystemClock.elapsedRealtime();val bytes=sentences.sumOf{it.toByteArray(Charsets.US_ASCII).size}.toLong()
        val invalid=sentences.firstOrNull{!NmeaGeneratedSentenceValidator.isValid(it)}
        if(invalid!=null){
            val stream=logicalStream?:sentenceTypes.sorted().joinToString("+").ifBlank{"SOCKET"}
            synchronized(guard){
                appendPacketDiagnosticLocked(NmeaPacketPathDiagnostic(sessionId,generation,path,NmeaPacketStage.DROPPED,stream,configured.transportMode,destinationLocked(configured,input),invalid.trim().removePrefix("$").substringBefore(',').takeLast(3),now,sourceStableKey=sourceStableKey,inputTransportGeneration=expectedInputTransportGeneration,normalizedSentence=invalid.trim(),byteLength=invalid.toByteArray(Charsets.US_ASCII).size,outcome="REJECTED",failureReason="INVALID_GENERATED_SENTENCE"))
                recordDropped(stream,"Generated NMEA sentence failed framing/checksum/length validation.")
                _status.value=_status.value.copy(packetPathDiagnostics=packetDiagnostics.toList())
            }
            return false
        }
        val settings=synchronized(guard){
            if(generation!=publicationGeneration||!configured.anyOutputEnabled||endpointBlocked)return false
            _status.value=_status.value.copy(attemptedSentences=_status.value.attemptedSentences+sentences.size,lastAttemptElapsed=now,sentenceTypes=_status.value.sentenceTypes+sentenceTypes)
            if(configured.transportMode==NmeaOutputTransportMode.DEDICATED_TCP&&dedicatedCircuitOpen){
                _status.value=_status.value.copy(connectionState=NmeaTxConnectionState.ERROR,failedSentences=_status.value.failedSentences+sentences.size,message="Dedicated NMEA TX retries stopped to protect the server.",lastError="Three consecutive TX connection/write failures opened the safety circuit. Stop output and verify the dedicated TX port before starting again.")
                return false
            }
            if(configured.transportMode==NmeaOutputTransportMode.DEDICATED_TCP&&now<nextDedicatedAttemptElapsed){
                _status.value=_status.value.copy(connectionState=NmeaTxConnectionState.ERROR,failedSentences=_status.value.failedSentences+sentences.size,message="Dedicated NMEA TX is backing off before reconnect.")
                return false
            }
            if(configured.transportMode==NmeaOutputTransportMode.DEDICATED_TCP){
                val endpoint=NmeaOutputEndpointPolicy.resolved(configured,input)
                _status.value=_status.value.copy(connectionState=if(dedicatedClient.isConnected(endpoint.first,endpoint.second))NmeaTxConnectionState.CONNECTED else NmeaTxConnectionState.CONNECTING,message="Connecting dedicated NMEA TX…")
            }
            val stream=logicalStream?:sentenceTypes.sorted().joinToString("+").ifBlank{"SOCKET"}
            sentences.forEach{line->appendPacketDiagnosticLocked(NmeaPacketPathDiagnostic(sessionId,generation,path,NmeaPacketStage.WRITE_STARTED,stream,configured.transportMode,destinationLocked(configured,input),line.trim().removePrefix("$").substringBefore(',').takeLast(3),now,writeStartedAtElapsed=now,sourceStableKey=sourceStableKey,inputTransportGeneration=expectedInputTransportGeneration,normalizedSentence=line.trim(),byteLength=line.toByteArray(Charsets.US_ASCII).size,outcome="ATTEMPTED"))}
            activeWriteInputGeneration=expectedInputTransportGeneration
            _status.value=_status.value.copy(activeWriteStartedElapsed=now,backpressureState=NmeaWriteBackpressureState.NORMAL,packetPathDiagnostics=packetDiagnostics.toList())
            configured
        }
        // Network IO deliberately happens outside guard. A weak or silent
        // gateway may consume the full connect timeout, but it must never block
        // status reads, policy changes, Stop, or another runtime decision.
        // Install an in-flight echo barrier before touching the socket. Some
        // gateways return the converted first frame on RX before flush returns.
        val echoAttempt=loopGuard.beginWrite(sentences,now)
        val result=when(settings.transportMode){
            NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->navigation.writeToBoatExpected(sentences,expectedInputTransportGeneration).let{write->TransportWriteResult(write.success,error=write.error,writtenSentenceCount=write.writtenSentenceCount,inputTransportGeneration=write.actualGeneration,failureReason=write.failure.name)}
            NmeaOutputTransportMode.DEDICATED_TCP->writeDedicated(settings,input,sentences)
            NmeaOutputTransportMode.TCP_SERVER->TransportWriteResult(false,error="TCP server is owned by the independent Phone NMEA service.")
            NmeaOutputTransportMode.UDP_UNICAST->writeUdp(settings,input,sentences,false)
            NmeaOutputTransportMode.UDP_BROADCAST->writeUdp(settings,input,sentences,true)
        }
        val completedAt=SystemClock.elapsedRealtime();val writeDuration=(completedAt-now).coerceAtLeast(0L)
        // Promote only bytes the transport reports as written. This call must
        // precede state bookkeeping so an RX callback racing this completion can
        // never observe a gap between pending and confirmed quarantine.
        loopGuard.completeWrite(echoAttempt,result.success&&result.writtenSentenceCount>0,completedAt)
        synchronized(guard){
        activeWriteInputGeneration=null
        _status.value=_status.value.copy(activeWriteStartedElapsed=null,lastWriteDurationMillis=writeDuration,maximumWriteDurationMillis=maxOf(_status.value.maximumWriteDurationMillis,writeDuration),backpressureState=NmeaWriteBackpressureState.NORMAL)
        if(configured!=settings||generation!=publicationGeneration)return false
        if(result.success){
            consecutiveFailures=0;nextDedicatedAttemptElapsed=0L;dedicatedCircuitOpen=false
            val timestamp=java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
            val streamLabel=logicalStream?:sentenceTypes.sorted().joinToString("+").ifBlank{"SOCKET"}
            if(result.writtenSentenceCount>0)sentences.forEach{line->recent.addLast("$timestamp  [$streamLabel] ${line.trim()}");while(recent.size>RECENT_LIMIT)recent.removeFirst()}
            val logicalStreams=buildSet{if(sentenceTypes.any{it in setOf("RMC","GGA","VTG","ZDA")})add("POSITION");if(sentenceTypes.any{it in setOf("HDT","HDG","HDM")})add("HEADING");if("ROT" in sentenceTypes||("XDR" in sentenceTypes&&settings.effectiveMotionPolicy!=PublicationPolicy.OFF))add("MOTION");if("XDR" in sentenceTypes&&settings.effectivePressurePolicy!=PublicationPolicy.OFF)add("PRESSURE");if(sentenceTypes.any{it in setOf("MWD","MWV","VWT")})add("DERIVED_WIND");if("YOK" in sentenceTypes)add("STATUS")}
            val streamUpdates=(sentenceTypes+logicalStreams+listOfNotNull(logicalStream)).fold(_status.value.streams){map,type->val old=map[type]?:NmeaStreamTxStatus();val rate=old.lastWrittenElapsed?.let{previous->(completedAt-previous).takeIf{it>0}?.let{1_000.0/it}}?:old.socketWriteRateHz;map+(type to old.copy(lastWrittenElapsed=completedAt,suppressionReason=null,writtenCount=old.writtenCount+1,lastWrittenSequence=if(type==logicalStream&&generationSequence!=null)generationSequence else old.lastGeneratedSequence,socketWriteRateHz=rate,readiness=NmeaStreamReadiness.PUBLISHING))}
            val hasReceiverWrite=result.writtenSentenceCount>0
            val stage=if(hasReceiverWrite)NmeaPacketStage.WRITTEN else NmeaPacketStage.QUEUED_TO_SERVER_CLIENT
            if(hasReceiverWrite||result.acceptedReceivers>0)sentences.forEach{line->appendPacketDiagnosticLocked(NmeaPacketPathDiagnostic(sessionId,generation,path,stage,streamLabel,settings.transportMode,destinationLocked(settings,input),line.trim().removePrefix("$").substringBefore(',').takeLast(3),now,writeStartedAtElapsed=now,writtenAtElapsed=completedAt.takeIf{hasReceiverWrite},sourceStableKey=sourceStableKey,inputTransportGeneration=result.inputTransportGeneration?:expectedInputTransportGeneration,normalizedSentence=line.trim(),byteLength=line.toByteArray(Charsets.US_ASCII).size,outcome=if(hasReceiverWrite)"WRITTEN" else "QUEUED"))}
            _status.value=_status.value.copy(connectionState=NmeaTxConnectionState.CONNECTED,writtenSentences=_status.value.writtenSentences+result.writtenSentenceCount,bytesWritten=_status.value.bytesWritten+if(hasReceiverWrite)bytes else 0,lastWriteElapsed=completedAt.takeIf{hasReceiverWrite}?:_status.value.lastWriteElapsed,reconnectCount=_status.value.reconnectCount+if(result.openedNewConnection)1 else 0,lastError=null,message=when{hasReceiverWrite->"Socket TX successful; server receipt is not confirmed.";result.acceptedReceivers>0->"Queued to ${result.acceptedReceivers} connected receiver(s); awaiting socket flush.";else->"TCP output server is listening; no receiver is connected."},recentTx=recent.toList(),streams=if(hasReceiverWrite)streamUpdates else _status.value.streams,packetPathDiagnostics=packetDiagnostics.toList())
        }else if(settings.transportMode==NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION){
            val streamLabel=logicalStream?:sentenceTypes.sorted().joinToString("+").ifBlank{"SOCKET"}
            sentences.forEach{line->appendPacketDiagnosticLocked(NmeaPacketPathDiagnostic(sessionId,generation,path,NmeaPacketStage.DROPPED,streamLabel,settings.transportMode,destinationLocked(settings,input),line.trim().removePrefix("$").substringBefore(',').takeLast(3),now,writeStartedAtElapsed=now,sourceStableKey=sourceStableKey,inputTransportGeneration=result.inputTransportGeneration?:expectedInputTransportGeneration,normalizedSentence=line.trim(),byteLength=line.toByteArray(Charsets.US_ASCII).size,outcome="DROPPED",failureReason=result.failureReason?:result.error))}
            val oldStream=_status.value.streams[streamLabel]?:NmeaStreamTxStatus()
            _status.value=_status.value.copy(connectionState=NmeaTxConnectionState.DISCONNECTED,failedSentences=_status.value.failedSentences+sentences.size,droppedBatches=_status.value.droppedBatches+1,lastError=result.error?:"Input TCP connection is not writable.",message=if(result.failureReason==com.yokuli.anchorwatch.data.nmea.NmeaTransportWriteFailure.STALE_TRANSPORT_GENERATION.name)"A queued batch was dropped across an input reconnect; new data will continue on the replacement connection." else "Waiting for the input TCP connection.",packetPathDiagnostics=packetDiagnostics.toList(),streams=_status.value.streams+(streamLabel to oldStream.copy(droppedCount=oldStream.droppedCount+1)))
        }else{
            consecutiveFailures=(consecutiveFailures+1).coerceAtMost(RETRY_SECONDS.size)
            val dedicated=settings.transportMode==NmeaOutputTransportMode.DEDICATED_TCP
            dedicatedCircuitOpen=dedicated&&consecutiveFailures>=RETRY_SECONDS.size
            val delay=if(dedicated&&!dedicatedCircuitOpen)RETRY_SECONDS[consecutiveFailures-1]*1_000L else 0L
            if(delay>0)nextDedicatedAttemptElapsed=now+delay else if(dedicatedCircuitOpen)nextDedicatedAttemptElapsed=Long.MAX_VALUE
            _status.value=_status.value.copy(
                connectionState=NmeaTxConnectionState.ERROR,
                failedSentences=_status.value.failedSentences+sentences.size,
                reconnectCount=_status.value.reconnectCount+if(result.openedNewConnection)1 else 0,
                lastError=result.error,
                message=when(settings.transportMode){
                    NmeaOutputTransportMode.DEDICATED_TCP->if(dedicatedCircuitOpen)"Dedicated NMEA TX stopped after three failures." else "Dedicated NMEA TX write failed; reconnect in ${delay/1_000}s."
                    NmeaOutputTransportMode.TCP_SERVER->"Legacy TCP-server output route is disabled."
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
            val sequence=recordGenerated(path,sentences,SystemClock.elapsedRealtime(),startGeneration,null,NmeaPacketPath.DIAGNOSTIC_TEST)?:return false
            write(input,sentences,sentences.mapNotNull{line->line.trim().removePrefix("$").take(5).takeLast(3).takeIf{it.isNotBlank()}}.toSet(),path,sequence,startGeneration,null,NmeaPacketPath.DIAGNOSTIC_TEST)
        }finally{stop(startGeneration+1)}
    }

    fun stop(generation:Long=publicationGeneration+1)=configure(NmeaDeviceOutputSettings(),lastInputProfile,generation,null)

    private fun writeDedicated(settings:NmeaDeviceOutputSettings,input:ConnectionProfile,sentences:List<String>):TransportWriteResult{
        val result=dedicatedClient.write(settings,input,sentences)
        return TransportWriteResult(result.success,result.openedNewConnection,result.error,if(result.success)sentences.size else 0)
    }
    private fun writeUdp(settings:NmeaDeviceOutputSettings,input:ConnectionProfile,sentences:List<String>,broadcast:Boolean):TransportWriteResult{val endpoint=NmeaOutputEndpointPolicy.resolved(settings,input);val result=udpClient.write(endpoint.first,endpoint.second,sentences,broadcast);return TransportWriteResult(result.success,result.openedNewConnection,result.error,if(result.success)sentences.size else 0)}
    private fun cancelTransports(){dedicatedClient.close();udpClient.close()}
    private fun closeLocked(){cancelTransports();consecutiveFailures=0;nextDedicatedAttemptElapsed=0L;dedicatedCircuitOpen=false}
    private fun appendPacketDiagnosticLocked(value:NmeaPacketPathDiagnostic){packetDiagnostics.addLast(value);while(packetDiagnostics.size>PACKET_DIAGNOSTIC_LIMIT)packetDiagnostics.removeFirst()}
    private fun destinationLocked(settings:NmeaDeviceOutputSettings,input:ConnectionProfile):String{val endpoint=NmeaOutputEndpointPolicy.resolved(settings,input);return "${endpoint.first}:${endpoint.second}"}
    private val NmeaDeviceOutputSettings.anyOutputEnabled get()=anyEnabled
    companion object{private const val RECENT_LIMIT=40;private const val PACKET_DIAGNOSTIC_LIMIT=160;private val RETRY_SECONDS=listOf(15L,30L,60L)}
}

private data class TransportWriteResult(val success:Boolean,val openedNewConnection:Boolean=false,val error:String?=null,val writtenSentenceCount:Int=if(success)1 else 0,val acceptedReceivers:Int=0,val inputTransportGeneration:Long?=null,val failureReason:String?=null)

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
            try{val output=active.getOutputStream();output.write(NmeaWireBatch.encode(sentences));output.flush();DedicatedNmeaWriteResult(true,opened)}
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
