package com.yokuli.anchorwatch.data.nmea

import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.sonar.DepthObservation
import com.yokuli.anchorwatch.domain.sonar.DepthReference
import com.yokuli.anchorwatch.domain.sonar.DepthSentenceType
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

data class NmeaUpdate(val position:NavigationFix?=null,val sog:Double?=null,val cog:Double?=null,val trueHeading:Double?=null,val magneticHeading:Double?=null,val depth:Double?=null,val depthObservation:DepthObservation?=null,val speedThroughWaterKnots:Double?=null,val hdop:Double?=null,val fixQuality:Int?=null,val satellites:Int?=null,val trueWindDirection:Double?=null,val windSpeedKnots:Double?=null,val apparentWindAngle:Double?=null,val trueWindAngle:Double?=null,val trueWindSpeedKnots:Double?=null,val apparentWindSpeedKnots:Double?=null,val utcMillis:Long?=null,val type:String)

class Nmea0183Parser {
 fun parse(line:String,requireChecksum:Boolean=true,elapsed:Long=System.nanoTime()/1_000_000):NmeaUpdate? {
  if(!NmeaChecksum.validate(line,requireChecksum)) return null
  val body=line.substring(1).substringBefore('*'); val f=body.split(','); if(f[0].length<3)return null
  val type=f[0].takeLast(3)
  return when(type){"RMC"->rmc(f,line,elapsed);"GGA"->gga(f,line,elapsed);"GLL"->gll(f,line,elapsed);"VTG"->NmeaUpdate(sog=f.getOrNull(5).d(),cog=f.getOrNull(1).d(),type="VTG");"VHW"->NmeaUpdate(trueHeading=f.getOrNull(1).d(),magneticHeading=f.getOrNull(3).d(),speedThroughWaterKnots=f.getOrNull(5).d()?:f.getOrNull(7).d()?.times(.539957),type="VHW");"HDT"->NmeaUpdate(trueHeading=f.getOrNull(1).d(),type="HDT");"HDM"->NmeaUpdate(magneticHeading=f.getOrNull(1).d(),type="HDM");"HDG"->hdg(f);"DPT"->depthDpt(f,line,elapsed);"DBT"->depthDbt(f,line,elapsed);"MWD"->mwd(f);"MWV"->mwv(f);"VWR"->relativeWind(f,false);"VWT"->relativeWind(f,true);"MDA"->mda(f);"ZDA"->zda(f);in FIELD_BUS_TYPES->NmeaUpdate(type=type);else->null}
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
  return NmeaUpdate(position,sog,cog,utcMillis=utc,type="RMC")
 }
 private fun gga(f:List<String>,raw:String,e:Long):NmeaUpdate { val p=position(f,2,3,4,5);val q=f.getOrNull(6)?.toIntOrNull();val hdop=f.getOrNull(8).d();val satellites=f.getOrNull(7)?.toIntOrNull();val position=if(q==null)null else p?.let{NavigationFix(it.first,it.second,null,e,hdop=hdop,fixQuality=q,satellites=satellites,altitudeMeters=f.getOrNull(9).d(),sourceSentence=raw,valid=q>0)};return NmeaUpdate(position=position,hdop=hdop,fixQuality=q,satellites=satellites,type="GGA") }
 private fun gll(f:List<String>,raw:String,e:Long):NmeaUpdate { val p=position(f,1,2,3,4);val status=validity(f.getOrNull(6),"A","V");return NmeaUpdate(if(status==null)null else p?.let{NavigationFix(it.first,it.second,null,e,sourceSentence=raw,valid=status)},type="GLL") }
 private fun hdg(f:List<String>):NmeaUpdate { val mag=f.getOrNull(1).d(); val variation=f.getOrNull(4).d()?.let{if(f.getOrNull(5)=="W")-it else it}; return NmeaUpdate(trueHeading=if(mag!=null&&variation!=null)(mag+variation+360)%360 else null,magneticHeading=mag,type="HDG") }
 private fun mwd(f:List<String>):NmeaUpdate { val knots=f.getOrNull(5).d()?:f.getOrNull(7).d()?.times(1.943844);return NmeaUpdate(trueWindDirection=f.getOrNull(1).d(),windSpeedKnots=knots,trueWindSpeedKnots=knots,type="MWD") }
 private fun mwv(f:List<String>):NmeaUpdate {
  if(f.getOrNull(5)!="A")return NmeaUpdate(type="MWV")
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

data class NmeaDiagnostics(val bytes:Long=0,val validSentences:Long=0,val invalidSentences:Long=0,val checksumErrors:Long=0,val lastPacketElapsed:Long?=null,val lastFixElapsed:Long?=null,val lastByType:Map<String,String> = emptyMap(),val raw:List<String> = emptyList())
