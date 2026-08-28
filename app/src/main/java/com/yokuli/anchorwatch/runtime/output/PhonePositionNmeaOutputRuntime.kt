package com.yokuli.anchorwatch.runtime.output

import android.os.SystemClock
import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.nmea.output.NmeaDeviceOutputConnection
import com.yokuli.anchorwatch.data.nmea.output.NmeaOutputEndpointPolicy
import com.yokuli.anchorwatch.data.nmea.output.NmeaTxStatus
import com.yokuli.anchorwatch.data.sharing.NmeaOutputMux
import com.yokuli.anchorwatch.data.vessel.*
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.vessel.*
import com.yokuli.anchorwatch.location.vessel.*
import com.yokuli.anchorwatch.runtime.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

typealias PhonePositionOutputStatus=NmeaTxStatus

/** The only configuration visible to the live publisher. Legacy BACKUP values
 * are normalized to ALWAYS before this boundary. A destination changes only
 * where the Phone/App-owned bytes go, never which data sources may publish. */
data class NmeaPublisherConfig(
    val transportMode:NmeaOutputTransportMode=NmeaOutputTransportMode.DEDICATED_TCP,
    val host:String="",
    val port:Int=10110,
    val headingFormat:PhoneHeadingOutputFormat=PhoneHeadingOutputFormat.HDT_TRUE,
    val phonePositionEnabled:Boolean=false,
    val phoneHeadingEnabled:Boolean=false,
    val phoneRateOfTurnEnabled:Boolean=false,
    val phoneAttitudeEnabled:Boolean=false,
    val includePressure:Boolean=false,
    val includeDerivedWind:Boolean=false,
    val transportConfigured:Boolean=false,
    val publicationEnabled:Boolean=false,
){
    val running:Boolean get()=transportConfigured&&publicationEnabled
    fun asOutputSettings()=NmeaDeviceOutputSettings(
        transportMode=transportMode,outputHost=host,outputPort=port,
        phoneHeadingFormat=headingFormat,phonePositionEnabled=phonePositionEnabled,phoneHeadingEnabled=phoneHeadingEnabled,
        phoneRateOfTurnEnabled=phoneRateOfTurnEnabled,phoneAttitudeEnabled=phoneAttitudeEnabled,phonePressureEnabled=includePressure,includePressure=includePressure,
        includeDerivedWind=includeDerivedWind,transportConfigured=transportConfigured,
        publicationEnabled=publicationEnabled,
    ).publisherConfiguration()
    companion object{
        fun from(value:NmeaDeviceOutputSettings):NmeaPublisherConfig{
            val productFeed=value.publisherConfiguration()
            return NmeaPublisherConfig(
                transportMode=productFeed.transportMode,
                host=productFeed.outputHost.trim(),
                port=productFeed.outputPort,
                headingFormat=productFeed.phoneHeadingFormat,
                phonePositionEnabled=productFeed.phonePositionEnabled,
                phoneHeadingEnabled=productFeed.phoneHeadingEnabled,
                phoneRateOfTurnEnabled=productFeed.phoneRateOfTurnEnabled,
                phoneAttitudeEnabled=productFeed.phoneAttitudeEnabled,
                includePressure=productFeed.includePressure,
                includeDerivedWind=productFeed.includeDerivedWind,
                transportConfigured=productFeed.transportConfigured,
                publicationEnabled=productFeed.publicationEnabled,
            )
        }
    }
}

/** Bounded latest-value queue: each independent stream owns one slot and the
 * single writer drains slots fairly in insertion order. */
internal class LatestPerStreamQueue<T>(private val key:(T)->String){
    private val values=linkedMapOf<String,T>()
    @Synchronized fun offer(value:T):T?=values.put(key(value),value)
    @Synchronized fun poll():T?{val first=values.entries.firstOrNull()?:return null;values.remove(first.key);return first.value}
    @Synchronized fun clear():List<T>{val old=values.values.toList();values.clear();return old}
    @Synchronized fun size()=values.size
}

/** A slow gateway must not trigger a catch-up burst when a blocked write
 * finally returns. Healthy writes keep fixed-rate 1 Hz starts; a write that
 * crossed the congestion boundary gets one full recovery period after it
 * completes. Pending stream slots continue to collapse to their newest value
 * while the actor waits. */
object NmeaWireAttemptCadence{
    const val PERIOD_MILLIS=1_000L
    fun nextAllowed(startedAt:Long,completedAt:Long):Long{
        val duration=(completedAt-startedAt).coerceAtLeast(0L)
        return if(duration>=com.yokuli.anchorwatch.data.nmea.output.NmeaWriteBackpressurePolicy.CONGESTED_AFTER_MILLIS)completedAt+PERIOD_MILLIS else startedAt+PERIOD_MILLIS
    }
}

/** The single Phone/App-owned NMEA feed engine. Acquisition stays independent
 * from publication. Every transport receives the same locally-owned sensor and
 * App-derived values; transport choice can never enable Boat-data forwarding. */
@Singleton
class AnchorWatchNmeaPublisher @Inject constructor(
    private val outputConnection:NmeaDeviceOutputConnection,
    private val vesselDataHub:VesselDataHub,
    private val vesselPositionRepository:VesselPositionRepository,
    private val mux:NmeaOutputMux,
    private val encoder:AnchorWatchNmeaFeedEncoder,
    private val resources:RuntimeResourceManager,
    vesselAttitude:PhoneVesselAttitudeRepository,
    mountCalibration:VesselMountCalibrationRepository,
){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    @Volatile var enabled=false;private set
    @Volatile private var config=NmeaPublisherConfig();@Volatile private var inputProfile=ConnectionProfile()
    @Volatile private var mountState=PhoneVesselMountState.UNCALIBRATED;@Volatile private var calibration=VesselMountCalibration()
    private val heartbeat=AnchorWatchNmeaHeartbeat()
    private val sessionGate=NmeaPublicationSessionGate()
    private data class OutputBatch(val generation:Long,val profile:ConnectionProfile,val stream:String,val sentences:List<String>,val types:Set<String>,val sequence:Long,val sourceStableKey:String?,val inputTransportGeneration:Long?,val path:com.yokuli.anchorwatch.data.nmea.output.NmeaPacketPath)
    private val pending=LatestPerStreamQueue<OutputBatch>{it.stream}
    private val writerWake=Channel<Unit>(capacity=Channel.CONFLATED)
    val status=outputConnection.status
    init{
        scope.launch{combine(vesselAttitude.mountState,mountCalibration.calibration){mount,value->mount to value}.collect{(mount,value)->mountState=mount;calibration=value}}
        // One socket writer actor, with one latest-value slot per stream. Every
        // stream has the same 1 Hz product cadence. All values due on that tick
        // are drained into one wire write/flush, while a blocked gateway can
        // retain only the newest value for each stream and never a replay log.
        scope.launch{
            var nextWireAttemptElapsed=0L
            for(ignored in writerWake){
                val wait=(nextWireAttemptElapsed-SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                if(wait>0)delay(wait)
                // Drain only after the cadence wait, so any values accumulated
                // during congestion are replaced in-place before encoding the
                // next physical wire payload. There is no catch-up replay.
                val drained=buildList{while(true){val value=pending.poll()?:break;add(value)}}
                if(drained.isEmpty())continue
                val currentInputGeneration=outputConnection.currentInputTransportGeneration()
                val deliverable=drained.filter{batch->
                    val accepted=sessionGate.accepts(batch.generation)&&
                        (batch.inputTransportGeneration==null||batch.inputTransportGeneration==currentInputGeneration)
                    if(!accepted)outputConnection.recordDropped(batch.stream,"Queued NMEA value expired before the next 1 Hz wire batch.")
                    accepted
                }
                if(deliverable.isEmpty())continue
                val first=deliverable.first();val writeStarted=SystemClock.elapsedRealtime()
                val success=outputConnection.write(
                    input=first.profile,
                    sentences=deliverable.flatMap{it.sentences},
                    sentenceTypes=deliverable.flatMap{it.types}.toSet(),
                    logicalStream=null,
                    generationSequence=null,
                    generation=first.generation,
                    sourceStableKey=deliverable.mapNotNull{it.sourceStableKey}.distinct().joinToString("+").takeIf{it.isNotBlank()},
                    path=first.path,
                    expectedInputTransportGeneration=first.inputTransportGeneration,
                )
                val writeCompleted=SystemClock.elapsedRealtime()
                nextWireAttemptElapsed=NmeaWireAttemptCadence.nextAllowed(writeStarted,writeCompleted)
                if(!success)deliverable.forEach{outputConnection.recordDropped(it.stream,"The coalesced 1 Hz socket batch was not written.")}
            }
        }
        scope.launch{while(isActive){delay(50L);publishDue(SystemClock.elapsedRealtime())}}
    }

    @Synchronized fun configure(
        value:NmeaDeviceOutputSettings,
        input:ConnectionProfile=inputProfile,
        knownReadiness:PhoneVesselOutputReadiness?=null,
    ){
        val automaticValue=NmeaOutputEndpointPolicy.automatic(value,input)
        val requested=NmeaPublisherConfig.from(automaticValue);val requestedSettings=requested.asOutputSettings()
        // Readiness is a hard gate for beginning a formal publication session.
        // Once that session exists, a transient mount warning degrades only the
        // affected local streams below; it must not flap the shared socket or
        // make independent Phone GNSS/pressure data disappear.
        // The coordinator and ViewModel observe the same calibration streams,
        // but their collectors are not ordered. Accept its atomic readiness
        // snapshot so a valid first Start cannot be rejected by this runtime's
        // one-emission-old cache and then require another tap.
        val readiness=knownReadiness?:PhoneVesselOutputReadinessPolicy.evaluate(calibration,mountState)
        val readinessBlocksNewSession=FormalOutputSessionReadinessPolicy.blocksStart(requested.running,enabled,readiness)
        val safe=if(readinessBlocksNewSession||requested.running&&NmeaOutputEndpointPolicy.opensSecondTransportOnInputEndpoint(requestedSettings,input))requested.copy(publicationEnabled=false) else requested
        if(safe==config&&input==inputProfile&&enabled==safe.running)return
        pending.clear().forEach{outputConnection.recordDropped(it.stream)}
        val generation=if(safe.running)sessionGate.start()else sessionGate.stop()
        config=safe;inputProfile=input;enabled=safe.running;heartbeat.reset();encoder.reset()
        val effective=safe.asOutputSettings()
        outputConnection.configure(effective,input,generation,if(enabled)java.util.UUID.randomUUID().toString().take(8)else null)
        if(enabled)resources.set(RuntimeOwner.PHONE_NMEA_OUTPUT,RuntimeRequirement(needsSystemLocation=safe.phonePositionEnabled,needsNmeaTransport=false,needsWakeLock=true,needsWifiLock=true,needsPhoneHeading=safe.phoneHeadingEnabled,needsPhoneMotion=safe.phoneRateOfTurnEnabled||safe.phoneAttitudeEnabled,needsPhonePressure=safe.includePressure))
        else resources.release(RuntimeOwner.PHONE_NMEA_OUTPUT)
    }
    @Synchronized fun configure(value:Boolean)=configure(NmeaDeviceOutputSettings(transportConfigured=value,publicationEnabled=value))

    private fun publishDue(now:Long){
        val configured=config;val generation=sessionGate.current();if(!configured.running||!sessionGate.accepts(generation))return
        outputConnection.refreshTransportState()
        val inputTransportGeneration=outputConnection.currentInputTransportGeneration()
        val snapshot=vesselDataHub.snapshot.value;val phoneFix=vesselPositionRepository.acceptedPhoneFix.value
        val effectiveSettings=configured.asOutputSettings()
        val prepared=heartbeat.due(now).mapNotNull{stream->
            val name=stream.name
            val policy=effectiveSettings.policyFor(stream)
            if(policy==PublicationPolicy.OFF){
                outputConnection.recordDecision(name,policy,PublicationDecision(false,PublisherOwnershipState.SUPPRESSED),false,NmeaStreamReadiness.STANDBY)
                outputConnection.recordSuppressed(name,"USER_DISABLED")
                return@mapNotNull null
            }
            val runtimeSuppression=PhoneOwnedRuntimeSafety.suppression(configured.transportMode,stream,mountState)
            if(runtimeSuppression!=null){
                outputConnection.recordDecision(name,policy,PublicationDecision(false,PublisherOwnershipState.SUPPRESSED),false,NmeaStreamReadiness.WAITING_CALIBRATION)
                outputConnection.recordSuppressed(name,runtimeSuppression)
                return@mapNotNull null
            }
            val batch=encoder.encode(stream,snapshot,effectiveSettings,now,phoneFix,inputTransportGeneration,inputProfile.stableId,calibration,mountState)
            val ready=batch.sentences.isNotEmpty();val ownership=when{ready&&batch.sourceConflict->PublisherOwnershipState.SOURCE_CONFLICT;ready->PublisherOwnershipState.PHONE_ACTIVE;else->PublisherOwnershipState.SUPPRESSED}
            val publicationDecision=PublicationDecision(ready,ownership)
            // Locally-owned Phone/App values never yield merely because the
            // receiving boat network also has a value for the same field. The
            // receiving instrument owns source selection; this publisher owns
            // only validity, provenance and a stable heartbeat.
            outputConnection.recordDecision(name,policy,publicationDecision,ready,if(ready)NmeaStreamReadiness.READY else NmeaStreamReadinessPolicy.forSuppression(stream,batch.suppressionReason))
            if(ready)prepare(generation,name,batch.sentences,now,batch.sourceStableKey,inputTransportGeneration)else{outputConnection.recordSuppressed(name,batch.suppressionReason?:"NO_COMPLETE_VALUE");null}
        }
        prepared.forEach{batch->pending.offer(batch)?.let{outputConnection.recordDropped(it.stream,"A newer 1 Hz value replaced this blocked stream batch.")}}
        if(prepared.isNotEmpty())writerWake.trySend(Unit)
    }

    private fun prepare(generation:Long,stream:String,sentences:List<String>,now:Long,sourceStableKey:String?,inputTransportGeneration:Long):OutputBatch?{
        if(sentences.isEmpty()||!sessionGate.accepts(generation))return null
        val transportGeneration=inputTransportGeneration.takeIf{config.transportMode==NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION}
        val path=com.yokuli.anchorwatch.data.nmea.output.NmeaPacketPath.LOCAL_SENSOR_INJECTION
        val sequence=outputConnection.recordGenerated(stream,sentences,now,generation,sourceStableKey,path,inputTransportGeneration=transportGeneration)?:return null
        return OutputBatch(generation,inputProfile,stream,sentences,sentences.mapNotNull(mux::sentenceType).toSet(),sequence,sourceStableKey,transportGeneration,path)
    }

    fun testOutput(value:NmeaDeviceOutputSettings=config.asOutputSettings(),input:ConnectionProfile=inputProfile):Boolean{
        if(enabled)return false
        return outputConnection.test(value.publisherConfiguration(),input,listOf(mux.diagnostic()),"TEST_TX")
    }
    fun testKnownGoodHdg(value:NmeaDeviceOutputSettings=config.asOutputSettings(),input:ConnectionProfile=inputProfile):Boolean{
        if(enabled)return false
        return outputConnection.test(value.publisherConfiguration(),input,List(5){mux.diagnosticMagneticHeading(123.4)},"TEST_TX_HDG")
    }
    fun shutdown(){configure(NmeaDeviceOutputSettings(),inputProfile);outputConnection.stop(sessionGate.stop())}
}

/** Source-compatible name retained while callers and backup DTOs migrate. */
typealias PhonePositionNmeaOutputRuntime=AnchorWatchNmeaPublisher

object FormalOutputSessionReadinessPolicy{
    /** Calibration is stream-local. It suppresses HDT/Motion but must not stop
     * an explicitly requested socket carrying independent Position/Pressure. */
    fun blocksStart(@Suppress("UNUSED_PARAMETER") requestedRunning:Boolean,@Suppress("UNUSED_PARAMETER") currentlyEnabled:Boolean,@Suppress("UNUSED_PARAMETER") readiness:PhoneVesselOutputReadiness)=false
}

/** Runtime degradation is stream-local. Heading alignment is independent from
 * Trip attitude capture: moving the handset may make the user-aligned heading
 * temporarily represent the handset, but it must not flap the transport.
 * Only vessel-frame Motion is suppressed when no confirmed Trip segment is
 * active. */
object PhoneOwnedRuntimeSafety{
    fun suppression(mode:NmeaOutputTransportMode,stream:AnchorWatchNmeaStream,mountState:PhoneVesselMountState):String?{
        @Suppress("UNUSED_VARIABLE") val destinationOnly=mode
        if(stream !in setOf(AnchorWatchNmeaStream.RATE_OF_TURN,AnchorWatchNmeaStream.ATTITUDE))return null
        if(mountState==PhoneVesselMountState.VESSEL_MOUNTED)return null
        return if(mountState==PhoneVesselMountState.MOUNT_SUSPECT)"MOUNT_SUSPECT" else "PHONE_NOT_MOUNTED"
    }
}

fun NmeaDeviceOutputSettings.publisherConfiguration():NmeaDeviceOutputSettings{
    return copy(
    purpose=NmeaOutputPurpose.BOAT_BUS_INJECTION,
    phonePositionEnabled=phonePositionEnabled,
    phoneHeadingEnabled=phoneHeadingEnabled,
    phoneMotionEnabled=phoneRateOfTurnEnabled||phoneAttitudeEnabled,
    phoneRateOfTurnEnabled=phoneRateOfTurnEnabled,
    phoneAttitudeEnabled=phoneAttitudeEnabled,
    phonePressureEnabled=phonePressureEnabled,
    proprietaryStatusEnabled=false,
    positionPolicy=if(phonePositionEnabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF,
    headingPolicy=if(phoneHeadingEnabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF,
    motionPolicy=if(phoneRateOfTurnEnabled||phoneAttitudeEnabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF,
    rateOfTurnPolicy=if(phoneRateOfTurnEnabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF,
    attitudePolicy=if(phoneAttitudeEnabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF,
    pressurePolicy=if(phonePressureEnabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF,
    derivedWindPolicy=if(derivedWindPolicy!=PublicationPolicy.OFF||includeDerivedWind)PublicationPolicy.ALWAYS else PublicationPolicy.OFF,
    autoStartOutput=false,
)
}

private fun NmeaDeviceOutputSettings.policyFor(stream:AnchorWatchNmeaStream)=when(stream){
    AnchorWatchNmeaStream.POSITION->effectivePositionPolicy
    AnchorWatchNmeaStream.HEADING->effectiveHeadingPolicy
    AnchorWatchNmeaStream.RATE_OF_TURN->effectiveRateOfTurnPolicy
    AnchorWatchNmeaStream.ATTITUDE->effectiveAttitudePolicy
    AnchorWatchNmeaStream.PRESSURE->effectivePressurePolicy
    AnchorWatchNmeaStream.DERIVED_WIND->derivedWindPolicy
}

/** Compatibility name for tests/callers compiled against the unification pass. */
fun NmeaDeviceOutputSettings.canonicalPublisherConfiguration()=publisherConfiguration()
