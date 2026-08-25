package com.yokuli.anchorwatch.runtime.output

import com.yokuli.anchorwatch.data.sharing.NmeaOutputMux
import com.yokuli.anchorwatch.data.vessel.NmeaDeviceOutputSettings
import com.yokuli.anchorwatch.data.vessel.PhoneHeadingOutputFormat
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.vessel.VesselDataFreshness
import com.yokuli.anchorwatch.domain.vessel.VesselDataSnapshot
import com.yokuli.anchorwatch.domain.vessel.VesselObservation
import com.yokuli.anchorwatch.domain.vessel.VesselSourceClass
import com.yokuli.anchorwatch.domain.vessel.VesselMetricId
import com.yokuli.anchorwatch.domain.vessel.CandidateValidity
import javax.inject.Inject
import javax.inject.Singleton

enum class AnchorWatchNmeaStream(val periodMillis:Long){
    POSITION(1_000L),
    HEADING(200L),
    MOTION(500L),
    PRESSURE(1_000L),
    DERIVED_WIND(500L),
}

data class AnchorWatchNmeaFeedBatch(
    val stream:AnchorWatchNmeaStream,
    val sentences:List<String>,
)

/** Stateless encoder for the single normal-product NMEA feed. It consumes only
 * VesselDataHub's selected values and never accepts raw boat sentences or
 * publication-ownership/BACKUP state. */
@Singleton
class AnchorWatchNmeaFeedEncoder @Inject constructor(private val mux:NmeaOutputMux){
    fun encode(
        stream:AnchorWatchNmeaStream,
        snapshot:VesselDataSnapshot,
        settings:NmeaDeviceOutputSettings,
        nowElapsed:Long,
        phoneFix:NavigationFix?=null,
    ):AnchorWatchNmeaFeedBatch{
        val sentences=when(stream){
            AnchorWatchNmeaStream.POSITION->position(phoneFix,nowElapsed)
            AnchorWatchNmeaStream.HEADING->heading(snapshot,settings.phoneHeadingFormat,nowElapsed)
            AnchorWatchNmeaStream.MOTION->motion(snapshot,nowElapsed)
            AnchorWatchNmeaStream.PRESSURE->if(settings.includePressure)pressure(snapshot,nowElapsed)else emptyList()
            AnchorWatchNmeaStream.DERIVED_WIND->if(settings.includeDerivedWind)derivedWind(snapshot,nowElapsed)else emptyList()
        }
        return AnchorWatchNmeaFeedBatch(stream,sentences.filter(::completePrimaryMeasurement).distinct())
    }

    private fun position(phoneFix:NavigationFix?,now:Long):List<String> = phoneFix?.let{mux.phonePosition(it,now,POSITION_LEASE_MILLIS)}.orEmpty()

    private fun heading(snapshot:VesselDataSnapshot,format:PhoneHeadingOutputFormat,now:Long):List<String>{
        val trueHeading=phoneValue(snapshot,VesselMetricId.HEADING_TRUE,snapshot.headingTrueDegrees,setOf(VesselSourceClass.PHONE_VESSEL_HEADING),now,HEADING_LEASE_MILLIS)
        val magnetic=phoneValue(snapshot,VesselMetricId.HEADING_MAGNETIC,snapshot.headingMagneticDegrees,setOf(VesselSourceClass.PHONE_VESSEL_HEADING),now,HEADING_LEASE_MILLIS)
        return buildList{
            if(format!=PhoneHeadingOutputFormat.HDG_MAGNETIC&&trueHeading!=null)add(mux.phoneHeading(trueHeading))
            if(format!=PhoneHeadingOutputFormat.HDT_TRUE&&magnetic!=null){
                val variation=trueHeading?.let{signedAngle(it-magnetic)}
                add(mux.phoneMagneticHeading(magnetic,variation))
            }
        }
    }

    private fun motion(snapshot:VesselDataSnapshot,now:Long)=buildList{
        snapshot.attitude.value?.takeIf{snapshot.attitude.sourceClass==VesselSourceClass.PHONE_IMU&&usable(snapshot.attitude,now)}?.let{attitude->
            add(mux.phoneRateOfTurn(attitude.yawRateDegreesPerSecond*60.0));mux.phoneXdr(attitude,null)?.let(::add)
        }
    }

    private fun pressure(snapshot:VesselDataSnapshot,now:Long)=phoneValue(snapshot,VesselMetricId.PRESSURE,snapshot.pressureHpa,setOf(VesselSourceClass.PHONE_BAROMETER),now,PRESSURE_LEASE_MILLIS)
        ?.let{mux.phoneXdr(null,it)}
        ?.let(::listOf)
        .orEmpty()

    private fun derivedWind(snapshot:VesselDataSnapshot,now:Long):List<String>{
        val speed=snapshot.trueWind.speedKnots;val direction=snapshot.trueWind.directionDegrees;val angle=snapshot.trueWind.angleDegrees
        val derived=listOf(speed,direction,angle).all{it.sourceClass in setOf(VesselSourceClass.DERIVED_WATER,VesselSourceClass.DERIVED_GROUND)}
        if(!derived||!usable(speed,now)||!usable(direction,now)||!usable(angle,now))return emptyList()
        return mux.derivedTrueWind(speed.value!!,direction.value!!,angle.value!!)
    }

    private fun <T> usable(value:VesselObservation<T>,now:Long)=value.value!=null&&value.receivedElapsedRealtime?.let{now>=it}==true&&value.freshness in setOf(VesselDataFreshness.FRESH,VesselDataFreshness.HELD)
    private fun phoneValue(snapshot:VesselDataSnapshot,metric:VesselMetricId,selected:VesselObservation<Double>,classes:Set<VesselSourceClass>,now:Long,maxAge:Long):Double?{
        if(selected.sourceClass in classes&&usable(selected,now)&&selected.receivedElapsedRealtime?.let{now-it<=maxAge}==true)return selected.value
        return snapshot.candidates[metric].orEmpty().asSequence()
            .filter{it.sourceClass in classes&&it.validity in setOf(CandidateValidity.ELIGIBLE,CandidateValidity.LOW_QUALITY)&&now-it.receivedElapsedRealtime in 0L..maxAge}
            .sortedByDescending{it.receivedElapsedRealtime}
            .mapNotNull{it.value as? Double}
            .firstOrNull()
    }
    private fun signedAngle(value:Double)=((value+540.0)%360.0)-180.0

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

    private companion object{const val POSITION_LEASE_MILLIS=30_000L;const val HEADING_LEASE_MILLIS=5_000L;const val PRESSURE_LEASE_MILLIS=10*60_000L}
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
