package com.yokuli.anchorwatch.data.nmea

import android.os.SystemClock
import com.yokuli.anchorwatch.data.NavigationRepository
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

enum class NmeaFieldSemantic {
    ROT, RUDDER_ANGLE, HEEL, PITCH, RAW_ANGULAR, WATER_TEMPERATURE, AIR_TEMPERATURE, AIR_PRESSURE,
    CURRENT_SET_TRUE, CURRENT_DRIFT, CROSS_TRACK_ERROR, BEARING_TO_WAYPOINT,
    DISTANCE_TO_WAYPOINT, DESTINATION_WAYPOINT, TOTAL_LOG, TRIP_LOG,
    APPARENT_WIND_ANGLE, APPARENT_WIND_SPEED, TRUE_WIND_ANGLE, TRUE_WIND_SPEED, TRUE_WIND_DIRECTION,
    RAW,
}

data class NmeaFieldKey(
    val talker:String,
    val sentenceType:String,
    val fieldIndex:Int,
    val semantic:NmeaFieldSemantic=NmeaFieldSemantic.RAW,
    val transducerName:String?=null,
){
    val stableId:String get()=listOf(talker,sentenceType,fieldIndex.toString(),semantic.name,transducerName.orEmpty()).joinToString(":")
}

data class NmeaFieldObservation(
    val key:NmeaFieldKey,
    val value:Double?=null,
    val text:String?=null,
    val unit:String?=null,
    val receivedElapsedRealtime:Long,
    val rawSentence:String,
){
    fun isFresh(nowElapsed:Long,maxAgeMillis:Long=30_000L)=nowElapsed-receivedElapsedRealtime in 0L..maxAgeMillis
}

data class NmeaFieldHeartbeat(val talker:String,val sentenceType:String,val allowsHold:Boolean)

/** Same-source last-value cache for the generic field bus (MDA/XDR/etc.). */
class NmeaFieldRetentionBuffer(private val retentionMillis:Long=30_000L){
    private val held=linkedMapOf<String,NmeaFieldObservation>()
    @Synchronized fun accept(decoded:List<NmeaFieldObservation>,heartbeat:NmeaFieldHeartbeat?,rawLine:String,elapsed:Long):List<NmeaFieldObservation>{
        if(heartbeat?.allowsHold==false){
            held.entries.removeAll{(_,value)->value.key.talker==heartbeat.talker&&value.key.sentenceType==heartbeat.sentenceType}
        }else if(heartbeat!=null){
            val updated=decoded.mapTo(mutableSetOf()){it.key.stableId}
            val carried=held.entries.filter{(_,value)->value.key.talker==heartbeat.talker&&value.key.sentenceType==heartbeat.sentenceType&&value.key.stableId !in updated}
            carried.forEach{(id,value)->held[id]=value.copy(receivedElapsedRealtime=elapsed,rawSentence=rawLine.trim())}
        }
        decoded.forEach{held[it.key.stableId]=it}
        held.entries.removeAll{elapsed-it.value.receivedElapsedRealtime>retentionMillis}
        return held.values.sortedWith(compareBy<NmeaFieldObservation>{it.key.sentenceType}.thenBy{it.key.fieldIndex}.thenBy{it.key.transducerName.orEmpty()})
    }
    @Synchronized fun clear(){held.clear()}
}

/**
 * Produces a discoverable field bus without changing the safety parser. Empty
 * fields are deliberately omitted: an absent field means no update and never
 * erases the most recent observation from that field key.
 */
object NmeaFieldDecoder {
    fun heartbeat(line:String):NmeaFieldHeartbeat?{
        if(!NmeaChecksum.validate(line,required=false))return null
        val fields=line.trim().removePrefix("$").substringBefore('*').split(',')
        val id=fields.firstOrNull()?.uppercase(Locale.US).orEmpty();if(id.length<3)return null
        val type=id.takeLast(3);val talker=id.dropLast(3).ifBlank{"--"}
        val explicitlyInvalid=when(type){
            "RMC"->fields.getOrNull(2).equals("V",true)
            "GGA"->fields.getOrNull(6)?.toIntOrNull()==0
            "GLL"->fields.getOrNull(6).equals("V",true)
            "MWV"->fields.getOrNull(5).equals("V",true)
            "ROT"->fields.getOrNull(2).equals("V",true)
            "RSA"->fields.getOrNull(2).equals("V",true)&&fields.getOrNull(4).equals("V",true)
            "RMB"->fields.getOrNull(1).equals("V",true)
            "XTE","APB"->fields.getOrNull(2).equals("V",true)
            else->false
        }
        return NmeaFieldHeartbeat(talker,type,!explicitlyInvalid)
    }

    fun decode(line:String,elapsed:Long=SystemClock.elapsedRealtime()):List<NmeaFieldObservation>{
        if(!NmeaChecksum.validate(line,required=false))return emptyList()
        val raw=line.trim();val fields=raw.removePrefix("$").substringBefore('*').split(',')
        val id=fields.firstOrNull()?.uppercase(Locale.US).orEmpty();if(id.length<3)return emptyList()
        val type=id.takeLast(3);val talker=id.dropLast(3).ifBlank{"--"}
        fun number(index:Int,semantic:NmeaFieldSemantic,unit:String?=null,transform:(Double)->Double={it}):NmeaFieldObservation?{
            val value=fields.getOrNull(index)?.toDoubleOrNull()?:return null
            return NmeaFieldObservation(NmeaFieldKey(talker,type,index,semantic),transform(value),unit=unit,receivedElapsedRealtime=elapsed,rawSentence=raw)
        }
        fun text(index:Int,semantic:NmeaFieldSemantic):NmeaFieldObservation?=fields.getOrNull(index)?.trim()?.takeIf{it.isNotEmpty()}?.let{NmeaFieldObservation(NmeaFieldKey(talker,type,index,semantic),text=it,receivedElapsedRealtime=elapsed,rawSentence=raw)}
        fun signed(index:Int,sideIndex:Int,semantic:NmeaFieldSemantic,unit:String):NmeaFieldObservation?=number(index,semantic,unit){value->if(fields.getOrNull(sideIndex)?.uppercase(Locale.US) in setOf("L","P"))-kotlin.math.abs(value) else kotlin.math.abs(value)}
        val known=when(type){
            "VLW"->listOfNotNull(number(1,NmeaFieldSemantic.TOTAL_LOG,"NM"),number(3,NmeaFieldSemantic.TRIP_LOG,"NM"))
            "VWR"->listOfNotNull(signed(1,2,NmeaFieldSemantic.APPARENT_WIND_ANGLE,"deg"),number(3,NmeaFieldSemantic.APPARENT_WIND_SPEED,"kn"))
            "VWT"->listOfNotNull(signed(1,2,NmeaFieldSemantic.TRUE_WIND_ANGLE,"deg"),number(3,NmeaFieldSemantic.TRUE_WIND_SPEED,"kn"))
            "ROT"->if(fields.getOrNull(2)?.uppercase(Locale.US)=="A")listOfNotNull(number(1,NmeaFieldSemantic.ROT,"deg/min"))else emptyList()
            "RSA"->if(fields.getOrNull(2)?.uppercase(Locale.US)=="A")listOfNotNull(number(1,NmeaFieldSemantic.RUDDER_ANGLE,"deg"))else emptyList()
            "MTW"->listOfNotNull(number(1,NmeaFieldSemantic.WATER_TEMPERATURE,"C"))
            "MTA"->listOfNotNull(number(1,NmeaFieldSemantic.AIR_TEMPERATURE,"C"))
            "MDA"->listOfNotNull(
                number(3,NmeaFieldSemantic.AIR_PRESSURE,"hPa"){it*1_000.0},
                number(5,NmeaFieldSemantic.AIR_TEMPERATURE,"C"),
                number(13,NmeaFieldSemantic.TRUE_WIND_DIRECTION,"degT"),
                number(17,NmeaFieldSemantic.TRUE_WIND_SPEED,"kn"),
            )
            "VDR"->listOfNotNull(number(1,NmeaFieldSemantic.CURRENT_SET_TRUE,"degT"),number(5,NmeaFieldSemantic.CURRENT_DRIFT,"kn"))
            "RMB"->if(fields.getOrNull(1)?.uppercase(Locale.US)=="A")listOfNotNull(signed(2,3,NmeaFieldSemantic.CROSS_TRACK_ERROR,"NM"),text(5,NmeaFieldSemantic.DESTINATION_WAYPOINT),number(10,NmeaFieldSemantic.DISTANCE_TO_WAYPOINT,"NM"),number(11,NmeaFieldSemantic.BEARING_TO_WAYPOINT,"degT"))else emptyList()
            "BWC","BWR"->listOfNotNull(number(6,NmeaFieldSemantic.BEARING_TO_WAYPOINT,"degT"),number(10,NmeaFieldSemantic.DISTANCE_TO_WAYPOINT,"NM"),text(12,NmeaFieldSemantic.DESTINATION_WAYPOINT))
            "BOD"->listOfNotNull(number(1,NmeaFieldSemantic.BEARING_TO_WAYPOINT,"degT"),text(5,NmeaFieldSemantic.DESTINATION_WAYPOINT))
            "XTE"->if(fields.getOrNull(2)?.uppercase(Locale.US)=="A")listOfNotNull(signed(3,4,NmeaFieldSemantic.CROSS_TRACK_ERROR,"NM"))else emptyList()
            "APB"->if(fields.getOrNull(2)?.uppercase(Locale.US)=="A")listOfNotNull(signed(3,4,NmeaFieldSemantic.CROSS_TRACK_ERROR,"NM"),number(8,NmeaFieldSemantic.BEARING_TO_WAYPOINT,"degT"),text(10,NmeaFieldSemantic.DESTINATION_WAYPOINT))else emptyList()
            "XDR"->decodeTransducers(talker,type,fields,raw,elapsed)
            else->emptyList()
        }
        val knownIndexes=known.map{it.key.fieldIndex}.toSet()
        val rawFields=fields.drop(1).mapIndexedNotNull{offset,value->
            val index=offset+1
            value.trim().takeIf{it.isNotEmpty()&&index !in knownIndexes}?.let{text->NmeaFieldObservation(NmeaFieldKey(talker,type,index),value=text.toDoubleOrNull(),text=text.takeIf{text.toDoubleOrNull()==null},receivedElapsedRealtime=elapsed,rawSentence=raw)}
        }
        return (known+rawFields).distinctBy{it.key.stableId}
    }

    private fun decodeTransducers(talker:String,type:String,fields:List<String>,raw:String,elapsed:Long):List<NmeaFieldObservation>{
        val result=mutableListOf<NmeaFieldObservation>();var index=1
        while(index+3<fields.size){
            val value=fields[index+1].toDoubleOrNull();val unit=fields[index+2].trim();val name=fields[index+3].trim().takeIf{it.isNotEmpty()}
            if(value!=null){
                val semantic=when{
                    fields[index].equals("C",true)&&unit.equals("C",true)&&name?.contains("WATER",true)==true->NmeaFieldSemantic.WATER_TEMPERATURE
                    fields[index].equals("C",true)&&unit.equals("C",true)->NmeaFieldSemantic.AIR_TEMPERATURE
                    fields[index].equals("A",true)->when(name?.trim()?.uppercase(Locale.US)){
                        "PHONE_HEEL","ROLL","HEEL"->NmeaFieldSemantic.HEEL
                        "PHONE_PITCH","PITCH"->NmeaFieldSemantic.PITCH
                        "RUDDER","RUDDER_ANGLE"->NmeaFieldSemantic.RUDDER_ANGLE
                        else->NmeaFieldSemantic.RAW_ANGULAR
                    }
                    else->NmeaFieldSemantic.RAW
                }
                result+=NmeaFieldObservation(NmeaFieldKey(talker,type,index+1,semantic,name),value=value,unit=unit,receivedElapsedRealtime=elapsed,rawSentence=raw)
            }
            index+=4
        }
        return result
    }
}

@Singleton
class NmeaFieldRepository @Inject constructor(navigation:NavigationRepository){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val retention=NmeaFieldRetentionBuffer(DISCOVERY_RETENTION_MILLIS)
    private val _fields=MutableStateFlow<List<NmeaFieldObservation>>(emptyList());val fields=_fields.asStateFlow()
    init{
        scope.launch{navigation.transportDiagnostics.map{it.connectionGeneration}.distinctUntilChanged().drop(1).collect{retention.clear();_fields.value=emptyList()}}
        scope.launch{navigation.validRawSentences.collect{line->accept(line)}}
    }
    fun accept(line:String,elapsed:Long=SystemClock.elapsedRealtime()){
        val decoded=NmeaFieldDecoder.decode(line,elapsed)
        val heartbeat=NmeaFieldDecoder.heartbeat(line)
        _fields.value=retention.accept(decoded,heartbeat,line,elapsed)
    }
    fun semantic(value:NmeaFieldSemantic):NmeaFieldObservation?=fields.value.filter{it.key.semantic==value}.maxByOrNull{it.receivedElapsedRealtime}
    companion object{const val DISCOVERY_RETENTION_MILLIS=30_000L}
}
