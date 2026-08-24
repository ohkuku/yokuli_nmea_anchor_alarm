package com.yokuli.anchorwatch.runtime.trip

import android.os.SystemClock
import com.yokuli.anchorwatch.data.database.*
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.data.trip.TripSampleWriter
import com.yokuli.anchorwatch.data.nmea.NmeaFieldRepository
import com.yokuli.anchorwatch.data.trip.DashboardTileBinding
import com.yokuli.anchorwatch.data.trip.TripDashboardRepository
import com.yokuli.anchorwatch.data.trip.TripCustomMetricRecordingPolicy
import com.yokuli.anchorwatch.data.vessel.VesselDataHub
import com.yokuli.anchorwatch.data.vessel.VesselSettingsRepository
import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.domain.vessel.*
import com.yokuli.anchorwatch.domain.trip.TripSessionTiming
import com.yokuli.anchorwatch.location.vessel.PhoneVesselAttitudeRepository
import com.yokuli.anchorwatch.location.vessel.VesselMountCalibrationRepository
import com.yokuli.anchorwatch.runtime.*
import com.yokuli.anchorwatch.runtime.nmea.NmeaRuntime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

data class TripRuntimeResult(val success:Boolean,val message:String,val session:TripSessionEntity?=null)

@Singleton
class TripRuntime @Inject constructor(
    private val dao:TripDao,
    private val hub:VesselDataHub,
    private val writer:TripSampleWriter,
    private val resources:RuntimeResourceManager,
    private val appSettings:SettingsRepository,
    private val vesselSettings:VesselSettingsRepository,
    private val nmeaRuntime:NmeaRuntime,
    private val vesselAttitude:PhoneVesselAttitudeRepository,
    private val mountCalibration:VesselMountCalibrationRepository,
    private val nmeaFields:NmeaFieldRepository,
    private val dashboards:TripDashboardRepository,
){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val mutex=Mutex()
    @Volatile private var active:TripSessionEntity?=null
    private var ticker:Job?=null
    private var lastFlushElapsed=0L
    private var lastPosition:VesselPosition?=null
    private var lastPositionAt:Long?=null
    private var lastRecordedPositionSource:String?=null
    private var lastRecordedHeadingSource:String?=null
    private var positionGapOpen:Boolean?=null
    private var lastImpactElapsed:Long?=null
    private var phoneMotionRecordingAllowed=false
    private var nmeaTransportOwned=false
    /** "Expected" is session memory, not an alias for "available now". Once a
     * boat instrument has participated in this trip, its later disappearance
     * must produce a gap and its return a recovery event. */
    private var nmeaExpected=false
    private var depthExpected=false
    private var windExpected=false
    private val eventTransitions=TripEventTransitionTracker()
    @Volatile private var selectedCustomBindings:Map<String,DashboardTileBinding> = emptyMap()

    init{
        scope.launch{dashboards.decoded.collect{pages->selectedCustomBindings=TripCustomMetricRecordingPolicy.bindings(pages)}}
    }

    fun activeSession()=active

    suspend fun restore():TripSessionEntity?=mutex.withLock{
        val existing=dao.active()?:return@withLock null
        val restored=existing.copy(restoredAfterProcessDeath=true,eventCount=existing.eventCount+1)
        // Rebuild the per-field expectation memory from durable samples. A
        // process restart must not turn a later missing field into "never
        // observed", otherwise real depth/wind/NMEA gaps would be hidden.
        nmeaExpected=existing.nmeaWasActiveAtStart||dao.hasNmeaSamples(existing.id)
        depthExpected=existing.minDepthMeters!=null||dao.hasDepthSamples(existing.id)
        windExpected=dao.hasWindSamples(existing.id)
        dao.updateSessionAndInsertEvent(restored,TripEventEntity(tripId=existing.id,timestamp=System.currentTimeMillis(),type="RUNTIME_RESTORED",severity="INFO"))
        active=restored
        resetRecordingEdges()
        if(!existing.paused){
            try{ownResources(restored);startTicker()}
            catch(cancelled:CancellationException){releaseOwnedResources();throw cancelled}
            catch(error:Exception){
                releaseOwnedResources()
                val now=System.currentTimeMillis()
                val paused=restored.copy(paused=true,pausedAt=now,eventCount=restored.eventCount+1)
                dao.updateSessionAndInsertEvent(paused,TripEventEntity(tripId=existing.id,timestamp=now,type="RUNTIME_RESTORE_PAUSED",severity="WARNING",detailJson="{\"reason\":\"${error.javaClass.simpleName}\"}"))
                active=paused
            }
        }
        active
    }

    suspend fun start(name:String,nmeaState:NmeaConnectionState,phoneMotionRequested:Boolean=true):TripRuntimeResult=mutex.withLock{
        if(active!=null)return@withLock TripRuntimeResult(false,"A Trip Watch session is already open.",active)
        val app=appSettings.settings.first()
        val vessel=vesselSettings.settings.first()
        val calibration=mountCalibration.calibration.first()
        val motionEnabled=TripStartSensorPolicy.phoneMotionEnabled(phoneMotionRequested,vesselAttitude.capabilities.attitudeAvailable,calibration.calibratedAt)
        val now=System.currentTimeMillis()
        val value=TripSessionEntity(
            name=name.trim().ifBlank{"Trip · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(now))}"},
            startedAt=now,
            boatLengthMeters=app.boatLengthMeters,
            draftMeters=vessel.draftMeters,
            positionPreference=vessel.positionPreference.name,
            headingPreference=vessel.headingPreference.name,
            phoneMotionEnabled=motionEnabled,
            mountCalibrationVersion=calibration.version.takeIf{motionEnabled},
            motionAlgorithmVersion=VesselMotionAnalyzer.ALGORITHM_VERSION,
            nmeaWasActiveAtStart=nmeaState.hasRecentNmeaTraffic(),
        )
        try{ownResources(value)}catch(error:Exception){releaseOwnedResources();throw error}
        val id=try{dao.insertSessionAndEvent(value,TripEventEntity(tripId=0,timestamp=now,type="TRIP_STARTED",severity="INFO",detailJson="{\"phoneMotionRequested\":$phoneMotionRequested,\"phoneMotionEnabled\":$motionEnabled,\"mountCalibrationVersion\":${calibration.version.takeIf{motionEnabled}?:"null"}}"))}
        catch(error:Exception){releaseOwnedResources();throw error}
        active=value.copy(id=id,eventCount=1)
        nmeaExpected=value.nmeaWasActiveAtStart
        depthExpected=false
        windExpected=false
        resetRecordingEdges()
        lastFlushElapsed=SystemClock.elapsedRealtime()
        startTicker()
        TripRuntimeResult(true,"Trip recording started.",active)
    }

    suspend fun pause():TripRuntimeResult{
        stopTicker()
        return mutex.withLock{
            val current=active?:return@withLock TripRuntimeResult(false,"No active Trip Watch.")
            if(current.paused)return@withLock TripRuntimeResult(true,"Trip is already paused.",current)
            val flush=try{flushLocked()}catch(error:Exception){startTicker();throw error}
            if(flush.writeFailed){startTicker();return@withLock TripRuntimeResult(false,"Buffered samples could not be written. Trip recording is still running; check storage and try Pause again.",current)}
            val latest=active?:current
            val now=System.currentTimeMillis()
            val updated=latest.copy(paused=true,pausedAt=now,eventCount=latest.eventCount+1)
            try{dao.updateSessionAndInsertEvent(updated,TripEventEntity(tripId=latest.id,timestamp=now,type="TRIP_PAUSED",severity="INFO"))}
            catch(error:Exception){startTicker();throw error}
            active=updated
            releaseOwnedResources()
            TripRuntimeResult(true,"Trip paused.",updated)
        }
    }

    suspend fun resume():TripRuntimeResult=mutex.withLock{
        val current=active?:return@withLock TripRuntimeResult(false,"No active Trip Watch.")
        if(!current.paused)return@withLock TripRuntimeResult(true,"Trip is already recording.",current)
        val now=System.currentTimeMillis()
        val updated=current.copy(
            paused=false,
            pausedAt=null,
            accumulatedPausedMillis=current.accumulatedPausedMillis+(current.pausedAt?.let{now-it}?:0),
            eventCount=current.eventCount+1,
        )
        try{ownResources(updated);dao.updateSessionAndInsertEvent(updated,TripEventEntity(tripId=current.id,timestamp=now,type="TRIP_RESUMED",severity="INFO"))}
        catch(error:Exception){releaseOwnedResources();throw error}
        active=updated
        resetRecordingEdges()
        lastFlushElapsed=SystemClock.elapsedRealtime()
        startTicker()
        TripRuntimeResult(true,"Trip resumed.",updated)
    }

    suspend fun end():TripRuntimeResult{
        stopTicker()
        return mutex.withLock{
            val current=active?:return@withLock TripRuntimeResult(false,"No active Trip Watch.")
            val flush=try{flushLocked()}catch(error:Exception){if(!current.paused)startTicker();throw error}
            if(flush.writeFailed){if(!current.paused)startTicker();return@withLock TripRuntimeResult(false,"Buffered samples could not be written. Trip recording remains active; check storage and try End again.",current)}
            val latest=active?:current
            val now=System.currentTimeMillis()
            val ended=TripSessionTiming.end(latest,now)
            try{dao.updateSessionAndInsertEvent(ended,TripEventEntity(tripId=current.id,timestamp=now,type="TRIP_ENDED",severity="INFO"))}
            catch(error:Exception){if(!current.paused)startTicker();throw error}
            active=null
            releaseOwnedResources()
            eventTransitions.reset()
            nmeaExpected=false;depthExpected=false;windExpected=false
            TripRuntimeResult(true,"Trip ended and saved.",ended)
        }
    }

    suspend fun waypoint(name:String,note:String,type:String):TripRuntimeResult=mutex.withLock{
        val current=active?:return@withLock TripRuntimeResult(false,"No active Trip Watch.")
        if(current.paused)return@withLock TripRuntimeResult(false,"Resume Trip Watch before marking a waypoint.",current)
        val positionObservation=hub.snapshot.value.position
        val position=positionObservation.value?.takeIf{positionObservation.freshness==VesselDataFreshness.FRESH}?:return@withLock TripRuntimeResult(false,"A fresh current position is required for a waypoint.",current)
        val now=System.currentTimeMillis()
        val updated=current.copy(waypointCount=current.waypointCount+1,eventCount=current.eventCount+1)
        val snapshot=hub.snapshot.value
        dao.updateSessionAndInsertEventAndWaypoint(updated,TripEventEntity(tripId=current.id,timestamp=now,type="USER_WAYPOINT",severity="INFO",latitude=position.latitude,longitude=position.longitude),TripWaypointEntity(tripId=current.id,timestamp=now,latitude=position.latitude,longitude=position.longitude,name=name.trim().ifBlank{"Waypoint ${current.waypointCount+1}"},note=note.trim(),type=type,positionSource=snapshot.position.source.name,sogKnots=snapshot.sogKnots.currentOrHeldValue(),cogTrueDegrees=snapshot.cogTrueDegrees.currentOrHeldValue(),headingTrueDegrees=snapshot.headingTrueDegrees.currentOrHeldValue(),speedThroughWaterKnots=snapshot.speedThroughWaterKnots.currentOrHeldValue(),depthMeters=snapshot.depthMeters.currentOrHeldValue(),trueWindSpeedKnots=snapshot.trueWind.speedKnots.currentOrHeldValue(),trueWindAngleDegrees=snapshot.trueWind.angleDegrees.currentOrHeldValue(),apparentWindSpeedKnots=snapshot.apparentWind.speedKnots.currentOrHeldValue(),apparentWindAngleDegrees=snapshot.apparentWind.angleDegrees.currentOrHeldValue(),heelDegrees=snapshot.attitude.currentOrHeldValue()?.heelDegrees,pitchDegrees=snapshot.attitude.currentOrHeldValue()?.pitchDegrees,pressureHpa=snapshot.pressureHpa.currentOrHeldValue()))
        active=updated
        TripRuntimeResult(true,"Waypoint saved.",updated)
    }

    fun shutdown(){
        stopTicker()
        runBlocking(Dispatchers.IO){withTimeoutOrNull(2_000){mutex.withLock{flushLocked()}}}
        releaseOwnedResources()
    }

    private suspend fun ownResources(session:TripSessionEntity){
        val calibration=mountCalibration.calibration.first()
        val motionRuntimeEnabled=session.phoneMotionEnabled&&vesselAttitude.capabilities.attitudeAvailable&&calibration.calibratedAt>0L&&session.mountCalibrationVersion==calibration.version
        phoneMotionRecordingAllowed=motionRuntimeEnabled
        // A trip that began phone-only may adopt live boat instruments later.
        // Pause/resume must preserve that expectation instead of falling back
        // to the immutable "active at start" flag and silently losing NMEA.
        nmeaTransportOwned=session.nmeaWasActiveAtStart||nmeaExpected
        setResourceRequirement(nmeaTransportOwned)
        if(nmeaTransportOwned)nmeaRuntime.ensureConnected(appSettings.settings.first().profile)
    }
    private fun setResourceRequirement(useNmeaTransport:Boolean){
        resources.set(RuntimeOwner.TRIP_WATCH,RuntimeRequirement(needsSystemLocation=true,needsNmeaTransport=useNmeaTransport,needsWakeLock=true,needsWifiLock=useNmeaTransport,needsPhoneMotion=phoneMotionRecordingAllowed,needsPhoneHeading=true,needsPhonePressure=true))
    }
    /** A phone-only trip may begin using an already connected boat stream
     * later. Claim it at that point so Wi-Fi/NMEA background ownership lasts
     * for the rest of the running leg, without auto-opening a saved endpoint
     * after process restore unless it was active at trip start. */
    private suspend fun claimLiveNmeaTransport(){
        if(nmeaTransportOwned)return
        setResourceRequirement(true)
        try{
            nmeaRuntime.ensureConnected(appSettings.settings.first().profile)
            nmeaTransportOwned=true
        }catch(error:Exception){
            setResourceRequirement(false)
            throw error
        }
    }
    private fun releaseOwnedResources(){resources.release(RuntimeOwner.TRIP_WATCH);phoneMotionRecordingAllowed=false;nmeaTransportOwned=false;nmeaRuntime.releaseIfUnowned()}
    private fun stopTicker(){ticker?.cancel();ticker=null}
    private fun startTicker(){
        ticker?.cancel()
        ticker=scope.launch{
            while(isActive){
                val started=SystemClock.elapsedRealtime()
                try{mutex.withLock{recordLocked()}}
                catch(cancelled:CancellationException){throw cancelled}
                catch(_:Exception){delay(TripSampleWriter.MIN_FLUSH_RETRY_MILLIS)}
                delay((500-(SystemClock.elapsedRealtime()-started)).coerceAtLeast(50))
            }
        }
    }
    private suspend fun recordLocked(){val session=active?.takeIf{!it.paused}?:return;val nowWall=System.currentTimeMillis();val now=SystemClock.elapsedRealtime();val snapshot=hub.snapshot.value;val sample=sample(session,snapshot,nowWall,now);val overflow=writer.enqueue(sample);var newEvents=0
        val bindings=selectedCustomBindings
        // Dashboard visibility is intentionally independent from persistence:
        // only fields explicitly marked "Record in Trips" reach Room.
        val customIds=bindings.keys
        if(customIds.isNotEmpty()){
            val rows=nmeaFields.fields.value.filter{it.key.stableId in customIds&&it.isFresh(now,60_000L)}.map{field->val binding=bindings[field.key.stableId];TripCustomMetricSampleEntity(tripId=session.id,timestamp=nowWall,fieldId=field.key.stableId,displayName=binding?.label?.takeIf{it.isNotBlank()}?:field.key.transducerName?:"${field.key.talker}${field.key.sentenceType}:${field.key.fieldIndex}",numericValue=binding?.transformed(field.value)?:field.value,textValue=field.text,unit=binding?.unitOverride?.takeIf{it.isNotBlank()}?:field.unit,sentenceType=field.key.sentenceType,fieldAgeMillis=(now-field.receivedElapsedRealtime).coerceAtLeast(0))}
            if(rows.isNotEmpty())dao.insertCustomMetrics(rows)
        }
        val positionSource=sample.positionSource.takeIf{sample.latitude!=null&&sample.longitude!=null}?:VesselDataSource.NONE.name
        lastRecordedPositionSource?.takeIf{it!=positionSource}?.let{previous->dao.insertEvent(TripEventEntity(tripId=session.id,timestamp=nowWall,type="POSITION_SOURCE_CHANGED",severity="INFO",detailJson="{\"from\":\"$previous\",\"to\":\"$positionSource\"}"));newEvents++};lastRecordedPositionSource=positionSource
        val headingSource=sample.headingSource.takeIf{sample.headingTrueDegrees!=null}?:VesselDataSource.NONE.name
        lastRecordedHeadingSource?.takeIf{it!=headingSource}?.let{previous->dao.insertEvent(TripEventEntity(tripId=session.id,timestamp=nowWall,type="HEADING_SOURCE_CHANGED",severity="INFO",detailJson="{\"from\":\"$previous\",\"to\":\"$headingSource\"}"));newEvents++};lastRecordedHeadingSource=headingSource
        val gap=sample.latitude==null||sample.longitude==null||(sample.positionAgeMillis?:Long.MAX_VALUE)>30_000L
        if(positionGapOpen!=gap){if(gap){dao.insertEvent(TripEventEntity(tripId=session.id,timestamp=nowWall,type="POSITION_GAP_STARTED",severity="WARNING"));newEvents++}else if(positionGapOpen==true){dao.insertEvent(TripEventEntity(tripId=session.id,timestamp=nowWall,type="POSITION_GAP_ENDED",severity="INFO",latitude=sample.latitude,longitude=sample.longitude));newEvents++};positionGapOpen=gap}
        snapshot.motion.value?.let{motion->val impactAt=motion.impactCandidateElapsedRealtime;if(impactAt!=null&&impactAt!=lastImpactElapsed){dao.insertEvent(TripEventEntity(tripId=session.id,timestamp=nowWall,type="IMPACT_CANDIDATE",severity="ATTENTION",latitude=sample.latitude,longitude=sample.longitude,detailJson="{\"peakG\":${motion.impactPeakG?:0.0},\"heelDegrees\":${sample.heelDegrees?:"null"},\"pitchDegrees\":${sample.pitchDegrees?:"null"},\"sogKnots\":${sample.sogKnots?:"null"}}"));lastImpactElapsed=impactAt;newEvents++}}
        val nmeaAvailable=nmeaRuntime.connectionState.value.hasRecentNmeaTraffic()
        if(nmeaAvailable&&!nmeaTransportOwned)claimLiveNmeaTransport()
        val depthAvailable=sample.depthMeters!=null&&(sample.depthAgeMillis?:Long.MAX_VALUE)<=60_000L
        val windAvailable=(sample.trueWindSpeedKnots!=null&&(sample.trueWindSpeedAgeMillis?:Long.MAX_VALUE)<=60_000L)||(sample.apparentWindSpeedKnots!=null&&(sample.apparentWindSpeedAgeMillis?:Long.MAX_VALUE)<=60_000L)
        val phoneMotionAvailable=session.phoneMotionEnabled&&phoneMotionRecordingAllowed&&sample.heelDegrees!=null&&(sample.attitudeAgeMillis?:Long.MAX_VALUE)<=5_000L&&sample.attitudeQuality==VesselDataQuality.GOOD.name&&!sample.attitudeMountSuspect
        if(nmeaAvailable)nmeaExpected=true
        if(depthAvailable)depthExpected=true
        if(windAvailable)windExpected=true
        eventTransitions.update(TripTransitionInput(now,nmeaExpected,nmeaAvailable,depthExpected,depthAvailable,windExpected,windAvailable,session.phoneMotionEnabled,phoneMotionAvailable,snapshot.attitude.provenance=="PHONE_MOVED_OR_MOUNT_SUSPECT",sample.motionScore)).forEach{event->
            dao.insertEvent(TripEventEntity(tripId=session.id,timestamp=nowWall,type=event.type,severity=event.severity,latitude=sample.latitude,longitude=sample.longitude,detailJson=event.detailJson));newEvents++
        }
        var current=session.copy(eventCount=session.eventCount+newEvents)
        val position=snapshot.position.currentOrHeldValue();val previous=lastPosition;val previousAt=lastPositionAt;val currentSog=snapshot.sogKnots.currentOrHeldValue();if(position!=null&&previous!=null){val distance=AnchorGeometry.distanceMeters(previous.latitude,previous.longitude,position.latitude,position.longitude).takeIf{it<500}?:0.0;current=current.copy(distanceMeters=current.distanceMeters+distance,movingDurationMillis=current.movingDurationMillis+if((currentSog?:0.0)>=.5)(previousAt?.let{(now-it).coerceIn(0,2_000)}?:0)else 0)};if(position!=null){lastPosition=position;lastPositionAt=now}else{lastPosition=null;lastPositionAt=null}
        current=current.copy(sampleCount=current.sampleCount+1,maxSogKnots=max(current.maxSogKnots,sample.sogKnots),maxAbsHeelDegrees=max(current.maxAbsHeelDegrees,sample.heelDegrees?.takeIf{sample.attitudeQuality==VesselDataQuality.GOOD.name&&!sample.attitudeMountSuspect}?.let(::abs)),minDepthMeters=min(current.minDepthMeters,sample.depthMeters),minUkcMeters=min(current.minUkcMeters,sample.ukcMeters));active=current
        if(overflow){dao.insertEvent(TripEventEntity(tripId=session.id,timestamp=nowWall,type="DATA_WRITE_BACKPRESSURE",severity="WARNING"));incrementEvent()}
        val sinceFlush=now-lastFlushElapsed
        if(sinceFlush>=TripSampleWriter.FLUSH_MILLIS||(writer.size()>=TripSampleWriter.FLUSH_SIZE&&sinceFlush>=TripSampleWriter.MIN_FLUSH_RETRY_MILLIS))flushLocked()
    }
    private suspend fun flushLocked():com.yokuli.anchorwatch.data.trip.TripWriterResult{val current=active;val result=writer.flush();if(current!=null&&result.written+result.dropped>0){active=current.copy(droppedSampleCount=current.droppedSampleCount+result.dropped);dao.updateSession(requireNotNull(active))};lastFlushElapsed=SystemClock.elapsedRealtime();return result}
    private suspend fun incrementEvent(){active?.let{current->active=current.copy(eventCount=current.eventCount+1);dao.updateSession(requireNotNull(active))}}
    private fun resetRecordingEdges(){
        lastPosition=null
        lastPositionAt=null
        lastRecordedPositionSource=null
        lastRecordedHeadingSource=null
        positionGapOpen=null
        lastImpactElapsed=null
        eventTransitions.reset()
    }
    private fun NmeaConnectionState.hasRecentNmeaTraffic()=this in setOf(
        NmeaConnectionState.CONNECTED,
        NmeaConnectionState.CONNECTED_NO_FIX,
        NmeaConnectionState.STALE,
    )
    private fun sample(session:TripSessionEntity,s:VesselDataSnapshot,wall:Long,elapsed:Long):TripSampleEntity{
        val attitude=s.attitude.takeIf{session.phoneMotionEnabled&&phoneMotionRecordingAllowed}?:VesselObservation()
        val motion=s.motion.takeIf{session.phoneMotionEnabled&&phoneMotionRecordingAllowed}?:VesselObservation()
        return TripSampleEntity(
            id=0,tripId=session.id,timestamp=wall,
            latitude=s.position.value?.latitude,longitude=s.position.value?.longitude,positionSource=s.position.source.name,positionQuality=s.position.quality.name,positionAgeMillis=age(s.position,elapsed),
            // Change-only instruments retain HELD values, but a STALE value is
            // not serialized as timeless current evidence when the schema has
            // no dedicated SOG/COG age column.
            sogKnots=s.sogKnots.currentOrHeldValue(),cogTrueDegrees=s.cogTrueDegrees.currentOrHeldValue(),sogAgeMillis=age(s.sogKnots,elapsed),cogAgeMillis=age(s.cogTrueDegrees,elapsed),headingTrueDegrees=s.headingTrueDegrees.value,headingSource=s.headingTrueDegrees.source.name,headingAgeMillis=age(s.headingTrueDegrees,elapsed),
            depthMeters=s.depthMeters.value,depthSource=s.depthMeters.source.name,depthAgeMillis=age(s.depthMeters,elapsed),speedThroughWaterKnots=s.speedThroughWaterKnots.value,stwSource=s.speedThroughWaterKnots.source.name,stwAgeMillis=age(s.speedThroughWaterKnots,elapsed),
            trueWindSpeedKnots=s.trueWind.speedKnots.value,trueWindDirectionDegrees=s.trueWind.directionDegrees.value,trueWindAngleDegrees=s.trueWind.angleDegrees.value,apparentWindSpeedKnots=s.apparentWind.speedKnots.value,apparentWindAngleDegrees=s.apparentWind.angleDegrees.value,windSource=s.trueWind.speedKnots.source.name,windAgeMillis=age(s.trueWind.speedKnots,elapsed),
            trueWindSpeedAgeMillis=age(s.trueWind.speedKnots,elapsed),trueWindDirectionAgeMillis=age(s.trueWind.directionDegrees,elapsed),trueWindAngleAgeMillis=age(s.trueWind.angleDegrees,elapsed),apparentWindSpeedAgeMillis=age(s.apparentWind.speedKnots,elapsed),apparentWindAngleAgeMillis=age(s.apparentWind.angleDegrees,elapsed),
            heelDegrees=attitude.value?.heelDegrees,pitchDegrees=attitude.value?.pitchDegrees,rollRateDegPerSec=attitude.value?.rollRateDegreesPerSecond,pitchRateDegPerSec=attitude.value?.pitchRateDegreesPerSecond,yawRateDegPerSec=attitude.value?.yawRateDegreesPerSecond,motionScore=motion.value?.score,rollPeriodSeconds=motion.value?.dominantRollPeriodSeconds,rollPeriodConfidence=motion.value?.rollPeriodConfidence?.name,attitudeAgeMillis=age(attitude,elapsed),attitudeQuality=attitude.quality.name,attitudeMountSuspect=attitude.provenance=="PHONE_MOVED_OR_MOUNT_SUSPECT",
            pressureHpa=s.pressureHpa.value,pressureAgeMillis=age(s.pressureHpa,elapsed),ukcMeters=s.derived.underKeelClearanceMeters.value,
        )
    }
    private fun age(value:VesselObservation<*>,now:Long)=value.receivedElapsedRealtime?.let{(now-it).coerceAtLeast(0)}
    private fun <T> VesselObservation<T>.currentOrHeldValue():T?=value.takeIf{freshness==VesselDataFreshness.FRESH||freshness==VesselDataFreshness.HELD}
    private fun max(a:Double?,b:Double?)=listOfNotNull(a,b).maxOrNull();private fun min(a:Double?,b:Double?)=listOfNotNull(a,b).minOrNull()
}
