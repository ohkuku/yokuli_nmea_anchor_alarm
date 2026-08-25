package com.yokuli.anchorwatch.runtime.output

import com.yokuli.anchorwatch.data.sharing.NmeaOutputMux
import com.yokuli.anchorwatch.data.nmea.output.NmeaGeneratedSentenceValidator
import com.yokuli.anchorwatch.data.vessel.NmeaDeviceOutputSettings
import com.yokuli.anchorwatch.data.vessel.NmeaOutputTransportMode
import com.yokuli.anchorwatch.data.vessel.PhoneHeadingOutputFormat
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.PositionProvider
import com.yokuli.anchorwatch.domain.vessel.VesselDataFreshness
import com.yokuli.anchorwatch.domain.vessel.VesselDataSnapshot
import com.yokuli.anchorwatch.domain.vessel.VesselObservation
import com.yokuli.anchorwatch.domain.vessel.VesselMetricId
import com.yokuli.anchorwatch.domain.vessel.CandidateValidity
import com.yokuli.anchorwatch.domain.vessel.VesselPosition
import com.yokuli.anchorwatch.domain.vessel.persistentKey
import com.yokuli.anchorwatch.domain.vessel.toLegacySource
import com.yokuli.anchorwatch.domain.vessel.source.MetricSourceEligibility
import javax.inject.Inject
import javax.inject.Singleton

enum class AnchorWatchNmeaStream(val periodMillis:Long){
    POSITION(1_000L),
    HEADING(200L),
    MOTION(500L),
    PRESSURE(1_000L),
    DERIVED_WIND(500L),
    DEPTH(1_000L),
    SPEED_THROUGH_WATER(500L),
}

data class AnchorWatchNmeaFeedBatch(
    val stream:AnchorWatchNmeaStream,
    val sentences:List<String>,
    val sourceStableKey:String?=null,
    val suppressionReason:String?=null,
    val sourceConflict:Boolean=false,
)

/** Last complete value owned by the publisher, distinct from VesselDataHub's
 * presentation cache. A lease is renewed by a complete measurement or by a
 * fresh heartbeat from the exact same physical source. */
data class PublishedMetricLease<T>(
    val lastCompleteValue:T,
    val lastNumericMeasurementElapsed:Long,
    val lastSourceHeartbeatElapsed:Long,
    val expiresAtElapsed:Long,
    val sourceStableKey:String,
)

data class PublishedMetricValue<T>(
    val value:T,
    val sourceStableKey:String,
    val held:Boolean,
    val sourceChanged:Boolean,
)

class CanonicalNmeaMetricLeaseBank @Inject constructor(){
    private val leases=linkedMapOf<String,PublishedMetricLease<Any>>()

    @Suppress("UNCHECKED_CAST")
    @Synchronized fun <T> resolve(
        key:String,
        metric:VesselMetricId?,
        observation:VesselObservation<T>,
        candidates:List<com.yokuli.anchorwatch.domain.vessel.VesselSourceCandidate<*>>,
        now:Long,
        measurementLeaseMillis:Long,
        heartbeatFreshMillis:Long,
        allowHeartbeatHold:Boolean=true,
    ):PublishedMetricValue<T>?{
        val existing=leases[key] as? PublishedMetricLease<T>
        val currentKey=existing?.sourceStableKey
        val explicitlyInvalid=currentKey!=null&&candidates.any{candidate->
            candidate.source.persistentKey==currentKey&&candidate.explicitValidity in setOf(CandidateValidity.INVALID,CandidateValidity.DISABLED)
        }
        if(explicitlyInvalid){leases.remove(key);return null}

        val value=observation.value
        val measured=observation.receivedElapsedRealtime
        val heartbeat=observation.sourceHeartbeatElapsedRealtime?:measured
        val sourceKey=observation.sourceIdentity?.persistentKey
            ?:observation.provenance?.takeIf{it.isNotBlank()}
            ?:observation.source.name
        val observationEligible=value!=null&&measured!=null&&heartbeat!=null&&
            observation.freshness in setOf(VesselDataFreshness.FRESH,VesselDataFreshness.HELD)&&
            now>=measured&&now>=heartbeat&&
            candidates.none{candidate->candidate.source.persistentKey==sourceKey&&candidate.explicitValidity in setOf(CandidateValidity.INVALID,CandidateValidity.DISABLED)}
        if(observationEligible){
            val heldByHeartbeat=allowHeartbeatHold&&heartbeat>measured&&now-heartbeat<=heartbeatFreshMillis
            val expiresAt=if(heldByHeartbeat)heartbeat+heartbeatFreshMillis else measured+measurementLeaseMillis
            if(now<=expiresAt){
                val lease=PublishedMetricLease(value as Any,measured,heartbeat,expiresAt,sourceKey)
                leases[key]=lease
                return PublishedMetricValue(value,sourceKey,heldByHeartbeat,currentKey!=null&&currentKey!=sourceKey)
            }
        }

        val retained=existing?:return null
        if(now>retained.expiresAtElapsed){leases.remove(key);return null}
        return PublishedMetricValue(retained.lastCompleteValue,retained.sourceStableKey,true,false)
    }

    @Synchronized fun invalidateSource(sourceStableKey:String){leases.entries.removeAll{it.value.sourceStableKey==sourceStableKey}}
    @Synchronized fun reset(){leases.clear()}
}

/** Destination-aware encoder for the single product publisher. Independent
 * destinations consume VesselDataHub's selected values; SAME_AS_INPUT consumes
 * only provenance-proven local evidence. Raw Boat sentences and BACKUP
 * ownership state never enter this boundary. */
@Singleton
class AnchorWatchNmeaFeedEncoder @Inject constructor(
    private val mux:NmeaOutputMux,
    private val leases:CanonicalNmeaMetricLeaseBank=CanonicalNmeaMetricLeaseBank(),
){
    private var activeInputTransportGeneration:Long?=null
    private var activeInputProfileId:String?=null

    @Synchronized fun encode(
        stream:AnchorWatchNmeaStream,
        snapshot:VesselDataSnapshot,
        settings:NmeaDeviceOutputSettings,
        nowElapsed:Long,
        phoneFix:NavigationFix?=null,
        inputTransportGeneration:Long?=null,
        inputProfileId:String?=null,
    ):AnchorWatchNmeaFeedBatch{
        val sameInput=settings.transportMode==NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION
        activeInputTransportGeneration=inputTransportGeneration.takeIf{sameInput}
        activeInputProfileId=inputProfileId.takeIf{sameInput}
        try{
        val encoded=when(stream){
            AnchorWatchNmeaStream.POSITION->if(sameInput)localPosition(phoneFix,nowElapsed)else position(snapshot,phoneFix,nowElapsed)
            AnchorWatchNmeaStream.HEADING->if(sameInput)localHeading(snapshot,settings.phoneHeadingFormat,nowElapsed)else heading(snapshot,settings.phoneHeadingFormat,nowElapsed)
            AnchorWatchNmeaStream.MOTION->if(sameInput)localMotion(snapshot,nowElapsed)else motion(snapshot,nowElapsed)
            AnchorWatchNmeaStream.PRESSURE->if(!settings.includePressure)EncodedStream(suppressionReason="USER_DISABLED")else if(sameInput)localPressure(snapshot,nowElapsed)else pressure(snapshot,nowElapsed)
            AnchorWatchNmeaStream.DERIVED_WIND->if(!settings.includeDerivedWind)EncodedStream(suppressionReason="USER_DISABLED")else if(sameInput)localOnlyWind(snapshot,nowElapsed)else wind(snapshot,nowElapsed)
            AnchorWatchNmeaStream.DEPTH->if(sameInput)EncodedStream(suppressionReason="SAME_SOCKET_BOAT_METRIC_BLOCKED")else depth(snapshot,nowElapsed)
            AnchorWatchNmeaStream.SPEED_THROUGH_WATER->if(sameInput)EncodedStream(suppressionReason="SAME_SOCKET_BOAT_METRIC_BLOCKED")else speedThroughWater(snapshot,nowElapsed)
        }
        return AnchorWatchNmeaFeedBatch(stream,encoded.sentences.filter(::completePrimaryMeasurement).filter(NmeaGeneratedSentenceValidator::isValid).distinct(),encoded.sourceStableKey,encoded.suppressionReason,encoded.sourceConflict)
        }finally{activeInputTransportGeneration=null;activeInputProfileId=null}
    }

    private data class EncodedStream(val sentences:List<String> = emptyList(),val sourceStableKey:String?=null,val suppressionReason:String?=null,val sourceConflict:Boolean=false)

    /** SAME_AS_INPUT position is one atomic Android Location observation. */
    private fun localPosition(phoneFix:NavigationFix?,now:Long):EncodedStream{
        val fix=phoneFix?.takeIf{value->
            value.valid&&value.positionProvider in setOf(PositionProvider.ANDROID_GNSS,PositionProvider.ANDROID_NETWORK)&&
                now-value.receivedElapsedRealtime in 0L..POSITION_LEASE_MILLIS
        }?:return EncodedStream(suppressionReason="PHONE_GPS_STALE")
        return EncodedStream(mux.phonePosition(fix,now,POSITION_LEASE_MILLIS),"phone:gnss")
    }

    private fun localHeading(snapshot:VesselDataSnapshot,format:PhoneHeadingOutputFormat,now:Long):EncodedStream{
        val allowed=setOf(com.yokuli.anchorwatch.domain.vessel.VesselSourceClass.PHONE_VESSEL_HEADING,com.yokuli.anchorwatch.domain.vessel.VesselSourceClass.PHONE_DEVICE_COMPASS)
        val trueHeading=resolveLocal(VesselMetricId.HEADING_TRUE,snapshot.headingTrueDegrees,snapshot,allowed,now,HEADING_LEASE_MILLIS,HEADING_HEARTBEAT_MILLIS)
        val magnetic=resolveLocal(VesselMetricId.HEADING_MAGNETIC,snapshot.headingMagneticDegrees,snapshot,allowed,now,HEADING_LEASE_MILLIS,HEADING_HEARTBEAT_MILLIS)
        val sentences=buildList{
            if(format!=PhoneHeadingOutputFormat.HDG_MAGNETIC&&trueHeading!=null)add(mux.phoneHeading(trueHeading.value))
            if(format!=PhoneHeadingOutputFormat.HDT_TRUE&&magnetic!=null){val variation=trueHeading?.let{signedAngle(it.value-magnetic.value)};add(mux.phoneMagneticHeading(magnetic.value,variation))}
        }
        return EncodedStream(sentences,trueHeading?.sourceStableKey?:magnetic?.sourceStableKey,if(sentences.isEmpty())"PHONE_HEADING_STALE" else null,sourceConflict(snapshot,VesselMetricId.HEADING_TRUE,allowed))
    }

    private fun localMotion(snapshot:VesselDataSnapshot,now:Long):EncodedStream{
        val attitude=localObservation(snapshot.attitude,setOf(com.yokuli.anchorwatch.domain.vessel.VesselSourceClass.PHONE_IMU))
            ?.let{leases.resolve("LOCAL_ATTITUDE",null,it,emptyList(),now,5_000L,5_000L)}
            ?:return EncodedStream(suppressionReason="PHONE_MOTION_STALE")
        val sentences=buildList{add(mux.phoneRateOfTurn(attitude.value.yawRateDegreesPerSecond*60.0));mux.phoneXdr(attitude.value,null)?.let(::add)}
        return EncodedStream(sentences,attitude.sourceStableKey,sourceConflict=sourceConflict(snapshot,VesselMetricId.RATE_OF_TURN,setOf(com.yokuli.anchorwatch.domain.vessel.VesselSourceClass.PHONE_IMU)))
    }

    private fun localPressure(snapshot:VesselDataSnapshot,now:Long):EncodedStream{
        val allowed=setOf(com.yokuli.anchorwatch.domain.vessel.VesselSourceClass.PHONE_BAROMETER)
        val pressure=resolveLocal(VesselMetricId.PRESSURE,snapshot.pressureHpa,snapshot,allowed,now,PRESSURE_LEASE_MILLIS,PRESSURE_HEARTBEAT_MILLIS)
            ?:return EncodedStream(suppressionReason="PHONE_PRESSURE_STALE")
        return EncodedStream(pressure.value.let{mux.phoneXdr(null,it)}?.let(::listOf).orEmpty(),pressure.sourceStableKey,sourceConflict=sourceConflict(snapshot,VesselMetricId.PRESSURE,allowed))
    }

    private fun localOnlyWind(snapshot:VesselDataSnapshot,now:Long):EncodedStream{
        val observations=listOf(snapshot.trueWind.speedKnots,snapshot.trueWind.directionDegrees,snapshot.trueWind.angleDegrees)
        val decisions=observations.map{SameSocketProvenanceFirewall.evaluate(it,activeInputProfileId,activeInputTransportGeneration)}
        if(decisions.any{!it.allowed})return EncodedStream(suppressionReason=decisions.first{!it.allowed}.reason.name)
        val speed=resolve(VesselMetricId.TRUE_WIND_SPEED,snapshot.trueWind.speedKnots,snapshot,now,WIND_LEASE_MILLIS,WIND_HEARTBEAT_MILLIS)
        val direction=resolve(VesselMetricId.TRUE_WIND_DIRECTION,snapshot.trueWind.directionDegrees,snapshot,now,WIND_LEASE_MILLIS,WIND_HEARTBEAT_MILLIS)
        val angle=resolve(VesselMetricId.TRUE_WIND_ANGLE,snapshot.trueWind.angleDegrees,snapshot,now,WIND_LEASE_MILLIS,WIND_HEARTBEAT_MILLIS)
        if(speed==null||direction==null||angle==null)return EncodedStream(suppressionReason="NO_LOCAL_DERIVED_WIND")
        return EncodedStream(mux.derivedTrueWind(speed.value,direction.value,angle.value),listOf(speed.sourceStableKey,direction.sourceStableKey,angle.sourceStableKey).distinct().joinToString("+"))
    }

    private fun position(snapshot:VesselDataSnapshot,phoneFix:NavigationFix?,now:Long):EncodedStream{
        val resolved=resolve(VesselMetricId.POSITION,snapshot.position,snapshot,now,POSITION_LEASE_MILLIS,POSITION_LEASE_MILLIS,false)?:return EncodedStream()
        val position=resolved.value
        val phoneMetadata=phoneFix?.takeIf{fix->snapshot.position.sourceClass==com.yokuli.anchorwatch.domain.vessel.VesselSourceClass.PHONE_GNSS&&kotlin.math.abs(fix.latitude-position.latitude)<0.000001&&kotlin.math.abs(fix.longitude-position.longitude)<0.000001}
        val sog=resolve(VesselMetricId.SOG,snapshot.sogKnots,snapshot,now,5_000L,5_000L)?.value
        val cog=resolve(VesselMetricId.COG,snapshot.cogTrueDegrees,snapshot,now,5_000L,5_000L)?.value
        val fix=NavigationFix(
            latitude=position.latitude,longitude=position.longitude,
            timestampUtcMillis=snapshot.position.observedAtUtcMillis?:phoneMetadata?.timestampUtcMillis,
            receivedElapsedRealtime=snapshot.position.receivedElapsedRealtime?:now,
            sogKnots=sog?:phoneMetadata?.sogKnots,cogTrueDegrees=cog?:phoneMetadata?.cogTrueDegrees,
            hdop=position.hdop?:phoneMetadata?.hdop,satellites=position.satellites?:phoneMetadata?.satellites,
            altitudeMeters=position.altitudeMeters?:phoneMetadata?.altitudeMeters,
            horizontalAccuracyMeters=position.horizontalAccuracyMeters?:phoneMetadata?.horizontalAccuracyMeters,
            positionProvider=when(snapshot.position.sourceClass){
                com.yokuli.anchorwatch.domain.vessel.VesselSourceClass.BOAT_NMEA->PositionProvider.NMEA
                com.yokuli.anchorwatch.domain.vessel.VesselSourceClass.DEMO->PositionProvider.DEMO
                else->PositionProvider.ANDROID_GNSS
            },
            isMockLocation=phoneMetadata?.isMockLocation?:false,
            sourceSentence="ANCHOR_WATCH_CANONICAL",valid=true,
        )
        return EncodedStream(mux.phonePosition(fix,now,POSITION_LEASE_MILLIS),resolved.sourceStableKey)
    }

    private fun heading(snapshot:VesselDataSnapshot,format:PhoneHeadingOutputFormat,now:Long):EncodedStream{
        val trueHeading=resolve(VesselMetricId.HEADING_TRUE,snapshot.headingTrueDegrees,snapshot,now,HEADING_LEASE_MILLIS,HEADING_HEARTBEAT_MILLIS)
        val magnetic=resolve(VesselMetricId.HEADING_MAGNETIC,snapshot.headingMagneticDegrees,snapshot,now,HEADING_LEASE_MILLIS,HEADING_HEARTBEAT_MILLIS)
        val sentences=buildList{
            if(format!=PhoneHeadingOutputFormat.HDG_MAGNETIC&&trueHeading!=null)add(mux.phoneHeading(trueHeading.value))
            if(format!=PhoneHeadingOutputFormat.HDT_TRUE&&magnetic!=null){
                val variation=trueHeading?.let{signedAngle(it.value-magnetic.value)}
                add(mux.phoneMagneticHeading(magnetic.value,variation))
            }
        }
        return EncodedStream(sentences,trueHeading?.sourceStableKey?:magnetic?.sourceStableKey)
    }

    private fun motion(snapshot:VesselDataSnapshot,now:Long):EncodedStream{
        val rateOfTurn=resolve(VesselMetricId.RATE_OF_TURN,snapshot.rateOfTurnDegreesPerMinute,snapshot,now,ROT_LEASE_MILLIS,ROT_HEARTBEAT_MILLIS)
        val attitude=eligibleObservation(snapshot.attitude)?.let{leases.resolve("ATTITUDE",null,it,emptyList(),now,5_000L,5_000L)}
        val sentences=buildList{
        (rateOfTurn?.value?:attitude?.value?.yawRateDegreesPerSecond?.times(60.0))?.let{add(mux.phoneRateOfTurn(it))}
        attitude?.value?.let{attitude->
            mux.phoneXdr(attitude,null)?.let(::add)
        }
        }
        return EncodedStream(sentences,rateOfTurn?.sourceStableKey?:attitude?.sourceStableKey)
    }

    private fun pressure(snapshot:VesselDataSnapshot,now:Long):EncodedStream{
        val pressure=resolve(VesselMetricId.PRESSURE,snapshot.pressureHpa,snapshot,now,PRESSURE_LEASE_MILLIS,PRESSURE_HEARTBEAT_MILLIS)
        return EncodedStream(pressure?.value?.let{mux.phoneXdr(null,it)}?.let(::listOf).orEmpty(),pressure?.sourceStableKey)
    }

    private fun wind(snapshot:VesselDataSnapshot,now:Long):EncodedStream{
        val speed=resolve(VesselMetricId.TRUE_WIND_SPEED,snapshot.trueWind.speedKnots,snapshot,now,WIND_LEASE_MILLIS,WIND_HEARTBEAT_MILLIS)
        val direction=resolve(VesselMetricId.TRUE_WIND_DIRECTION,snapshot.trueWind.directionDegrees,snapshot,now,WIND_LEASE_MILLIS,WIND_HEARTBEAT_MILLIS)
        val angle=resolve(VesselMetricId.TRUE_WIND_ANGLE,snapshot.trueWind.angleDegrees,snapshot,now,WIND_LEASE_MILLIS,WIND_HEARTBEAT_MILLIS)
        if(speed==null||direction==null||angle==null)return EncodedStream()
        return EncodedStream(mux.derivedTrueWind(speed.value,direction.value,angle.value),listOf(speed.sourceStableKey,direction.sourceStableKey,angle.sourceStableKey).distinct().joinToString("+"))
    }

    private fun depth(snapshot:VesselDataSnapshot,now:Long):EncodedStream{
        val depth=resolve(VesselMetricId.DEPTH,snapshot.depthMeters,snapshot,now,DEPTH_LEASE_MILLIS,DEPTH_HEARTBEAT_MILLIS)?:return EncodedStream()
        if(depth.value !in 0.05..12_000.0)return EncodedStream()
        return EncodedStream(listOf(mux.canonicalDepth(depth.value)),depth.sourceStableKey)
    }

    private fun speedThroughWater(snapshot:VesselDataSnapshot,now:Long):EncodedStream{
        val speed=resolve(VesselMetricId.SPEED_THROUGH_WATER,snapshot.speedThroughWaterKnots,snapshot,now,STW_LEASE_MILLIS,STW_HEARTBEAT_MILLIS)?:return EncodedStream()
        if(speed.value !in 0.0..200.0)return EncodedStream()
        val trueHeading=snapshot.headingTrueDegrees.value.takeIf{snapshot.headingTrueDegrees.freshness in setOf(VesselDataFreshness.FRESH,VesselDataFreshness.HELD)}
        val magneticHeading=snapshot.headingMagneticDegrees.value.takeIf{snapshot.headingMagneticDegrees.freshness in setOf(VesselDataFreshness.FRESH,VesselDataFreshness.HELD)}
        return EncodedStream(listOf(mux.canonicalSpeedThroughWater(speed.value,trueHeading,magneticHeading)),speed.sourceStableKey)
    }

    private fun <T> resolve(metric:VesselMetricId,observation:VesselObservation<T>,snapshot:VesselDataSnapshot,now:Long,lease:Long,heartbeat:Long,allowHeartbeat:Boolean=true):PublishedMetricValue<T>?{
        val eligible=eligibleObservation(observation)?:return null
        val candidates=snapshot.candidates[metric].orEmpty().filter{candidate->candidate.sourceClass!=com.yokuli.anchorwatch.domain.vessel.VesselSourceClass.BOAT_NMEA||activeInputTransportGeneration==null||candidate.source.connectionGeneration==activeInputTransportGeneration}
        return leases.resolve(metric.name,metric,eligible,candidates,now,lease,heartbeat,allowHeartbeat)
    }

    private fun <T> resolveLocal(
        metric:VesselMetricId,
        selected:VesselObservation<T>,
        snapshot:VesselDataSnapshot,
        allowedClasses:Set<com.yokuli.anchorwatch.domain.vessel.VesselSourceClass>,
        now:Long,
        lease:Long,
        heartbeat:Long,
    ):PublishedMetricValue<T>?{
        val observation=localObservation(selected,allowedClasses)?:snapshot.candidates[metric].orEmpty().asSequence()
            .filter{it.sourceClass in allowedClasses&&it.explicitValidity in setOf(CandidateValidity.ELIGIBLE,CandidateValidity.LOW_QUALITY)}
            .filter{SameSocketProvenanceFirewall.evaluate(it.source,it.sourceClass,it.provenance,activeInputProfileId,activeInputTransportGeneration).allowed}
            .sortedByDescending{it.sourceHeartbeatElapsedRealtime}
            .mapNotNull{candidate->candidate.toObservation<T>()}
            .firstOrNull()
            ?:VesselObservation()
        return leases.resolve("LOCAL_${metric.name}",metric,observation,snapshot.candidates[metric].orEmpty(),now,lease,heartbeat)
    }

    private fun <T> localObservation(
        observation:VesselObservation<T>,
        allowedClasses:Set<com.yokuli.anchorwatch.domain.vessel.VesselSourceClass>,
    ):VesselObservation<T>?{
        if(observation.value==null||observation.sourceClass !in allowedClasses)return null
        return observation.takeIf{SameSocketProvenanceFirewall.evaluate(it,activeInputProfileId,activeInputTransportGeneration).allowed}
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> com.yokuli.anchorwatch.domain.vessel.VesselSourceCandidate<*>.toObservation():VesselObservation<T>?{
        val typed=value as? T?:return null
        return VesselObservation(
            value=typed,
            source=sourceClass.toLegacySource(),
            observedAtUtcMillis=observedAtUtcMillis,
            receivedElapsedRealtime=receivedElapsedRealtime,
            quality=quality,
            freshness=if(sourceHeartbeatElapsedRealtime>receivedElapsedRealtime)VesselDataFreshness.HELD else VesselDataFreshness.FRESH,
            provenance=source.displayName,
            sourceIdentity=source,
            sourceClass=sourceClass,
            reference=reference,
            provenanceDetail=provenance,
            sourceHeartbeatElapsedRealtime=sourceHeartbeatElapsedRealtime,
        )
    }

    private fun sourceConflict(
        snapshot:VesselDataSnapshot,
        metric:VesselMetricId,
        localClasses:Set<com.yokuli.anchorwatch.domain.vessel.VesselSourceClass>,
    ):Boolean{
        val candidates=snapshot.candidates[metric].orEmpty()
        return candidates.any{it.sourceClass in localClasses}&&candidates.any{it.sourceClass==com.yokuli.anchorwatch.domain.vessel.VesselSourceClass.BOAT_NMEA}
    }
    private fun <T> eligibleObservation(observation:VesselObservation<T>):VesselObservation<T>?{
        val expected=activeInputTransportGeneration
        if(expected!=null&&observation.sourceClass==com.yokuli.anchorwatch.domain.vessel.VesselSourceClass.BOAT_NMEA&&observation.sourceIdentity?.connectionGeneration!=expected){observation.sourceIdentity?.persistentKey?.let(leases::invalidateSource);return null}
        return observation
    }
    private fun signedAngle(value:Double)=((value+540.0)%360.0)-180.0

    fun reset(){leases.reset()}

    /** Main measurement fields must never be blank. Optional NMEA metadata may
     * remain empty where the standard permits it. */
    private fun completePrimaryMeasurement(sentence:String):Boolean{
        val fields=sentence.trim().substringBefore('*').split(',')
        val type=mux.sentenceType(sentence)?:return false
        return when(type){
            "HDT","HDG","ROT"->fields.getOrNull(1)?.isNotBlank()==true
            "XDR"->fields.size>=5&&fields.drop(1).chunked(4).all{it.size==4&&it[1].isNotBlank()}
            "MWD"->fields.getOrNull(1)?.isNotBlank()==true&&fields.getOrNull(5)?.isNotBlank()==true
            "MWV","VWT"->fields.getOrNull(1)?.isNotBlank()==true&&fields.getOrNull(3)?.isNotBlank()==true
            "DBT"->fields.getOrNull(3)?.isNotBlank()==true
            "VHW"->fields.getOrNull(5)?.isNotBlank()==true
            "RMC","GGA"->fields.getOrNull(3)?.isNotBlank()==true&&fields.getOrNull(5)?.isNotBlank()==true
            "VTG"->fields.getOrNull(1)?.isNotBlank()==true&&fields.getOrNull(5)?.isNotBlank()==true
            "ZDA"->fields.getOrNull(1)?.isNotBlank()==true
            else->false
        }
    }

    private companion object{
        const val POSITION_LEASE_MILLIS=3_000L
        const val HEADING_LEASE_MILLIS=15_000L
        const val HEADING_HEARTBEAT_MILLIS=5_000L
        const val PRESSURE_LEASE_MILLIS=60_000L
        const val PRESSURE_HEARTBEAT_MILLIS=60_000L
        const val WIND_LEASE_MILLIS=10_000L
        const val WIND_HEARTBEAT_MILLIS=10_000L
        const val DEPTH_LEASE_MILLIS=60_000L
        const val DEPTH_HEARTBEAT_MILLIS=60_000L
        const val STW_LEASE_MILLIS=10_000L
        const val STW_HEARTBEAT_MILLIS=10_000L
        const val ROT_LEASE_MILLIS=3_000L
        const val ROT_HEARTBEAT_MILLIS=3_000L
    }
}

/** Deterministic heartbeat scheduler; tests can advance monotonic time without
 * sleeping for ten minutes. */
class AnchorWatchNmeaHeartbeat{
    private val last=mutableMapOf<AnchorWatchNmeaStream,Long>()
    fun due(nowElapsed:Long):List<AnchorWatchNmeaStream> = AnchorWatchNmeaStream.entries.filter{stream->
        val previous=last[stream]
        if(previous==null||nowElapsed-previous>=stream.periodMillis){last[stream]=nowElapsed;true}else false
    }
    fun reset(){last.clear()}
}

/** Monotonic publication lease. Stop invalidates every queued/in-flight batch
 * before transport shutdown begins. */
class NmeaPublicationSessionGate{
    private var generation=0L
    private var running=false
    @Synchronized fun start():Long{generation++;running=true;return generation}
    @Synchronized fun stop():Long{generation++;running=false;return generation}
    @Synchronized fun current()=generation
    @Synchronized fun accepts(candidate:Long)=running&&candidate==generation
}
