package com.yokuli.anchorwatch.runtime.anchor

import android.os.SystemClock
import com.yokuli.anchorwatch.data.database.AnchorTelemetrySampleEntity
import com.yokuli.anchorwatch.data.database.TripDao
import com.yokuli.anchorwatch.data.vessel.VesselDataHub
import com.yokuli.anchorwatch.domain.vessel.VesselObservation
import com.yokuli.anchorwatch.runtime.RuntimeOwner
import com.yokuli.anchorwatch.runtime.RuntimeRequirement
import com.yokuli.anchorwatch.runtime.RuntimeResourceManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*

/** Low-rate Anchor environment recorder, deliberately independent from track writes. */
@Singleton
class AnchorTelemetryRuntime @Inject constructor(private val dao:TripDao,private val hub:VesselDataHub,private val resources:RuntimeResourceManager){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);private val lock=Any();private val pending=ArrayDeque<AnchorTelemetrySampleEntity>();private var sessionId:Long?=null;private var job:Job?=null
    @Synchronized fun configure(activeSessionId:Long?){
        if(sessionId==activeSessionId)return
        job?.cancel();job=null;sessionId=activeSessionId
        if(activeSessionId==null){resources.release(RuntimeOwner.ANCHOR_TELEMETRY);scope.launch{flush()};return}
        resources.set(RuntimeOwner.ANCHOR_TELEMETRY,RuntimeRequirement(needsPhoneMotion=true,needsPhonePressure=true))
        job=scope.launch{while(isActive){record(activeSessionId);delay(1_000)}}
    }
    private suspend fun record(id:Long){val now=SystemClock.elapsedRealtime();val value=hub.snapshot.value;val sample=AnchorTelemetrySampleEntity(sessionId=id,timestamp=System.currentTimeMillis(),depthMeters=value.depthMeters.value,depthAgeMillis=age(value.depthMeters,now),trueWindSpeedKnots=value.trueWind.speedKnots.value,trueWindDirectionDegrees=value.trueWind.directionDegrees.value,windAgeMillis=age(value.trueWind.speedKnots,now),heelDegrees=value.attitude.value?.heelDegrees,pitchDegrees=value.attitude.value?.pitchDegrees,rollRateDegPerSec=value.attitude.value?.rollRateDegreesPerSecond,pitchRateDegPerSec=value.attitude.value?.pitchRateDegreesPerSecond,yawRateDegPerSec=value.attitude.value?.yawRateDegreesPerSecond,motionScore=value.motion.value?.score,rollPeriodSeconds=value.motion.value?.dominantRollPeriodSeconds,rollPeriodConfidence=value.motion.value?.rollPeriodConfidence?.name,pressureHpa=value.pressureHpa.value);synchronized(lock){if(pending.size>=MAX_PENDING)pending.removeFirst();pending.addLast(sample)};if(synchronized(lock){pending.size>=12})flush()}
    suspend fun flush(){
        val values=synchronized(lock){pending.toList().also{pending.clear()}}
        if(values.isEmpty())return
        try{dao.insertAnchorTelemetry(values)}catch(failure:Exception){
            synchronized(lock){
                val restored=ArrayDeque<AnchorTelemetrySampleEntity>(values.size+pending.size)
                values.forEach(restored::addLast);pending.forEach(restored::addLast)
                while(restored.size>MAX_PENDING)restored.removeFirst()
                pending.clear();restored.forEach(pending::addLast)
            }
            if(failure is CancellationException)throw failure
        }
    }
    fun shutdown(){job?.cancel();resources.release(RuntimeOwner.ANCHOR_TELEMETRY);runBlocking(Dispatchers.IO){withTimeoutOrNull(2_000){flush()}}}
    private fun age(value:VesselObservation<*>,now:Long)=value.receivedElapsedRealtime?.let{(now-it).coerceAtLeast(0)}
    private companion object{const val MAX_PENDING=120}
}
