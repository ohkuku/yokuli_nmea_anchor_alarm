package com.yokuli.anchorwatch
import com.yokuli.anchorwatch.data.nmea.*
import org.junit.Assert.*
import org.junit.Test
class NmeaParserTest{private val p=Nmea0183Parser()
 @Test fun checksum(){val line=NmeaChecksum.append("GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W");assertTrue(NmeaChecksum.validate(line));assertFalse(NmeaChecksum.validate(line.dropLast(2)+"00"))}
 @Test fun rmcValidAndInvalid(){val a=p.parse("\$GPRMC,123519,A,4807.038,N,01131.000,E,22.4,84.4,230394,,,A",false)!!;assertTrue(a.position!!.valid);assertEquals(48.1173,a.position.latitude,1e-5);val v=p.parse("\$GNRMC,123519,V,4807.038,N,01131.000,E,0,0,230394,,,N",false)!!;assertFalse(v.position!!.valid)}
 @Test fun rmcPreservesFractionalSeconds(){val first=p.parse("\$GPRMC,123519.10,A,4807.038,N,01131.000,E,0,0,230394,,,A",false)!!.utcMillis!!;val second=p.parse("\$GPRMC,123519.35,A,4807.038,N,01131.000,E,0,0,230394,,,A",false)!!.utcMillis!!;assertEquals(250L,second-first)}
 @Test fun ggaAndGll(){val g=p.parse("\$GNGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,",false)!!;assertEquals(8,g.position!!.satellites);assertEquals(545.4,g.position.altitudeMeters!!,.01);assertTrue(p.parse("\$GPGLL,4916.45,N,12311.12,W,225444,A",false)!!.position!!.valid)}
 @Test fun auxiliary(){assertEquals(12.3,p.parse("\$GPVTG,84.4,T,,M,12.3,N,22.8,K",false)!!.sog!!,.01);assertEquals(123.4,p.parse("\$IIHDT,123.4,T",false)!!.trueHeading!!,.01);assertEquals(5.2,p.parse("\$IIDPT,5.2,0",false)!!.depth!!,.01);assertEquals(5.0,p.parse("\$IIDBT,16.4,f,5.0,M,2.7,F",false)!!.depth!!,.01)}
 @Test fun windSentencesKeepTrueAndApparentValuesSeparate(){val direction=p.parse("\$IIMWD,214.8,T,201.3,M,12.4,N,6.4,M",false)!!;assertEquals(214.8,direction.trueWindDirection!!,.01);assertEquals(12.4,direction.trueWindSpeedKnots!!,.01);val apparent=p.parse("\$IIMWV,32.0,R,6.0,M,A",false)!!;assertEquals(32.0,apparent.apparentWindAngle!!,.01);assertEquals(11.66,apparent.apparentWindSpeedKnots!!,.05);assertNull(apparent.trueWindSpeedKnots);val trueWind=p.parse("\$IIMWV,30.0,T,11.8,N,A",false)!!;assertEquals(30.0,trueWind.trueWindAngle!!,.01);assertEquals(11.8,trueWind.trueWindSpeedKnots!!,.01);assertNull(trueWind.apparentWindAngle)}
 @Test fun streamSplitsAndBatches(){val s=NmeaStreamSplitter();assertTrue(s.feed("\$GPR").isEmpty());assertEquals("\$GPRMC,A",s.feed("MC,A\r\n").single());assertEquals(3,s.feed("\$A\r\n\$B\n\$C\r\n").size)}
}
