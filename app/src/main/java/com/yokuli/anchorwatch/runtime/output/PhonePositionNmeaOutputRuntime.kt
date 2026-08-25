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
 * from publication; every destination receives the same re-encoded canonical
 * feed and no raw boat sentence enters this runtime. */
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
    @Volatile private var settings=NmeaDeviceOutputSettings();@Volatile private var inputProfile=ConnectionProfile()
    @Volatile private var mountState=PhoneVesselMountState.UNCALIBRATED;@Volatile private var calibration=VesselMountCalibration()
    private val heartbeat=AnchorWatchNmeaHeartbeat()
    private val sessionGate=NmeaPublicationSessionGate()
    private data class OutputBatch(val generation:Long,val profile:ConnectionProfile,val stream:String,val sentences:List<String>,val types:Set<String>,val sequence:Long)
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
                outputConnection.write(batch.profile,batch.sentences,batch.types,batch.stream,batch.sequence,batch.generation)
            }
        }}
        scope.launch{while(isActive){delay(50L);publishDue(SystemClock.elapsedRealtime())}}
    }

    @Synchronized fun configure(value:NmeaDeviceOutputSettings,input:ConnectionProfile=inputProfile){
        val canonical=value.canonicalPublisherConfiguration()
        val safe=if(canonical.anyEnabled&&(!PhoneVesselOutputReadinessPolicy.evaluate(calibration,mountState).ready||NmeaOutputEndpointPolicy.opensSecondTransportOnInputEndpoint(canonical,input)))canonical.copy(publicationEnabled=false) else canonical
        if(safe==settings&&input==inputProfile&&enabled==safe.anyEnabled)return
        pending.clear().forEach{outputConnection.recordDropped(it.stream)}
        val generation=if(safe.anyEnabled)sessionGate.start()else sessionGate.stop()
        settings=safe;inputProfile=input;enabled=safe.anyEnabled;heartbeat.reset()
        outputConnection.configure(safe,input,generation,if(enabled)java.util.UUID.randomUUID().toString().take(8)else null)
        if(enabled)resources.set(RuntimeOwner.PHONE_NMEA_OUTPUT,RuntimeRequirement(needsSystemLocation=true,needsNmeaTransport=safe.transportMode==NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION,needsWakeLock=true,needsWifiLock=true,needsPhoneHeading=true,needsPhoneMotion=true,needsPhonePressure=safe.includePressure))
        else resources.release(RuntimeOwner.PHONE_NMEA_OUTPUT)
    }
    @Synchronized private fun enforceProductionReadiness(){if(enabled&&!PhoneVesselOutputReadinessPolicy.evaluate(calibration,mountState).ready)configure(settings.copy(publicationEnabled=false),inputProfile)}
    @Synchronized fun configure(value:Boolean)=configure(NmeaDeviceOutputSettings(transportConfigured=value,publicationEnabled=value))

    private fun publishDue(now:Long){
        val configured=settings;val generation=sessionGate.current();if(!configured.anyEnabled||!sessionGate.accepts(generation))return
        outputConnection.refreshTransportState()
        val snapshot=vesselDataHub.snapshot.value;val phoneFix=vesselPositionRepository.acceptedPhoneFix.value
        heartbeat.due(now).forEach{stream->val batch=encoder.encode(stream,snapshot,configured,now,phoneFix);val name=stream.name
            val ready=batch.sentences.isNotEmpty();outputConnection.recordDecision(name,PublicationPolicy.ALWAYS,PublicationDecision(ready,if(ready)PublisherOwnershipState.PHONE_ACTIVE else PublisherOwnershipState.SUPPRESSED),ready,NmeaStreamReadinessPolicy.sensor(ready))
            if(ready)write(generation,name,batch.sentences,now)else outputConnection.recordSuppressed(name,"NO_COMPLETE_VALUE")
        }
    }

    private fun write(generation:Long,stream:String,sentences:List<String>,now:Long){
        if(sentences.isEmpty()||!sessionGate.accepts(generation))return
        val sequence=outputConnection.recordGenerated(stream,sentences,now,generation)?:return
        val batch=OutputBatch(generation,inputProfile,stream,sentences,sentences.mapNotNull(mux::sentenceType).toSet(),sequence)
        pending.offer(batch)?.let{outputConnection.recordDropped(it.stream)}
        writerWake.trySend(Unit)
    }

    fun testOutput(value:NmeaDeviceOutputSettings=settings,input:ConnectionProfile=inputProfile):Boolean{
        if(enabled)return false
        return outputConnection.test(value.canonicalPublisherConfiguration(),input,listOf(mux.diagnostic()),"TEST_TX")
    }
    fun testKnownGoodHdg(value:NmeaDeviceOutputSettings=settings,input:ConnectionProfile=inputProfile):Boolean{
        if(enabled)return false
        return outputConnection.test(value.canonicalPublisherConfiguration(),input,List(5){mux.diagnosticMagneticHeading(123.4)},"TEST_TX_HDG")
    }
    fun shutdown(){configure(NmeaDeviceOutputSettings(),inputProfile);outputConnection.stop(sessionGate.stop())}
}

/** Source-compatible name retained while callers and backup DTOs migrate. */
typealias PhonePositionNmeaOutputRuntime=AnchorWatchNmeaPublisher

fun NmeaDeviceOutputSettings.canonicalPublisherConfiguration()=copy(
    purpose=NmeaOutputPurpose.CANONICAL_CLIENT_FEED,
    phonePositionEnabled=false,
    phoneHeadingEnabled=false,
    phoneMotionEnabled=false,
    phonePressureEnabled=false,
    proprietaryStatusEnabled=false,
    positionPolicy=PublicationPolicy.OFF,
    headingPolicy=PublicationPolicy.OFF,
    motionPolicy=PublicationPolicy.OFF,
    pressurePolicy=PublicationPolicy.OFF,
    derivedWindPolicy=PublicationPolicy.OFF,
    autoStartOutput=false,
)
