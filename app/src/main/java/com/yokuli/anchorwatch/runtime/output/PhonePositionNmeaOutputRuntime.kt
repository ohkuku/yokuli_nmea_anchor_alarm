package com.yokuli.anchorwatch.runtime.output

import android.os.SystemClock
import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.nmea.output.NmeaDeviceOutputConnection
import com.yokuli.anchorwatch.data.nmea.output.NmeaTxStatus
import com.yokuli.anchorwatch.data.sharing.NmeaOutputMux
import com.yokuli.anchorwatch.data.vessel.VesselPositionRepository
import com.yokuli.anchorwatch.data.vessel.VesselDataHub
import com.yokuli.anchorwatch.data.vessel.NmeaDeviceOutputSettings
import com.yokuli.anchorwatch.data.vessel.anyEnabled
import com.yokuli.anchorwatch.data.vessel.NmeaOutputTransportMode
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

typealias PhonePositionOutputStatus=NmeaTxStatus

@Singleton
class PhonePositionNmeaOutputRuntime @Inject constructor(
    positions:VesselPositionRepository,
    private val outputConnection:NmeaDeviceOutputConnection,
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
    @Volatile private var inputProfile=ConnectionProfile()
    private var lastWriteElapsed=0L
    private var lastHeadingWriteElapsed=0L;private var lastAttitudeWriteElapsed=0L;private var lastPressureWriteElapsed=0L;private var lastProprietaryWriteElapsed=0L
    @Volatile private var heading=PhoneHeadingSample();@Volatile private var attitude=PhoneVesselAttitudeSample();@Volatile private var pressure=PhonePressureSample()
    val status=outputConnection.status
    init{scope.launch{phoneHeading.sample.collect{heading=it}};scope.launch{vesselAttitude.sample.collect{attitude=it}};scope.launch{phonePressure.sample.collect{pressure=it}};scope.launch{positions.acceptedPhoneFix.filterNotNull().collect{fix->
        if(!settings.phonePositionEnabled)return@collect
        val now=SystemClock.elapsedRealtime();if(now-lastWriteElapsed<900L)return@collect
        val sentences=mux.phonePosition(fix,now);if(sentences.isEmpty())return@collect
        write(sentences)
    }};scope.launch{while(isActive){delay(100L);writeSensors(SystemClock.elapsedRealtime())}}}
    @Synchronized fun configure(value:NmeaDeviceOutputSettings,input:ConnectionProfile=inputProfile){
        settings=value;inputProfile=input;enabled=value.anyEnabled;outputConnection.configure(value,input)
        if(enabled){resources.set(RuntimeOwner.PHONE_NMEA_OUTPUT,RuntimeRequirement(needsSystemLocation=value.phonePositionEnabled,needsNmeaTransport=value.transportMode==NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION,needsWakeLock=true,needsWifiLock=true,needsPhoneHeading=value.phoneHeadingEnabled||value.proprietaryStatusEnabled,needsPhoneMotion=value.phoneMotionEnabled||value.proprietaryStatusEnabled,needsPhonePressure=value.phonePressureEnabled||value.proprietaryStatusEnabled))}
        else{resources.release(RuntimeOwner.PHONE_NMEA_OUTPUT);lastWriteElapsed=0L;lastHeadingWriteElapsed=0L;lastAttitudeWriteElapsed=0L;lastPressureWriteElapsed=0L;lastProprietaryWriteElapsed=0L}
    }
    @Synchronized fun configure(value:Boolean)=configure(NmeaDeviceOutputSettings(phonePositionEnabled=value))
    private fun writeSensors(now:Long){val configured=settings;if(!configured.anyEnabled)return;val sentences=mutableListOf<String>()
        // HDT is a true-heading claim. Do not emit it until a valid position has
        // made magnetic declination available; UI presentation remains live.
        val liveHeading=(heading.liveTrueHeadingDegrees?:heading.trueHeadingDegrees).takeIf{heading.declinationReferenceReady}
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
        write(sentences)
    }
    private fun write(sentences:List<String>){if(outputConnection.write(inputProfile,sentences,sentences.mapNotNull(mux::sentenceType).toSet()))lastWriteElapsed=SystemClock.elapsedRealtime()}
    fun testOutput(value:NmeaDeviceOutputSettings=settings,input:ConnectionProfile=inputProfile):Boolean{
        val testConfiguration=value.copy(proprietaryStatusEnabled=true)
        outputConnection.configure(testConfiguration,input)
        // Default diagnostics must never inject a plausible navigation claim.
        val sentence=mux.diagnostic()
        val result=outputConnection.write(input,listOf(sentence),setOf("YOK"))
        outputConnection.configure(value,input)
        return result
    }
    fun testKnownGoodHdg(value:NmeaDeviceOutputSettings=settings,input:ConnectionProfile=inputProfile):Boolean{
        val testConfiguration=value.copy(proprietaryStatusEnabled=true)
        outputConnection.configure(testConfiguration,input)
        return try{
            repeat(5){index->
                val sentence=mux.diagnosticMagneticHeading(123.4)
                if(!outputConnection.write(input,listOf(sentence),setOf("HDG")))return false
                if(index<4)Thread.sleep(500L)
            }
            true
        }finally{outputConnection.configure(value,input)}
    }
    fun shutdown(){configure(NmeaDeviceOutputSettings(),inputProfile);outputConnection.stop()}
}
