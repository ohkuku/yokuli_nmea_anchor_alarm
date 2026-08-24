package com.yokuli.anchorwatch
import com.yokuli.anchorwatch.data.nmea.*
import com.yokuli.anchorwatch.domain.sonar.DepthReference
import org.junit.Assert.*
import org.junit.Test
class NmeaParserTest{private val p=Nmea0183Parser()
 @Test fun checksum(){val line=NmeaChecksum.append("GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W");assertTrue(NmeaChecksum.validate(line));assertFalse(NmeaChecksum.validate(line.dropLast(2)+"00"))}
 @Test fun rmcValidAndInvalid(){val a=p.parse("\$GPRMC,123519,A,4807.038,N,01131.000,E,22.4,84.4,230394,,,A",false)!!;assertTrue(a.position!!.valid);assertEquals(48.1173,a.position.latitude,1e-5);val v=p.parse("\$GNRMC,123519,V,4807.038,N,01131.000,E,3.2,91.0,230394,,,N",false)!!;assertFalse(v.position!!.valid);assertNull(v.sog);assertNull(v.cog)}
 @Test fun rmcPreservesFractionalSeconds(){val first=p.parse("\$GPRMC,123519.10,A,4807.038,N,01131.000,E,0,0,230394,,,A",false)!!.utcMillis!!;val second=p.parse("\$GPRMC,123519.35,A,4807.038,N,01131.000,E,0,0,230394,,,A",false)!!.utcMillis!!;assertEquals(250L,second-first)}
 @Test fun ggaAndGll(){val g=p.parse("\$GNGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,",false)!!;assertEquals(8,g.position!!.satellites);assertEquals(545.4,g.position.altitudeMeters!!,.01);assertTrue(p.parse("\$GPGLL,4916.45,N,12311.12,W,225444,A",false)!!.position!!.valid)}
 @Test fun auxiliary(){assertEquals(12.3,p.parse("\$GPVTG,84.4,T,,M,12.3,N,22.8,K",false)!!.sog!!,.01);assertEquals(123.4,p.parse("\$IIHDT,123.4,T",false)!!.trueHeading!!,.01);assertEquals(5.2,p.parse("\$IIDPT,5.2,0",false)!!.depth!!,.01);assertEquals(5.0,p.parse("\$IIDBT,16.4,f,5.0,M,2.7,F",false)!!.depth!!,.01)}
 @Test fun vhwProvidesPhysicalHeadingAndSpeedThroughWater(){val value=p.parse("\$IIVHW,123.4,T,120.0,M,5.6,N,10.4,K",false)!!;assertEquals(123.4,value.trueHeading!!,.01);assertEquals(120.0,value.magneticHeading!!,.01);assertEquals(5.6,value.speedThroughWaterKnots!!,.01)}
 @Test fun recognizedSentencesWithMissingFieldsAreNoUpdateNotInvalid(){
  assertNull(p.parse("\$IIDBT,,f,,M,,F",false)!!.depth)
  assertNull(p.parse("\$IIDPT,,",false)!!.depth)
  val rmc=p.parse("\$GPRMC,123519,A,,,,,5.2,84.4,230394,,,A",false)!!
  assertNull(rmc.position);assertEquals(5.2,rmc.sog!!,.01);assertEquals(84.4,rmc.cog!!,.01)
  assertNull(p.parse("\$IIMWV,30.0,R,12.0,N,V",false)!!.apparentWindSpeedKnots)
 }
 @Test fun missingPositionValidityIsNoUpdateButExplicitInvalidStillInvalid(){
  val missingStatus=p.parse("\$GPRMC,123519,,4807.038,N,01131.000,E,5.2,84.4,230394,,,",false)!!
  assertNull(missingStatus.position);assertNull(missingStatus.sog);assertNull(missingStatus.cog)
  assertNull(p.parse("\$GNGGA,123519,4807.038,N,01131.000,E,,08,0.9,545.4,M,46.9,M,,",false)!!.position)
  assertNull(p.parse("\$GPGLL,4916.45,N,12311.12,W,225444,",false)!!.position)
  assertFalse(p.parse("\$GNGGA,123519,4807.038,N,01131.000,E,0,08,0.9,545.4,M,46.9,M,,",false)!!.position!!.valid)
  assertFalse(p.parse("\$GPGLL,4916.45,N,12311.12,W,225444,V",false)!!.position!!.valid)
 }
 @Test fun windSentencesKeepTrueAndApparentValuesSeparate(){val direction=p.parse("\$IIMWD,214.8,T,201.3,M,12.4,N,6.4,M",false)!!;assertEquals(214.8,direction.trueWindDirection!!,.01);assertEquals(12.4,direction.trueWindSpeedKnots!!,.01);val apparent=p.parse("\$IIMWV,32.0,R,6.0,M,A",false)!!;assertEquals(32.0,apparent.apparentWindAngle!!,.01);assertEquals(11.66,apparent.apparentWindSpeedKnots!!,.05);assertNull(apparent.trueWindSpeedKnots);val trueWind=p.parse("\$IIMWV,30.0,T,11.8,N,A",false)!!;assertEquals(30.0,trueWind.trueWindAngle!!,.01);assertEquals(11.8,trueWind.trueWindSpeedKnots!!,.01);assertNull(trueWind.apparentWindAngle)}
 @Test fun legacyWindAndWeatherSentencesFeedLiveWindWithoutInvalidatingFieldBusTraffic(){
  val apparent=p.parse("\$IIVWR,37.0,L,8.4,N,4.3,M,15.6,K",false)!!
  assertEquals(-37.0,apparent.apparentWindAngle!!,.01);assertEquals(8.4,apparent.apparentWindSpeedKnots!!,.01)
  val trueWind=p.parse("\$IIVWT,42.0,R,10.1,N,5.2,M,18.7,K",false)!!
  assertEquals(42.0,trueWind.trueWindAngle!!,.01);assertEquals(10.1,trueWind.trueWindSpeedKnots!!,.01)
  val weather=p.parse("\$WIMDA,29.920,I,1.013,B,18.2,C,16.8,C,82.0,70.0,12.0,C,225.0,T,220.0,M,14.0,N,7.2,M",false)!!
  assertEquals(225.0,weather.trueWindDirection!!,.01);assertEquals(14.0,weather.trueWindSpeedKnots!!,.01)
  assertEquals("ROT",p.parse("\$IIROT,2.3,A",false)!!.type)
  assertEquals("XDR",p.parse("\$IIXDR,A,3.1,D,HEEL",false)!!.type)
 }
 @Test fun blankFieldsRefreshTheSamePhysicalSourceWithoutErasingItsValue(){
  val retainer=NmeaUpdateRetainer()
  val depth=retainer.accept(p.parse("\$IIDBT,16.4,f,5.0,M,2.7,F",false,100)!!,100,"\$IIDBT,16.4,f,5.0,M,2.7,F")
  val heldDepth=retainer.accept(p.parse("\$IIDBT,,f,,M,,F",false,250)!!,250,"\$IIDBT,,f,,M,,F")
  assertEquals(5.0,depth.depth!!,.001);assertEquals(5.0,heldDepth.depth!!,.001)
  assertEquals(250,heldDepth.depthObservation!!.receivedElapsedRealtime)

  val heading=retainer.accept(p.parse("\$IIHDT,123.4,T",false,300)!!,300,"\$IIHDT,123.4,T")
  val heldHeading=retainer.accept(p.parse("\$IIHDT,,T",false,400)!!,400,"\$IIHDT,,T")
  assertEquals(heading.trueHeading,heldHeading.trueHeading)

  retainer.accept(p.parse("\$IIHDG,100.0,,,10.0,E",false,500)!!,500,"\$IIHDG,100.0,,,10.0,E")
  val changedMagnetic=retainer.accept(p.parse("\$IIHDG,110.0,,,,",false,600)!!,600,"\$IIHDG,110.0,,,,")
  assertEquals(10.0,changedMagnetic.magneticVariationDegrees!!,.001);assertEquals(120.0,changedMagnetic.trueHeading!!,.001)

  retainer.accept(p.parse("\$IIDPT,5.0,-1.2",false,700)!!,700,"\$IIDPT,5.0,-1.2")
  val changedDepth=retainer.accept(p.parse("\$IIDPT,6.0,",false,800)!!,800,"\$IIDPT,6.0,")
  assertEquals(-1.2,changedDepth.depthObservation!!.offsetMeters!!,.001);assertEquals(DepthReference.BELOW_KEEL,changedDepth.depthObservation!!.reference)
 }
 @Test fun retentionNeverCrossesTalkersAndExplicitInvalidityClearsTheSource(){
  val retainer=NmeaUpdateRetainer()
  retainer.accept(p.parse("\$IIHDT,123.4,T",false,100)!!,100,"\$IIHDT,123.4,T")
  assertNull(retainer.accept(p.parse("\$HCHDT,,T",false,200)!!,200,"\$HCHDT,,T").trueHeading)
  retainer.accept(p.parse("\$IIMWV,30.0,R,12.0,N,A",false,300)!!,300,"\$IIMWV,30.0,R,12.0,N,A")
  val invalid=retainer.accept(p.parse("\$IIMWV,30.0,R,12.0,N,V",false,400)!!,400,"\$IIMWV,30.0,R,12.0,N,V")
  assertFalse(invalid.holdAllowed);assertNull(invalid.apparentWindSpeedKnots)
  assertNull(retainer.accept(p.parse("\$IIMWV,,R,,N,A",false,500)!!,500,"\$IIMWV,,R,,N,A").apparentWindSpeedKnots)
 }
 @Test fun streamSplitsAndBatches(){val s=NmeaStreamSplitter();assertTrue(s.feed("\$GPR").isEmpty());assertEquals("\$GPRMC,A",s.feed("MC,A\r\n").single());assertEquals(3,s.feed("\$A\r\n\$B\n\$C\r\n").size)}
}
