package com.yokuli.anchorwatch.runtime.output

import android.os.SystemClock
import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.nmea.output.NmeaDeviceOutputConnection
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

/** Compatibility facade for the final Phone Vessel Gateway publisher.
 * Acquisition stays independent from publication; this scheduler always reads
 * the latest snapshot and never queues sensor history for replay. */
@Singleton
class PhonePositionNmeaOutputRuntime @Inject constructor(
    positions:VesselPositionRepository,
    private val outputConnection:NmeaDeviceOutputConnection,
    private val vesselDataHub:VesselDataHub,
    private val mux:NmeaOutputMux,
    private val resources:RuntimeResourceManager,
    phoneHeading:PhoneHeadingRepository,
    vesselAttitude:PhoneVesselAttitudeRepository,
    mountCalibration:VesselMountCalibrationRepository,
    phonePressure:PhonePressureRepository,
){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    @Volatile var enabled=false;private set
    @Volatile private var settings=NmeaDeviceOutputSettings();@Volatile private var inputProfile=ConnectionProfile()
    @Volatile private var latestPosition:NavigationFix?=null;@Volatile private var heading=PhoneHeadingSample();@Volatile private var attitude=PhoneVesselAttitudeSample();@Volatile private var pressure=PhonePressureSample();@Volatile private var mountState=PhoneVesselMountState.UNCALIBRATED;@Volatile private var calibration=VesselMountCalibration()
    private var lastPositionPublish=0L;private var lastHeadingPublish=0L;private var lastMotionPublish=0L;private var lastPressurePublish=0L;private var lastWindPublish=0L;private var lastStatusPublish=0L
    private val positionGate=PublicationOwnershipGate(5_000L);private val headingGate=PublicationOwnershipGate(3_000L);private val motionGate=PublicationOwnershipGate(0L);private val pressureGate=PublicationOwnershipGate(0L);private val windGate=PublicationOwnershipGate(5_000L)
    private data class OutputBatch(val profile:ConnectionProfile,val stream:String,val sentences:List<String>,val types:Set<String>,val sequence:Long)
    private val pending=LatestPerStreamQueue<OutputBatch>{it.stream}
    private val writerWake=Channel<Unit>(capacity=Channel.CONFLATED)
    val status=outputConnection.status
    init{
        scope.launch{positions.acceptedPhoneFix.collect{latestPosition=it}}
        scope.launch{phoneHeading.sample.collect{heading=it}}
        scope.launch{vesselAttitude.sample.collect{attitude=it}}
        scope.launch{vesselAttitude.mountState.collect{mountState=it}}
        scope.launch{mountCalibration.calibration.collect{calibration=it}}
        scope.launch{phonePressure.sample.collect{pressure=it}}
        // One socket writer actor, with one latest-value slot per stream. A 5 Hz
        // heading heartbeat may replace older heading snapshots while a fragile
        // gateway is blocked, but it can never evict the independent 1 Hz
        // position slot. No stream keeps historical batches for reconnect replay.
        scope.launch{for(ignored in writerWake){
            while(isActive){
                val batch=pending.poll()?:break
                outputConnection.write(batch.profile,batch.sentences,batch.types,batch.stream,batch.sequence)
            }
        }}
        scope.launch{while(isActive){delay(50L);publishDue(SystemClock.elapsedRealtime())}}
    }

    @Synchronized fun configure(value:NmeaDeviceOutputSettings,input:ConnectionProfile=inputProfile){
        settings=value;inputProfile=input;enabled=value.anyEnabled;outputConnection.configure(value,input)
        if(enabled)resources.set(RuntimeOwner.PHONE_NMEA_OUTPUT,RuntimeRequirement(needsSystemLocation=value.effectivePositionPolicy!=PublicationPolicy.OFF||value.effectiveHeadingPolicy!=PublicationPolicy.OFF,needsNmeaTransport=value.transportMode==NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION,needsWakeLock=true,needsWifiLock=true,needsPhoneHeading=value.effectiveHeadingPolicy!=PublicationPolicy.OFF||value.proprietaryStatusEnabled,needsPhoneMotion=value.effectiveMotionPolicy!=PublicationPolicy.OFF||value.proprietaryStatusEnabled,needsPhonePressure=value.effectivePressurePolicy!=PublicationPolicy.OFF||value.proprietaryStatusEnabled))
        else{resources.release(RuntimeOwner.PHONE_NMEA_OUTPUT);pending.clear().forEach{outputConnection.recordDropped(it.stream)};resetClocks();positionGate.reset();headingGate.reset();motionGate.reset();pressureGate.reset();windGate.reset()}
    }
    @Synchronized fun configure(value:Boolean)=configure(NmeaDeviceOutputSettings(phonePositionEnabled=value,transportConfigured=value,publicationEnabled=value))

    private fun publishDue(now:Long){
        val configured=settings;if(!configured.anyEnabled)return
        val snapshot=vesselDataHub.snapshot.value
        if(now-lastPositionPublish>=1_000L)publishPosition(configured,snapshot,now)
        if(now-lastHeadingPublish>=200L)publishHeading(configured,snapshot,now)
        if(now-lastMotionPublish>=200L)publishMotion(configured,snapshot,now)
        if(now-lastPressurePublish>=1_000L)publishPressure(configured,snapshot,now)
        if(now-lastWindPublish>=500L)publishWind(configured,snapshot,now)
        if(configured.proprietaryStatusEnabled&&now-lastStatusPublish>=1_000L){val liveAttitude=attitude.attitude.takeIf{mountState==PhoneVesselMountState.VESSEL_MOUNTED};mux.phoneProprietary(liveAttitude,snapshot.motion.value,phoneVesselTrueHeading(),pressure.pressureHpa)?.let{write("STATUS",listOf(it),now)};lastStatusPublish=now}
    }

    private fun publishPosition(configured:NmeaDeviceOutputSettings,snapshot:VesselDataSnapshot,now:Long){
        lastPositionPublish=now;val conflict=snapshot.conflicts[VesselMetricId.POSITION]?.active==true;val decision=positionGate.evaluate(configured.effectivePositionPolicy,externalFresh(snapshot,VesselMetricId.POSITION,now,5_000L),conflict,now);outputConnection.recordDecision("POSITION",configured.effectivePositionPolicy,decision,latestPosition?.let{now-it.receivedElapsedRealtime in 0L..POSITION_HOLD_MILLIS}==true)
        if(!decision.publish)return;val sentences=latestPosition?.let{mux.phonePosition(it,now,POSITION_HOLD_MILLIS)}?:emptyList();if(sentences.isEmpty()){outputConnection.recordSuppressed("POSITION",NmeaSuppressionReason.PHONE_GPS_STALE.name);return};write("POSITION",sentences,now)
    }
    private fun publishHeading(configured:NmeaDeviceOutputSettings,snapshot:VesselDataSnapshot,now:Long){
        lastHeadingPublish=now;val conflict=snapshot.conflicts[VesselMetricId.HEADING_TRUE]?.active==true;val decision=headingGate.evaluate(configured.effectiveHeadingPolicy,externalFresh(snapshot,VesselMetricId.HEADING_TRUE,now,3_000L),conflict,now);val ready=mountState==PhoneVesselMountState.VESSEL_MOUNTED&&heading.receivedElapsedRealtime?.let{now-it in 0L..HEADING_HOLD_MILLIS}==true;outputConnection.recordDecision("HEADING",configured.effectiveHeadingPolicy,decision,ready)
        if(!decision.publish)return
        if(mountState!=PhoneVesselMountState.VESSEL_MOUNTED){outputConnection.recordSuppressed("HEADING",if(mountState==PhoneVesselMountState.MOUNT_SUSPECT)NmeaSuppressionReason.MOUNT_SUSPECT.name else NmeaSuppressionReason.PHONE_NOT_MOUNTED.name);return}
        val liveTrue=phoneVesselTrueHeading();val liveMagnetic=phoneVesselMagneticHeading();val variation=heading.magneticDeclinationDegrees.takeIf{heading.declinationReferenceReady}
        if(heading.receivedElapsedRealtime?.let{now-it in 0L..HEADING_HOLD_MILLIS}!=true){outputConnection.recordSuppressed("HEADING",NmeaSuppressionReason.PHONE_HEADING_STALE.name);return}
        val generated=when(configured.phoneHeadingFormat){
            PhoneHeadingOutputFormat.HDT_TRUE->liveTrue?.let{listOf(mux.phoneHeading(it))}
            PhoneHeadingOutputFormat.HDG_MAGNETIC->if(liveMagnetic!=null&&variation!=null)listOf(mux.phoneMagneticHeading(liveMagnetic,variation))else null
            PhoneHeadingOutputFormat.HDT_AND_HDG->if(liveTrue!=null&&liveMagnetic!=null&&variation!=null)listOf(mux.phoneHeading(liveTrue),mux.phoneMagneticHeading(liveMagnetic,variation))else null
        }
        if(generated==null){outputConnection.recordSuppressed("HEADING",NmeaSuppressionReason.NO_DECLINATION_REFERENCE.name);return};write("HEADING",generated,now)
    }
    private fun publishMotion(configured:NmeaDeviceOutputSettings,snapshot:VesselDataSnapshot,now:Long){
        lastMotionPublish=now;val external=externalFresh(snapshot,VesselMetricId.RATE_OF_TURN,now,3_000L)||externalFresh(snapshot,VesselMetricId.HEEL,now,3_000L)||externalFresh(snapshot,VesselMetricId.PITCH,now,3_000L);val decision=motionGate.evaluate(configured.effectiveMotionPolicy,external,false,now);val live=attitude.attitude.takeIf{mountState==PhoneVesselMountState.VESSEL_MOUNTED&&attitude.receivedElapsedRealtime?.let{now-it in 0L..MOTION_HOLD_MILLIS}==true};outputConnection.recordDecision("MOTION",configured.effectiveMotionPolicy,decision,live!=null);if(!decision.publish)return
        if(live==null){outputConnection.recordSuppressed("MOTION",if(mountState==PhoneVesselMountState.MOUNT_SUSPECT)NmeaSuppressionReason.MOUNT_SUSPECT.name else NmeaSuppressionReason.PHONE_NOT_MOUNTED.name);return};write("MOTION",listOfNotNull(mux.phoneRateOfTurn(live.yawRateDegreesPerSecond*60.0),mux.phoneXdr(live,null)),now)
    }
    private fun publishPressure(configured:NmeaDeviceOutputSettings,snapshot:VesselDataSnapshot,now:Long){
        lastPressurePublish=now;val live=pressure.pressureHpa.takeIf{pressure.receivedElapsedRealtime?.let{now-it in 0L..PRESSURE_HOLD_MILLIS}==true};val decision=pressureGate.evaluate(configured.effectivePressurePolicy,externalFresh(snapshot,VesselMetricId.PRESSURE,now,10_000L),false,now);outputConnection.recordDecision("PRESSURE",configured.effectivePressurePolicy,decision,live!=null);if(decision.publish&&live!=null)mux.phoneXdr(null,live)?.let{write("PRESSURE",listOf(it),now)}else if(decision.publish)outputConnection.recordSuppressed("PRESSURE","PHONE_PRESSURE_STALE")
    }
    private fun publishWind(configured:NmeaDeviceOutputSettings,snapshot:VesselDataSnapshot,now:Long){
        lastWindPublish=now;val derived=snapshot.trueWind.speedKnots.sourceClass in setOf(VesselSourceClass.DERIVED_WATER,VesselSourceClass.DERIVED_GROUND);val external=externalFresh(snapshot,VesselMetricId.TRUE_WIND_SPEED,now,5_000L);val decision=windGate.evaluate(configured.derivedWindPolicy,external,false,now);val values=listOf(snapshot.trueWind.speedKnots,snapshot.trueWind.directionDegrees,snapshot.trueWind.angleDegrees);val ready=derived&&values.all{it.value!=null&&it.freshness==VesselDataFreshness.FRESH};outputConnection.recordDecision("DERIVED_WIND",configured.derivedWindPolicy,decision,ready);if(!decision.publish)return
        if(!ready){outputConnection.recordSuppressed("DERIVED_WIND",NmeaSuppressionReason.NO_DERIVED_WIND.name);return};write("DERIVED_WIND",mux.derivedTrueWind(snapshot.trueWind.speedKnots.value!!,snapshot.trueWind.directionDegrees.value!!,snapshot.trueWind.angleDegrees.value!!),now)
    }
    private fun externalFresh(snapshot:VesselDataSnapshot,metric:VesselMetricId,now:Long,maxAge:Long)=snapshot.candidates[metric].orEmpty().any{it.sourceClass==VesselSourceClass.BOAT_NMEA&&it.validity==CandidateValidity.ELIGIBLE&&now-it.receivedElapsedRealtime in 0L..maxAge}
    private fun phoneVesselTrueHeading()=heading.liveTrueHeadingDegrees?.let{normalize(it+calibration.headingAlignmentOffsetDegrees)}.takeIf{mountState==PhoneVesselMountState.VESSEL_MOUNTED&&heading.declinationReferenceReady}
    private fun phoneVesselMagneticHeading()=heading.liveMagneticHeadingDegrees?.let{normalize(it+calibration.headingAlignmentOffsetDegrees)}.takeIf{mountState==PhoneVesselMountState.VESSEL_MOUNTED}
    private fun normalize(value:Double)=(value%360.0+360.0)%360.0
    private fun write(stream:String,sentences:List<String>,now:Long){
        if(sentences.isEmpty())return
        val sequence=outputConnection.recordGenerated(stream,sentences,now)
        val batch=OutputBatch(inputProfile,stream,sentences,sentences.mapNotNull(mux::sentenceType).toSet(),sequence)
        pending.offer(batch)?.let{outputConnection.recordDropped(it.stream)}
        writerWake.trySend(Unit)
    }
    private fun resetClocks(){lastPositionPublish=0;lastHeadingPublish=0;lastMotionPublish=0;lastPressurePublish=0;lastWindPublish=0;lastStatusPublish=0}

    fun testOutput(value:NmeaDeviceOutputSettings=settings,input:ConnectionProfile=inputProfile):Boolean{
        val testConfiguration=value.copy(proprietaryStatusEnabled=true,publicationEnabled=true);outputConnection.configure(testConfiguration,input)
        val sentences=listOf(mux.diagnostic());val sequence=outputConnection.recordGenerated("DIAGNOSTIC",sentences)
        val result=outputConnection.write(input,sentences,setOf("YOK"),"DIAGNOSTIC",sequence);outputConnection.configure(value,input);return result
    }
    fun testKnownGoodHdg(value:NmeaDeviceOutputSettings=settings,input:ConnectionProfile=inputProfile):Boolean{
        val testConfiguration=value.copy(proprietaryStatusEnabled=true,publicationEnabled=true);outputConnection.configure(testConfiguration,input)
        return try{repeat(5){index->val sentences=listOf(mux.diagnosticMagneticHeading(123.4));val sequence=outputConnection.recordGenerated("DIAGNOSTIC_HDG",sentences);if(!outputConnection.write(input,sentences,setOf("HDG"),"DIAGNOSTIC_HDG",sequence))return false;if(index<4)Thread.sleep(500L)};true}finally{outputConnection.configure(value,input)}
    }
    fun shutdown(){configure(NmeaDeviceOutputSettings(),inputProfile);outputConnection.stop()}
    private companion object{const val POSITION_HOLD_MILLIS=10_000L;const val HEADING_HOLD_MILLIS=15_000L;const val MOTION_HOLD_MILLIS=5_000L;const val PRESSURE_HOLD_MILLIS=60_000L}
}
