package com.yokuli.anchorwatch.runtime.output

import android.os.SystemClock
import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.data.sharing.NmeaOutputMux
import com.yokuli.anchorwatch.data.vessel.VesselPositionRepository
import com.yokuli.anchorwatch.data.vessel.VesselDataHub
import com.yokuli.anchorwatch.data.vessel.NmeaDeviceOutputSettings
import com.yokuli.anchorwatch.data.vessel.anyEnabled
import com.yokuli.anchorwatch.location.PhoneHeadingRepository
import com.yokuli.anchorwatch.location.PhoneHeadingSample
import com.yokuli.anchorwatch.location.vessel.PhonePressureRepository
import com.yokuli.anchorwatch.location.vessel.PhonePressureSample
import com.yokuli.anchorwatch.location.vessel.PhoneVesselAttitudeRepository
import com.yokuli.anchorwatch.location.vessel.PhoneVesselAttitudeSample
import com.yokuli.anchorwatch.runtime.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class PhonePositionOutputStatus(val enabled:Boolean=false,val sentencesWritten:Long=0,val lastWriteElapsed:Long?=null,val message:String="Off",val sentenceTypes:Set<String> = emptySet())

@Singleton
class PhonePositionNmeaOutputRuntime @Inject constructor(
    positions:VesselPositionRepository,
    private val navigation:NavigationRepository,
    private val vesselDataHub:VesselDataHub,
    private val mux:NmeaOutputMux,
    private val resources:RuntimeResourceManager,
    phoneHeading:PhoneHeadingRepository,
    vesselAttitude:PhoneVesselAttitudeRepository,
    phonePressure:PhonePressureRepository,
){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    @Volatile var enabled=false;private set
    @Volatile private var settings=NmeaDeviceOutputSettings()
    private var lastWriteElapsed=0L
    private var lastHeadingWriteElapsed=0L;private var lastAttitudeWriteElapsed=0L;private var lastPressureWriteElapsed=0L;private var lastProprietaryWriteElapsed=0L
    @Volatile private var heading=PhoneHeadingSample();@Volatile private var attitude=PhoneVesselAttitudeSample();@Volatile private var pressure=PhonePressureSample()
    private val _status=kotlinx.coroutines.flow.MutableStateFlow(PhonePositionOutputStatus());val status=_status.asStateFlow()
    init{scope.launch{phoneHeading.sample.collect{heading=it}};scope.launch{vesselAttitude.sample.collect{attitude=it}};scope.launch{phonePressure.sample.collect{pressure=it}};scope.launch{positions.acceptedPhoneFix.filterNotNull().collect{fix->
        if(!settings.phonePositionEnabled)return@collect
        val now=SystemClock.elapsedRealtime();if(now-lastWriteElapsed<900L)return@collect
        val sentences=mux.phonePosition(fix,now);if(sentences.isEmpty())return@collect
        if(navigation.writeToBoat(sentences)){written(sentences,now,"Phone GPS is being written to the boat NMEA connection.")}else _status.value=_status.value.copy(message="Waiting for a writable TCP NMEA connection.")
    }};scope.launch{while(isActive){delay(100L);writeSensors(SystemClock.elapsedRealtime())}}}
    @Synchronized fun configure(value:NmeaDeviceOutputSettings){
        settings=value;enabled=value.anyEnabled
        if(enabled){resources.set(RuntimeOwner.PHONE_NMEA_OUTPUT,RuntimeRequirement(needsSystemLocation=value.phonePositionEnabled,needsNmeaTransport=true,needsWakeLock=true,needsWifiLock=true,needsPhoneHeading=value.phoneHeadingEnabled||value.proprietaryStatusEnabled,needsPhoneMotion=value.phoneMotionEnabled||value.proprietaryStatusEnabled,needsPhonePressure=value.phonePressureEnabled||value.proprietaryStatusEnabled));_status.value=_status.value.copy(enabled=true,message="Waiting for fresh enabled phone sensors and a writable TCP NMEA connection.")}
        else{resources.release(RuntimeOwner.PHONE_NMEA_OUTPUT);lastWriteElapsed=0L;lastHeadingWriteElapsed=0L;lastAttitudeWriteElapsed=0L;lastPressureWriteElapsed=0L;lastProprietaryWriteElapsed=0L;_status.value=_status.value.copy(enabled=false,message="Off",sentenceTypes=emptySet())}
    }
    @Synchronized fun configure(value:Boolean)=configure(NmeaDeviceOutputSettings(phonePositionEnabled=value))
    private fun writeSensors(now:Long){val configured=settings;if(!configured.anyEnabled)return;val sentences=mutableListOf<String>()
        val liveHeading=heading.liveTrueHeadingDegrees?:heading.trueHeadingDegrees
        if(configured.phoneHeadingEnabled&&liveHeading!=null&&heading.receivedElapsedRealtime?.let{now-it in 0L..1_500L}==true&&now-lastHeadingWriteElapsed>=200L){sentences+=mux.phoneHeading(liveHeading);lastHeadingWriteElapsed=now}
        val liveAttitude=attitude.attitude.takeIf{attitude.receivedElapsedRealtime?.let{received->now-received in 0L..1_500L}==true&&!attitude.mountSuspect}
        if(configured.phoneMotionEnabled&&liveAttitude!=null&&now-lastAttitudeWriteElapsed>=200L){sentences+=mux.phoneRateOfTurn(liveAttitude.yawRateDegreesPerSecond*60.0);mux.phoneXdr(liveAttitude,null)?.let(sentences::add);lastAttitudeWriteElapsed=now}
        val livePressure=pressure.pressureHpa.takeIf{pressure.receivedElapsedRealtime?.let{received->now-received in 0L..10_000L}==true}
        if(configured.phonePressureEnabled&&livePressure!=null&&now-lastPressureWriteElapsed>=1_000L){mux.phoneXdr(null,livePressure)?.let(sentences::add);lastPressureWriteElapsed=now}
        if(configured.proprietaryStatusEnabled&&now-lastProprietaryWriteElapsed>=1_000L){
            val motion=vesselDataHub.snapshot.value.motion.takeIf{it.freshness==com.yokuli.anchorwatch.domain.vessel.VesselDataFreshness.FRESH}?.value
            mux.phoneProprietary(liveAttitude,motion,liveHeading,livePressure)?.let{sentence->sentences.add(sentence);lastProprietaryWriteElapsed=now}
        }
        if(sentences.isEmpty())return
        if(navigation.writeToBoat(sentences))written(sentences,now,"Phone vessel sensors are being written to the boat NMEA connection.")else _status.value=_status.value.copy(message="Waiting for a writable TCP NMEA connection.")
    }
    private fun written(sentences:List<String>,now:Long,message:String){lastWriteElapsed=now;_status.value=_status.value.copy(sentencesWritten=_status.value.sentencesWritten+sentences.size,lastWriteElapsed=now,message=message,sentenceTypes=_status.value.sentenceTypes+sentences.mapNotNull(mux::sentenceType))}
    fun shutdown(){configure(NmeaDeviceOutputSettings())}
}
