package com.yokuli.anchorwatch.runtime.sharing

import android.os.SystemClock
import com.yokuli.anchorwatch.data.sharing.LocalNmeaServerSettings
import com.yokuli.anchorwatch.data.sharing.NmeaSharingServer
import com.yokuli.anchorwatch.data.sharing.SharingServerState
import com.yokuli.anchorwatch.data.vessel.NmeaDeviceOutputSettings
import com.yokuli.anchorwatch.data.vessel.NmeaOutputTransportMode
import com.yokuli.anchorwatch.data.vessel.PhoneHeadingOutputFormat
import com.yokuli.anchorwatch.data.vessel.VesselDataHub
import com.yokuli.anchorwatch.data.vessel.VesselPositionRepository
import com.yokuli.anchorwatch.domain.vessel.NmeaOutputPurpose
import com.yokuli.anchorwatch.location.vessel.PhoneVesselAttitudeRepository
import com.yokuli.anchorwatch.location.vessel.PhoneVesselMountState
import com.yokuli.anchorwatch.runtime.RuntimeOwner
import com.yokuli.anchorwatch.runtime.RuntimeRequirement
import com.yokuli.anchorwatch.runtime.RuntimeResourceManager
import com.yokuli.anchorwatch.runtime.output.AnchorWatchNmeaFeedEncoder
import com.yokuli.anchorwatch.runtime.output.AnchorWatchNmeaHeartbeat
import com.yokuli.anchorwatch.runtime.output.AnchorWatchNmeaStream
import com.yokuli.anchorwatch.runtime.output.PhoneOwnedRuntimeSafety
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class LocalNmeaServerRuntimeStatus(
    val requested:Boolean=false,
    val generation:Long=0,
    val generatedSentences:Long=0,
    val queuedSentences:Long=0,
    val suppressedStreams:Map<String,String> = emptyMap(),
    val recentGenerated:List<String> = emptyList(),
    val message:String="Off",
)

/**
 * Owns only the phone-hosted NMEA listener and its feed.
 *
 * It intentionally has no boat input profile, no boat TX client, and no
 * dependency on the boat-network publisher. Both products may run at once;
 * stopping either one cannot close the other's sockets or resource lease.
 */
@Singleton
class LocalNmeaServerRuntime @Inject constructor(
    private val server:NmeaSharingServer,
    private val vesselDataHub:VesselDataHub,
    private val vesselPositionRepository:VesselPositionRepository,
    private val encoder:AnchorWatchNmeaFeedEncoder,
    private val resources:RuntimeResourceManager,
    vesselAttitude:PhoneVesselAttitudeRepository,
){
    private var scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val heartbeat=AnchorWatchNmeaHeartbeat()
    private val recent=ArrayDeque<String>()
    private val _status=MutableStateFlow(LocalNmeaServerRuntimeStatus())
    val status=_status.asStateFlow()
    @Volatile private var settings=LocalNmeaServerSettings()
    @Volatile private var mountState=PhoneVesselMountState.UNCALIBRATED
    @Volatile private var running=false
    val enabled:Boolean get()=running

    init{
        scope.launch{vesselAttitude.mountState.collect{mountState=it}}
        scope.launch{while(isActive){delay(100L);publishDue(SystemClock.elapsedRealtime())}}
    }

    @Synchronized fun configure(value:LocalNmeaServerSettings){
        val valid=value.configured&&value.port in 1024..65535
        val shouldRun=value.serverRequested&&valid
        if(settings.copy(serverRequested=shouldRun)==value.copy(serverRequested=shouldRun)&&running==shouldRun)return
        val portChanged=settings.port!=value.port
        settings=value.copy(serverRequested=shouldRun)
        if(!shouldRun){
            running=false
            server.stop()
            heartbeat.reset();encoder.reset();resources.release(RuntimeOwner.NMEA_SHARING)
            _status.value=_status.value.copy(requested=false,generation=_status.value.generation+1,message=if(valid)"Off" else "Invalid listening port")
            return
        }
        running=true
        if(portChanged||server.status.value.state==SharingServerState.STOPPED||server.status.value.state==SharingServerState.ERROR)server.start(value.port)
        heartbeat.reset();encoder.reset()
        resources.set(RuntimeOwner.NMEA_SHARING,RuntimeRequirement(needsSystemLocation=true,needsWakeLock=true,needsWifiLock=true,needsPhoneHeading=true,needsPhoneMotion=false,needsPhonePressure=value.includePressure))
        _status.value=LocalNmeaServerRuntimeStatus(requested=true,generation=_status.value.generation+1,message="Starting the phone NMEA service")
    }

    private fun publishDue(now:Long){
        val active=settings
        if(!running||!active.serverRequested)return
        val serverState=server.status.value.state
        if(serverState !in setOf(SharingServerState.RUNNING,SharingServerState.STARTING)){
            _status.value=_status.value.copy(message=server.status.value.message.ifBlank{"Phone NMEA service is rebinding"})
            return
        }
        if(serverState!=SharingServerState.RUNNING)return
        val snapshot=vesselDataHub.snapshot.value
        val phoneFix=vesselPositionRepository.acceptedPhoneFix.value
        val feedSettings=NmeaDeviceOutputSettings(
            purpose=NmeaOutputPurpose.CANONICAL_CLIENT_FEED,
            transportMode=NmeaOutputTransportMode.TCP_SERVER,
            phoneHeadingFormat=PhoneHeadingOutputFormat.HDT_TRUE,
            includePressure=active.includePressure,
            includeDerivedWind=active.includeDerivedWind,
            transportConfigured=true,
            publicationEnabled=true,
        )
        val suppressed=linkedMapOf<String,String>()
        var generatedCount=0
        var queuedCount=0
        heartbeat.due(now).forEach{stream->
            PhoneOwnedRuntimeSafety.suppression(NmeaOutputTransportMode.TCP_SERVER,stream,mountState)?.let{reason->
                suppressed[stream.name]=reason
                return@forEach
            }
            val batch=encoder.encode(stream,snapshot,feedSettings,now,phoneFix,inputProfileId="local-phone-nmea-service")
            if(batch.sentences.isEmpty()){
                batch.suppressionReason?.let{suppressed[stream.name]=it}
                return@forEach
            }
            generatedCount+=batch.sentences.size
            val timestamp=java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
            batch.sentences.forEach{sentence->
                recent.addLast("$timestamp  [${stream.name}] ${sentence.trim()}")
                while(recent.size>RECENT_LIMIT)recent.removeFirst()
                val receivers=server.publish(sentence)
                if(receivers>0)queuedCount++
            }
        }
        _status.value=_status.value.copy(
            generatedSentences=_status.value.generatedSentences+generatedCount,
            queuedSentences=_status.value.queuedSentences+queuedCount,
            suppressedStreams=suppressed,
            recentGenerated=recent.toList(),
            message=if(server.status.value.clientCount==0)"Listening; waiting for a client" else "Serving ${server.status.value.clientCount} client(s)",
        )
    }

    @Synchronized fun shutdown(){
        running=false;server.stop();heartbeat.reset();encoder.reset();resources.release(RuntimeOwner.NMEA_SHARING)
        _status.value=_status.value.copy(requested=false,generation=_status.value.generation+1,message="Off")
    }

    companion object{private const val RECENT_LIMIT=60}
}
