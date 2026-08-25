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
import com.yokuli.anchorwatch.location.PhoneHeadingRepository
import com.yokuli.anchorwatch.location.PhoneHeadingSample
import com.yokuli.anchorwatch.location.vessel.*
import com.yokuli.anchorwatch.runtime.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

typealias PhonePositionOutputStatus=NmeaTxStatus

/** The only configuration visible to the live publisher. Legacy BACKUP values
 * are normalized to ALWAYS before this boundary. Destination semantics decide
 * whether the feed is local injection or unified fan-out. */
data class NmeaPublisherConfig(
    val transportMode:NmeaOutputTransportMode=NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION,
    val host:String="",
    val port:Int=10110,
    val headingFormat:PhoneHeadingOutputFormat=PhoneHeadingOutputFormat.HDT_TRUE,
    val includePressure:Boolean=true,
    val includeDerivedWind:Boolean=true,
    val transportConfigured:Boolean=false,
    val publicationEnabled:Boolean=false,
){
    val running:Boolean get()=transportConfigured&&publicationEnabled
    fun asOutputSettings()=NmeaDeviceOutputSettings(
        transportMode=transportMode,outputHost=host,outputPort=port,
        phoneHeadingFormat=headingFormat,includePressure=includePressure,
        includeDerivedWind=includeDerivedWind,transportConfigured=transportConfigured,
        publicationEnabled=publicationEnabled,
    ).publisherConfiguration()
    companion object{
        fun from(value:NmeaDeviceOutputSettings):NmeaPublisherConfig{
            val canonical=value.publisherConfiguration()
            return NmeaPublisherConfig(canonical.transportMode,canonical.outputHost.trim(),canonical.outputPort,canonical.phoneHeadingFormat,canonical.includePressure,canonical.includeDerivedWind,canonical.transportConfigured,canonical.publicationEnabled)
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

object SelectedExternalSourcePresence{
    fun present(observation:VesselObservation<*>,now:Long,maxAge:Long,acceptedSentenceTypes:Set<String>?=null):Boolean{
        if(observation.value==null||observation.sourceClass!=VesselSourceClass.BOAT_NMEA||observation.freshness!=VesselDataFreshness.FRESH)return false
        if(observation.receivedElapsedRealtime?.let{now-it in 0L..maxAge}!=true)return false
        return acceptedSentenceTypes==null||observation.sourceIdentity?.sentenceType?.uppercase() in acceptedSentenceTypes
    }
}

/** The single normal-product NMEA feed engine. Acquisition stays independent
 * from publication. SAME_AS_INPUT is local-only injection; independent
 * destinations receive the selected unified vessel state. */
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
        scope.launch{combine(vesselAttitude.mountState,mountCalibration.calibration){mount,value->mount to value}.collect{(mount,value)->mountState=mount;calibration=value;enforceProductionReadiness()}}
        // One socket writer actor, with one latest-value slot per stream. A 5 Hz
        // heading heartbeat may replace older heading snapshots while a fragile
        // gateway is blocked, but it can never evict the independent 1 Hz
        // position slot. No stream keeps historical batches for reconnect replay.
        scope.launch{for(ignored in writerWake){
            while(isActive){
                val batch=pending.poll()?:break
                if(!sessionGate.accepts(batch.generation)){outputConnection.recordDropped(batch.stream);continue}
                outputConnection.write(batch.profile,batch.sentences,batch.types,batch.stream,batch.sequence,batch.generation,batch.sourceStableKey,path=batch.path,expectedInputTransportGeneration=batch.inputTransportGeneration)
            }
        }}
        scope.launch{while(isActive){delay(50L);publishDue(SystemClock.elapsedRealtime())}}
    }

    @Synchronized fun configure(value:NmeaDeviceOutputSettings,input:ConnectionProfile=inputProfile){
        val requested=NmeaPublisherConfig.from(value);val requestedSettings=requested.asOutputSettings()
        val safe=if(requested.running&&(!PhoneVesselOutputReadinessPolicy.evaluate(calibration,mountState).ready||NmeaOutputEndpointPolicy.opensSecondTransportOnInputEndpoint(requestedSettings,input)))requested.copy(publicationEnabled=false) else requested
        if(safe==config&&input==inputProfile&&enabled==safe.running)return
        pending.clear().forEach{outputConnection.recordDropped(it.stream)}
        val generation=if(safe.running)sessionGate.start()else sessionGate.stop()
        config=safe;inputProfile=input;enabled=safe.running;heartbeat.reset();encoder.reset()
        val effective=safe.asOutputSettings()
        outputConnection.configure(effective,input,generation,if(enabled)java.util.UUID.randomUUID().toString().take(8)else null)
        if(enabled)resources.set(RuntimeOwner.PHONE_NMEA_OUTPUT,RuntimeRequirement(needsSystemLocation=true,needsNmeaTransport=safe.transportMode==NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION,needsWakeLock=true,needsWifiLock=true,needsPhoneHeading=true,needsPhoneMotion=true,needsPhonePressure=safe.includePressure))
        else resources.release(RuntimeOwner.PHONE_NMEA_OUTPUT)
    }
    @Synchronized private fun enforceProductionReadiness(){if(enabled&&!PhoneVesselOutputReadinessPolicy.evaluate(calibration,mountState).ready)configure(config.copy(publicationEnabled=false).asOutputSettings(),inputProfile)}
    @Synchronized fun configure(value:Boolean)=configure(NmeaDeviceOutputSettings(transportConfigured=value,publicationEnabled=value))

    private fun publishDue(now:Long){
        val configured=config;val generation=sessionGate.current();if(!configured.running||!sessionGate.accepts(generation))return
        outputConnection.refreshTransportState()
        val inputTransportGeneration=outputConnection.currentInputTransportGeneration()
        val snapshot=vesselDataHub.snapshot.value;val phoneFix=vesselPositionRepository.acceptedPhoneFix.value
        heartbeat.due(now).forEach{stream->val batch=encoder.encode(stream,snapshot,configured.asOutputSettings(),now,phoneFix,inputTransportGeneration,inputProfile.stableId);val name=stream.name
            val ready=batch.sentences.isNotEmpty();val ownership=when{ready&&batch.sourceConflict->PublisherOwnershipState.SOURCE_CONFLICT;ready->PublisherOwnershipState.PHONE_ACTIVE;else->PublisherOwnershipState.SUPPRESSED}
            outputConnection.recordDecision(name,PublicationPolicy.ALWAYS,PublicationDecision(ready,ownership),ready,NmeaStreamReadinessPolicy.sensor(ready))
            if(ready)write(generation,name,batch.sentences,now,batch.sourceStableKey,inputTransportGeneration)else outputConnection.recordSuppressed(name,batch.suppressionReason?:"NO_COMPLETE_VALUE")
        }
    }

    private fun write(generation:Long,stream:String,sentences:List<String>,now:Long,sourceStableKey:String?,inputTransportGeneration:Long){
        if(sentences.isEmpty()||!sessionGate.accepts(generation))return
        val transportGeneration=inputTransportGeneration.takeIf{config.transportMode==NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION}
        val path=if(config.transportMode==NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION)com.yokuli.anchorwatch.data.nmea.output.NmeaPacketPath.LOCAL_SENSOR_INJECTION else com.yokuli.anchorwatch.data.nmea.output.NmeaPacketPath.CANONICAL_PUBLISHER
        val sequence=outputConnection.recordGenerated(stream,sentences,now,generation,sourceStableKey,path,inputTransportGeneration=transportGeneration)?:return
        val batch=OutputBatch(generation,inputProfile,stream,sentences,sentences.mapNotNull(mux::sentenceType).toSet(),sequence,sourceStableKey,transportGeneration,path)
        pending.offer(batch)?.let{outputConnection.recordDropped(it.stream)}
        writerWake.trySend(Unit)
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

fun NmeaDeviceOutputSettings.publisherConfiguration():NmeaDeviceOutputSettings{
    val localInjection=transportMode==NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION
    return copy(
    purpose=if(localInjection)NmeaOutputPurpose.BOAT_BUS_INJECTION else NmeaOutputPurpose.CANONICAL_CLIENT_FEED,
    phonePositionEnabled=localInjection,
    phoneHeadingEnabled=localInjection,
    phoneMotionEnabled=localInjection,
    phonePressureEnabled=localInjection&&includePressure,
    proprietaryStatusEnabled=false,
    positionPolicy=if(localInjection)PublicationPolicy.ALWAYS else PublicationPolicy.OFF,
    headingPolicy=if(localInjection)PublicationPolicy.ALWAYS else PublicationPolicy.OFF,
    motionPolicy=if(localInjection)PublicationPolicy.ALWAYS else PublicationPolicy.OFF,
    pressurePolicy=if(localInjection&&includePressure)PublicationPolicy.ALWAYS else PublicationPolicy.OFF,
    derivedWindPolicy=if(localInjection&&includeDerivedWind)PublicationPolicy.ALWAYS else PublicationPolicy.OFF,
    autoStartOutput=false,
)
}

/** Compatibility name for tests/callers compiled against the unification pass. */
fun NmeaDeviceOutputSettings.canonicalPublisherConfiguration()=publisherConfiguration()
