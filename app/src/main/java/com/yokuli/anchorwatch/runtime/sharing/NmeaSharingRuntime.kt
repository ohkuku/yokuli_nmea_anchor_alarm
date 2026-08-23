package com.yokuli.anchorwatch.runtime.sharing

import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.data.sharing.NmeaOutputMux
import com.yokuli.anchorwatch.data.sharing.NmeaSharingServer
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.location.AcceptedPositionEvent
import com.yokuli.anchorwatch.location.AcceptedPositionRepository
import com.yokuli.anchorwatch.runtime.RuntimeOwner
import com.yokuli.anchorwatch.runtime.RuntimeRequirement
import com.yokuli.anchorwatch.runtime.RuntimeResourceManager
import com.yokuli.anchorwatch.runtime.nmea.NmeaRuntime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

data class SharingRuntimeResult(
    val needsLocationForeground:Boolean=false,
    val title:String?=null,
    val message:String?=null,
)

/** Owns sharing lifecycle, source policy and all output multiplexing. */
@Singleton
class NmeaSharingRuntime @Inject constructor(
    private val server:NmeaSharingServer,
    private val output:NmeaOutputMux,
    private val positionPublisher:NmeaSharingPositionPublisher,
    private val acceptedPosition:AcceptedPositionRepository,
    private val navigation:NavigationRepository,
    private val settings:SettingsRepository,
    private val resources:RuntimeResourceManager,
    private val nmeaRuntime:NmeaRuntime,
){
    @Volatile var enabled:Boolean=false
        private set
    @Volatile var source:GpsDataSource=GpsDataSource.SYSTEM
        private set
    val status get()=server.status

    suspend fun configure(enabled:Boolean,port:Int,requestedSource:GpsDataSource,lockedSource:GpsDataSource?):SharingRuntimeResult{
        this.enabled=enabled
        source=lockedSource?:requestedSource
        if(!enabled){
            positionPublisher.reset()
            server.stop()
            resources.release(RuntimeOwner.NMEA_SHARING)
            releaseNmeaIfUnowned()
            return SharingRuntimeResult()
        }
        server.start(port)
        val now=android.os.SystemClock.elapsedRealtime()
        val accepted=acceptedPosition.state.value
        positionPublisher.seed(accepted.selectedSource,accepted.acceptedFix,source,now)
            ?.let{fix->output.acceptedPosition(fix,now).forEach(server::publish)}
        val current=settings.settings.first()
        resources.set(
            RuntimeOwner.NMEA_SHARING,
            RuntimeRequirement(
                needsSystemLocation=source==GpsDataSource.SYSTEM,
                needsNmeaTransport=source==GpsDataSource.NMEA,
                needsWakeLock=true,
                needsWifiLock=current.keepWifiAwake,
            ),
        )
        return when{
            source==GpsDataSource.SYSTEM->SharingRuntimeResult(needsLocationForeground=true)
            navigation.connectionState.value!=NmeaConnectionState.CONNECTED->SharingRuntimeResult(
                title="NMEA Sharing waiting for input",
                message="Sharing will not connect a saved NMEA endpoint automatically. Open the NMEA connection when you want to publish boat data.",
            )
            else->SharingRuntimeResult()
        }
    }

    fun onAcceptedPosition(event:AcceptedPositionEvent,nowElapsed:Long){
        if(!enabled)return
        positionPublisher.accept(event.source,event.accepted.fix,source,nowElapsed)
            ?.let{fix->output.acceptedPosition(fix,nowElapsed).forEach(server::publish)}
    }

    fun tick(nowElapsed:Long){
        if(!enabled)return
        positionPublisher.tick(source,nowElapsed)
            ?.let{fix->output.acceptedPosition(fix,nowElapsed).forEach(server::publish)}
    }

    fun onBoatSentence(line:String){if(enabled)output.boatSentence(line,source)?.let(server::publish)}

    fun shutdown(){positionPublisher.reset();server.stop();resources.release(RuntimeOwner.NMEA_SHARING)}

    private fun releaseNmeaIfUnowned()=nmeaRuntime.releaseIfUnowned()
}
