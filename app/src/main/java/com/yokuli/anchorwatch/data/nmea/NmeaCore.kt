package com.yokuli.anchorwatch.data.nmea

import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.sonar.DepthObservation
import com.yokuli.anchorwatch.domain.sonar.DepthReference
import com.yokuli.anchorwatch.domain.sonar.DepthSentenceType
import com.yokuli.anchorwatch.data.nmea.input.ParsedNmeaEnvelope
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

object NmeaChecksum {
 fun validate(line:String, required:Boolean=true):Boolean {
  val s=line.trim(); if(!s.startsWith("$")) return false
  val star=s.indexOf('*'); if(star<0) return !required
  if(star+2>=s.length) return false
  var value=0; for(i in 1 until star) value=value xor s[i].code
  return value == s.substring(star+1, star+3).toIntOrNull(16)
 }
 fun append(body:String):String { var v=0; body.forEach{v=v xor it.code}; return "$$body*%02X".format(v) }
}

class NmeaStreamSplitter(private val maxLength:Int=1024) {
 private val buffer=StringBuilder()
 fun feed(bytes:ByteArray,count:Int=bytes.size):List<String> = feed(String(bytes,0,count,Charsets.US_ASCII))
 fun feed(chunk:String):List<String> {
  buffer.append(chunk); val result=mutableListOf<String>()
  while(true){ val nl=buffer.indexOf("\n"); if(nl<0) break; val line=buffer.substring(0,nl).trimEnd('\r'); buffer.delete(0,nl+1); if(line.startsWith("$")&&line.length<=maxLength) result+=line }
  if(buffer.length>maxLength){ val start=buffer.lastIndexOf("$"); val tail=if(start>=0) buffer.substring(start) else ""; buffer.clear().append(tail) }
  return result
 }
}

enum class NmeaMeasurementConfirmation { NUMERIC_MEASUREMENT, UNCHANGED_HEARTBEAT }
enum class NmeaMetric {
 POSITION,SOG,COG,TRUE_HEADING,MAGNETIC_HEADING,MAGNETIC_VARIATION,DEPTH,SPEED_THROUGH_WATER,
 HDOP,FIX_QUALITY,SATELLITES,TRUE_WIND_DIRECTION,APPARENT_WIND_ANGLE,TRUE_WIND_ANGLE,
 TRUE_WIND_SPEED,APPARENT_WIND_SPEED,
}
data class NmeaMetricTiming(
 val measuredElapsedRealtime:Long,
 val sourceHeartbeatElapsedRealtime:Long,
 val confirmation:NmeaMeasurementConfirmation,
)

data class NmeaUpdate(val position:NavigationFix?=null,val sog:Double?=null,val cog:Double?=null,val trueHeading:Double?=null,val magneticHeading:Double?=null,val magneticVariationDegrees:Double?=null,val depth:Double?=null,val depthObservation:DepthObservation?=null,val speedThroughWaterKnots:Double?=null,val hdop:Double?=null,val fixQuality:Int?=null,val satellites:Int?=null,val trueWindDirection:Double?=null,val windSpeedKnots:Double?=null,val apparentWindAngle:Double?=null,val trueWindAngle:Double?=null,val trueWindSpeedKnots:Double?=null,val apparentWindSpeedKnots:Double?=null,val utcMillis:Long?=null,val type:String,val sentenceId:String="",val holdAllowed:Boolean=true,val metricTimings:Map<NmeaMetric,NmeaMetricTiming> = emptyMap()){
 fun measuredAt(metric:NmeaMetric):Long?=metricTimings[metric]?.measuredElapsedRealtime
 fun heartbeatAt(metric:NmeaMetric):Long?=metricTimings[metric]?.sourceHeartbeatElapsedRealtime
 fun confirmation(metric:NmeaMetric):NmeaMeasurementConfirmation?=metricTimings[metric]?.confirmation
 fun isNumeric(metric:NmeaMetric)=confirmation(metric)==NmeaMeasurementConfirmation.NUMERIC_MEASUREMENT
}

/**
 * Some marine gateways send a complete value once and then repeat the same
 * sentence with blank fields while that value is unchanged. Retention is
 * deliberately scoped to one full sentence id and one live transport
 * generation. It therefore cannot borrow depth/heading/wind from another
 * instrument, endpoint or reconnect generation.
 *
 * Explicit negative validity (RMC/GLL/MWV V, GGA quality 0, GNS mode N) clears that source;
 * only an ordinary blank field is treated as "unchanged".
 */
class NmeaUpdateRetainer {
 private val held=linkedMapOf<String,NmeaUpdate>()
 @Synchronized fun accept(update:NmeaUpdate,elapsed:Long,rawSentence:String):NmeaUpdate{
  val key=update.sentenceId.ifBlank{update.type}.uppercase()
 if(key.isBlank())return update
 if(!update.holdAllowed){held.remove(key);return update}
 val previous=held[key]
  // Position is never retained. A blank coordinate heartbeat is useful for
  // transport health, but is not a new geographic fix.
  val currentPosition=update.position
  val carriedDepth=(update.depthObservation?:previous?.depthObservation)?.let{observation->
   val previousDepth=previous?.depthObservation
   if(update.depthObservation!=null){
    val offset=observation.offsetMeters?:previousDepth?.offsetMeters
    observation.copy(offsetMeters=offset,reference=if(observation.offsetMeters==null&&previousDepth!=null)previousDepth.reference else observation.reference,receivedElapsedRealtime=elapsed,sourceSentence=rawSentence)
   }else observation
  }
  val variation=update.magneticVariationDegrees?:previous?.magneticVariationDegrees
  val magnetic=update.magneticHeading?:previous?.magneticHeading
  val resolvedTrueHeading=update.trueHeading?:if((update.magneticHeading!=null||update.magneticVariationDegrees!=null)&&magnetic!=null&&variation!=null)(magnetic+variation+360.0)%360.0 else previous?.trueHeading
  val mergedValues=update.copy(
   position=currentPosition,sog=update.sog?:previous?.sog,cog=update.cog?:previous?.cog,
   trueHeading=resolvedTrueHeading,magneticHeading=magnetic,magneticVariationDegrees=variation,
   depth=update.depth?:previous?.depth,depthObservation=carriedDepth,
   speedThroughWaterKnots=update.speedThroughWaterKnots?:previous?.speedThroughWaterKnots,
   hdop=update.hdop?:previous?.hdop,fixQuality=update.fixQuality?:previous?.fixQuality,satellites=update.satellites?:previous?.satellites,
   trueWindDirection=update.trueWindDirection?:previous?.trueWindDirection,windSpeedKnots=update.windSpeedKnots?:previous?.windSpeedKnots,
   apparentWindAngle=update.apparentWindAngle?:previous?.apparentWindAngle,trueWindAngle=update.trueWindAngle?:previous?.trueWindAngle,
   trueWindSpeedKnots=update.trueWindSpeedKnots?:previous?.trueWindSpeedKnots,apparentWindSpeedKnots=update.apparentWindSpeedKnots?:previous?.apparentWindSpeedKnots,
   utcMillis=update.utcMillis?:previous?.utcMillis,
  )
  val numeric=buildSet{
   if(update.position!=null)add(NmeaMetric.POSITION);if(update.sog!=null)add(NmeaMetric.SOG);if(update.cog!=null)add(NmeaMetric.COG)
   if(update.trueHeading!=null)add(NmeaMetric.TRUE_HEADING);if(update.magneticHeading!=null)add(NmeaMetric.MAGNETIC_HEADING);if(update.magneticVariationDegrees!=null)add(NmeaMetric.MAGNETIC_VARIATION)
   // A new magnetic heading or variation produces a genuinely new computed
   // true heading even when its other operand was retained.
   if(resolvedTrueHeading!=null&&(update.trueHeading!=null||update.magneticHeading!=null||update.magneticVariationDegrees!=null))add(NmeaMetric.TRUE_HEADING)
   if(update.depth!=null)add(NmeaMetric.DEPTH);if(update.speedThroughWaterKnots!=null)add(NmeaMetric.SPEED_THROUGH_WATER)
   if(update.hdop!=null)add(NmeaMetric.HDOP);if(update.fixQuality!=null)add(NmeaMetric.FIX_QUALITY);if(update.satellites!=null)add(NmeaMetric.SATELLITES)
   if(update.trueWindDirection!=null)add(NmeaMetric.TRUE_WIND_DIRECTION);if(update.apparentWindAngle!=null)add(NmeaMetric.APPARENT_WIND_ANGLE);if(update.trueWindAngle!=null)add(NmeaMetric.TRUE_WIND_ANGLE)
   if(update.trueWindSpeedKnots!=null)add(NmeaMetric.TRUE_WIND_SPEED);if(update.apparentWindSpeedKnots!=null)add(NmeaMetric.APPARENT_WIND_SPEED)
  }
  fun timing(metric:NmeaMetric,valuePresent:Boolean):NmeaMetricTiming?=when{
   metric in numeric->NmeaMetricTiming(elapsed,elapsed,NmeaMeasurementConfirmation.NUMERIC_MEASUREMENT)
   valuePresent->previous?.metricTimings?.get(metric)?.copy(sourceHeartbeatElapsedRealtime=elapsed,confirmation=NmeaMeasurementConfirmation.UNCHANGED_HEARTBEAT)
   else->null
  }
  val timings=buildMap{
   timing(NmeaMetric.POSITION,mergedValues.position!=null)?.let{put(NmeaMetric.POSITION,it)}
   timing(NmeaMetric.SOG,mergedValues.sog!=null)?.let{put(NmeaMetric.SOG,it)};timing(NmeaMetric.COG,mergedValues.cog!=null)?.let{put(NmeaMetric.COG,it)}
   timing(NmeaMetric.TRUE_HEADING,mergedValues.trueHeading!=null)?.let{put(NmeaMetric.TRUE_HEADING,it)};timing(NmeaMetric.MAGNETIC_HEADING,mergedValues.magneticHeading!=null)?.let{put(NmeaMetric.MAGNETIC_HEADING,it)};timing(NmeaMetric.MAGNETIC_VARIATION,mergedValues.magneticVariationDegrees!=null)?.let{put(NmeaMetric.MAGNETIC_VARIATION,it)}
   timing(NmeaMetric.DEPTH,mergedValues.depth!=null)?.let{put(NmeaMetric.DEPTH,it)};timing(NmeaMetric.SPEED_THROUGH_WATER,mergedValues.speedThroughWaterKnots!=null)?.let{put(NmeaMetric.SPEED_THROUGH_WATER,it)}
   timing(NmeaMetric.HDOP,mergedValues.hdop!=null)?.let{put(NmeaMetric.HDOP,it)};timing(NmeaMetric.FIX_QUALITY,mergedValues.fixQuality!=null)?.let{put(NmeaMetric.FIX_QUALITY,it)};timing(NmeaMetric.SATELLITES,mergedValues.satellites!=null)?.let{put(NmeaMetric.SATELLITES,it)}
   timing(NmeaMetric.TRUE_WIND_DIRECTION,mergedValues.trueWindDirection!=null)?.let{put(NmeaMetric.TRUE_WIND_DIRECTION,it)};timing(NmeaMetric.APPARENT_WIND_ANGLE,mergedValues.apparentWindAngle!=null)?.let{put(NmeaMetric.APPARENT_WIND_ANGLE,it)};timing(NmeaMetric.TRUE_WIND_ANGLE,mergedValues.trueWindAngle!=null)?.let{put(NmeaMetric.TRUE_WIND_ANGLE,it)}
   timing(NmeaMetric.TRUE_WIND_SPEED,mergedValues.trueWindSpeedKnots!=null)?.let{put(NmeaMetric.TRUE_WIND_SPEED,it)};timing(NmeaMetric.APPARENT_WIND_SPEED,mergedValues.apparentWindSpeedKnots!=null)?.let{put(NmeaMetric.APPARENT_WIND_SPEED,it)}
  }
  val merged=mergedValues.copy(metricTimings=timings)
  held[key]=merged
  return merged
 }
 @Synchronized fun clear(){held.clear()}
}

class Nmea0183Parser {
 fun parseEnvelope(line:String,requireChecksum:Boolean=true,elapsed:Long=System.nanoTime()/1_000_000):ParsedNmeaEnvelope?{
  val update=parse(line,requireChecksum,elapsed)?:return null
  val full=update.sentenceId.uppercase();val type=update.type.uppercase();val talker=full.removeSuffix(type).takeIf{it.isNotBlank()}
  return ParsedNmeaEnvelope(line.trim(),talker.orEmpty(),type,full.ifBlank{type},elapsed,update)
 }
 fun parse(line:String,requireChecksum:Boolean=true,elapsed:Long=System.nanoTime()/1_000_000):NmeaUpdate? {
  if(!NmeaChecksum.validate(line,requireChecksum)) return null
  val body=line.substring(1).substringBefore('*'); val f=body.split(','); if(f[0].length<3)return null
  val type=f[0].takeLast(3)
  val update=when(type){"RMC"->rmc(f,line,elapsed);"GGA"->gga(f,line,elapsed);"GNS"->gns(f,line,elapsed);"GLL"->gll(f,line,elapsed);"VTG"->NmeaUpdate(sog=f.getOrNull(5).d(),cog=f.getOrNull(1).d(),type="VTG");"VHW"->NmeaUpdate(trueHeading=f.getOrNull(1).d(),magneticHeading=f.getOrNull(3).d(),speedThroughWaterKnots=f.getOrNull(5).d()?:f.getOrNull(7).d()?.times(.539957),type="VHW");"HDT"->NmeaUpdate(trueHeading=f.getOrNull(1).d(),type="HDT");"HDM"->NmeaUpdate(magneticHeading=f.getOrNull(1).d(),type="HDM");"HDG"->hdg(f);"DPT"->depthDpt(f,line,elapsed);"DBT"->depthDbt(f,line,elapsed);"MWD"->mwd(f);"MWV"->mwv(f);"VWR"->relativeWind(f,false);"VWT"->relativeWind(f,true);"MDA"->mda(f);"ZDA"->zda(f);in FIELD_BUS_TYPES->NmeaUpdate(type=type);else->null}
  return update?.copy(sentenceId=f[0].uppercase())
 }
 private fun depthDpt(f:List<String>,raw:String,e:Long):NmeaUpdate {
  val depth=f.getOrNull(1).d()?:return NmeaUpdate(type="DPT");val offset=f.getOrNull(2).d()
  val reference=when{offset==null->DepthReference.BELOW_TRANSDUCER;offset>=0.0->DepthReference.BELOW_SURFACE;else->DepthReference.BELOW_KEEL}
  return NmeaUpdate(depth=depth,depthObservation=DepthObservation(depth,offset,reference,DepthSentenceType.DPT,e,raw),type="DPT")
 }
 private fun depthDbt(f:List<String>,raw:String,e:Long):NmeaUpdate {
  val depth=f.getOrNull(3).d()?:return NmeaUpdate(type="DBT")
  return NmeaUpdate(depth=depth,depthObservation=DepthObservation(depth,null,DepthReference.BELOW_TRANSDUCER,DepthSentenceType.DBT,e,raw),type="DBT")
 }
 private fun rmc(f:List<String>,raw:String,e:Long):NmeaUpdate {
  val p=position(f,3,4,5,6);val status=validity(f.getOrNull(2),"A","V");val parsedSog=f.getOrNull(7).d();val parsedCog=f.getOrNull(8).d();val utc=parseRmcTime(f.getOrNull(1),f.getOrNull(9))
  // A missing field is no update, while an explicit V status is negative
  // evidence. Neither may refresh the SOG/COG clocks used by downstream
  // course-trust gates. The last previously valid components remain held.
  val sog=parsedSog.takeIf{status==true};val cog=parsedCog.takeIf{status==true}
  val position=if(status==null)null else p?.let{NavigationFix(it.first,it.second,utc,e,sog,cog,sourceSentence=raw,valid=status)}
  return NmeaUpdate(position,sog,cog,utcMillis=utc,type="RMC",holdAllowed=status!=false)
 }
 private fun gga(f:List<String>,raw:String,e:Long):NmeaUpdate { val p=position(f,2,3,4,5);val q=f.getOrNull(6)?.toIntOrNull();val hdop=f.getOrNull(8).d();val satellites=f.getOrNull(7)?.toIntOrNull();val position=if(q==null)null else p?.let{NavigationFix(it.first,it.second,null,e,hdop=hdop,fixQuality=q,satellites=satellites,altitudeMeters=f.getOrNull(9).d(),sourceSentence=raw,valid=q>0)};return NmeaUpdate(position=position,hdop=hdop,fixQuality=q,satellites=satellites,type="GGA",holdAllowed=q!=0) }
 private fun gns(f:List<String>,raw:String,e:Long):NmeaUpdate {
  val p=position(f,2,3,4,5);val modes=f.getOrNull(6)?.trim()?.uppercase().orEmpty();val explicit=modes.isNotEmpty();val valid=explicit&&modes.any{it!='N'}
  val quality=when{!explicit->null;!valid->0;'R' in modes->4;'F' in modes->5;'D' in modes->2;else->1}
  val hdop=f.getOrNull(8).d();val satellites=f.getOrNull(7)?.toIntOrNull()
  val fix=if(!explicit)null else p?.let{NavigationFix(it.first,it.second,null,e,hdop=hdop,fixQuality=quality,satellites=satellites,altitudeMeters=f.getOrNull(9).d(),sourceSentence=raw,valid=valid)}
  return NmeaUpdate(position=fix,hdop=hdop,fixQuality=quality,satellites=satellites,type="GNS",holdAllowed=!explicit||valid)
 }
 private fun gll(f:List<String>,raw:String,e:Long):NmeaUpdate { val p=position(f,1,2,3,4);val status=validity(f.getOrNull(6),"A","V");return NmeaUpdate(if(status==null)null else p?.let{NavigationFix(it.first,it.second,null,e,sourceSentence=raw,valid=status)},type="GLL",holdAllowed=status!=false) }
 private fun hdg(f:List<String>):NmeaUpdate { val mag=f.getOrNull(1).d(); val variation=f.getOrNull(4).d()?.let{if(f.getOrNull(5).equals("W",true))-it else it}; return NmeaUpdate(trueHeading=if(mag!=null&&variation!=null)(mag+variation+360)%360 else null,magneticHeading=mag,magneticVariationDegrees=variation,type="HDG") }
 private fun mwd(f:List<String>):NmeaUpdate { val knots=f.getOrNull(5).d()?:f.getOrNull(7).d()?.times(1.943844);return NmeaUpdate(trueWindDirection=f.getOrNull(1).d(),windSpeedKnots=knots,trueWindSpeedKnots=knots,type="MWD") }
 private fun mwv(f:List<String>):NmeaUpdate {
  if(f.getOrNull(5)!="A")return NmeaUpdate(type="MWV",holdAllowed=!f.getOrNull(5).equals("V",true))
  val angle=f.getOrNull(1).d()?.let{(it%360.0+360.0)%360.0}
  val speed=f.getOrNull(3).d()?.let{when(f.getOrNull(4)){"M"->it*1.943844;"K"->it*.539957;else->it}}
  return when(f.getOrNull(2)){"R"->NmeaUpdate(windSpeedKnots=speed,apparentWindAngle=angle,apparentWindSpeedKnots=speed,type="MWV");"T"->NmeaUpdate(windSpeedKnots=speed,trueWindAngle=angle,trueWindSpeedKnots=speed,type="MWV");else->NmeaUpdate(type="MWV")}
 }
 private fun relativeWind(f:List<String>,trueWind:Boolean):NmeaUpdate {
  val angle=f.getOrNull(1).d()?.let{if(f.getOrNull(2).equals("L",true))-kotlin.math.abs(it) else kotlin.math.abs(it)}
  val speed=f.getOrNull(3).d()?:f.getOrNull(5).d()?.times(1.943844)?:f.getOrNull(7).d()?.times(.539957)
  return if(trueWind)NmeaUpdate(windSpeedKnots=speed,trueWindAngle=angle,trueWindSpeedKnots=speed,type="VWT")
  else NmeaUpdate(windSpeedKnots=speed,apparentWindAngle=angle,apparentWindSpeedKnots=speed,type="VWR")
 }
 private fun mda(f:List<String>):NmeaUpdate {
  val speed=f.getOrNull(17).d()?:f.getOrNull(19).d()?.times(1.943844)
  return NmeaUpdate(trueWindDirection=f.getOrNull(13).d(),windSpeedKnots=speed,trueWindSpeedKnots=speed,type="MDA")
 }
 private fun zda(f:List<String>):NmeaUpdate = try { val date=LocalDate.of(f[4].toInt(),f[3].toInt(),f[2].toInt()); val time=parseClock(f[1]); NmeaUpdate(utcMillis=date.atTime(time).toInstant(ZoneOffset.UTC).toEpochMilli(),type="ZDA") }catch(_:Exception){NmeaUpdate(type="ZDA")}
 private fun position(f:List<String>,li:Int,lh:Int,oi:Int,oh:Int):Pair<Double,Double>? { val lat=coord(f.getOrNull(li),f.getOrNull(lh))?:return null; val lon=coord(f.getOrNull(oi),f.getOrNull(oh))?:return null; if(lat !in -90.0..90.0||lon !in -180.0..180.0)return null; return lat to lon }
 private fun validity(value:String?,valid:String,invalid:String):Boolean?=when(value?.trim()?.uppercase()){valid->true;invalid->false;else->null}
 private fun coord(v:String?,hem:String?):Double? { val n=v?.toDoubleOrNull()?:return null; val deg=(n/100).toInt(); val x=deg+(n-deg*100)/60; return if(hem=="S"||hem=="W")-x else x }
 private fun parseRmcTime(t:String?,d:String?):Long? = try { val date=LocalDate.parse(d,DateTimeFormatter.ofPattern("ddMMyy")); date.atTime(parseClock(t!!)).toInstant(ZoneOffset.UTC).toEpochMilli() }catch(_:Exception){null}
 private fun parseClock(value:String):LocalTime { val seconds=value.substring(4).toDouble();val whole=seconds.toInt();val nanos=((seconds-whole)*1_000_000_000.0).toLong().coerceIn(0,999_999_999).toInt();return LocalTime.of(value.substring(0,2).toInt(),value.substring(2,4).toInt(),whole,nanos) }
 private fun String?.d()=this?.toDoubleOrNull()
 companion object { private val FIELD_BUS_TYPES=setOf("VLW","ROT","RSA","MTW","MTA","VDR","RMB","BWC","BWR","BOD","XTE","APB","XDR") }
}

data class NmeaDiagnostics(val bytes:Long=0,val validSentences:Long=0,val invalidSentences:Long=0,val checksumErrors:Long=0,val lastPacketElapsed:Long?=null,val lastFixElapsed:Long?=null,val lastByType:Map<String,String> = emptyMap(),val raw:List<String> = emptyList(),val echoedAppTxSentences:Long=0,val lastPositionRejectionReason:String?=null)
