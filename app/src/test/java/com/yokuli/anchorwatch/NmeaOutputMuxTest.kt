package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.nmea.NmeaChecksum
import com.yokuli.anchorwatch.data.sharing.NmeaOutputMux
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.PositionProvider
import com.yokuli.anchorwatch.domain.vessel.VesselAttitude
import com.yokuli.anchorwatch.domain.vessel.VesselMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaOutputMuxTest {
    private val mux=NmeaOutputMux()

    @Test fun systemSourceSuppressesEveryTalkerPositionButKeepsBoatInstruments(){
        listOf("GPRMC,1","GNGGA,1","IIGLL,1","ECVTG,1").forEach{body->assertNull(mux.boatSentence(NmeaChecksum.append(body),GpsDataSource.SYSTEM))}
        val depth=NmeaChecksum.append("IIDPT,12.3,0.0")
        val heading=NmeaChecksum.append("IIHDT,123.4,T")
        assertEquals("$depth\r\n",mux.boatSentence(depth,GpsDataSource.SYSTEM))
        assertEquals("$heading\r\n",mux.boatSentence(heading,GpsDataSource.SYSTEM))
    }

    @Test fun nmeaSourcePassesValidSentencesAndAddsMissingChecksum(){
        val output=mux.boatSentence("\$IIMWV,120.0,R,12.0,N,A",GpsDataSource.NMEA)!!
        assertTrue(output.endsWith("\r\n"));assertTrue(NmeaChecksum.validate(output,true))
    }

    @Test fun systemEncoderUsesGnTalkerChecksumAndNeverInventsHeading(){
        val fix=NavigationFix(-36.8485,174.7633,1_720_000_000_000,10_000,sogKnots=1.2,cogTrueDegrees=92.0,headingTrueDegrees=38.0,hdop=.8,fixQuality=1,satellites=12,altitudeMeters=4.0,horizontalAccuracyMeters=2.4,positionProvider=PositionProvider.ANDROID_GNSS,sourceSentence="SYSTEM",valid=true)
        val output=mux.acceptedPosition(fix,10_500)
        assertEquals(3,output.size);assertTrue(output.any{it.startsWith("\$GNRMC")});assertTrue(output.any{it.startsWith("\$GNGGA")});assertTrue(output.any{it.startsWith("\$GNVTG")});assertTrue(output.none{it.contains("HDT")});assertTrue(output.all{it.endsWith("\r\n")&&NmeaChecksum.validate(it,true)})
    }

    @Test fun systemEncoderLeavesUnknownSatellitesAndAltitudeBlankAndOmitsVtgWithoutMotion(){
        val fix=NavigationFix(0.0,0.0,1_720_000_000_000,10_000,horizontalAccuracyMeters=6.0,positionProvider=PositionProvider.ANDROID_GNSS,sourceSentence="SYSTEM",valid=true)
        val output=mux.acceptedPosition(fix,10_100)
        assertEquals(2,output.size)
        val gga=output.first{it.startsWith("\$GNGGA")}.substringBefore('*').split(',')
        assertEquals("",gga[7]);assertEquals("",gga[9]);assertTrue(output.none{it.startsWith("\$GNVTG")})
        assertTrue(output.all{NmeaChecksum.validate(it,true)})
    }


    @Test fun encoderLeavesHdopBlankWhenSourceReportsNoAccuracy(){
        val fix=NavigationFix(0.0,0.0,1_720_000_000_000,10_000,positionProvider=PositionProvider.NMEA,sourceSentence="RMC",valid=true)
        val gga=mux.acceptedPosition(fix,10_100).first{it.startsWith("\$GNGGA")}.substringBefore('*').split(',')
        assertEquals("",gga[8])
    }

    @Test fun systemEncoderFormatsSouthernEasternNorthernWesternAndDatelineCoordinates(){
        fun rmc(lat:Double,lon:Double)=mux.acceptedPosition(NavigationFix(lat,lon,1_720_000_000_000,10_000,sogKnots=1.0,cogTrueDegrees=90.0,horizontalAccuracyMeters=4.0,positionProvider=PositionProvider.ANDROID_GNSS,sourceSentence="SYSTEM",valid=true),10_100).first{it.startsWith("\$GNRMC")}
        assertTrue(rmc(-36.8485,174.7633).contains(",3650.91000,S,17445.79800,E,"))
        assertTrue(rmc(48.25,-123.5).contains(",4815.00000,N,12330.00000,W,"))
        assertTrue(rmc(0.0,0.0).contains(",0000.00000,N,00000.00000,E,"))
        assertTrue(rmc(-0.001,179.999).contains(",0000.06000,S,17959.94000,E,"))
        assertTrue(rmc(0.001,-179.999).contains(",0000.06000,N,17959.94000,W,"))
    }

    @Test fun systemMuxReplacesBoatPositionAndKeepsHeadingDepthAndWind(){
        val boat=listOf("GNRMC,1","GNGGA,1","GNVTG,1","GPGLL,1","IIHDT,120.0,T","IIDPT,8.0,0.0","IIMWV,90.0,R,12.0,N,A")
        val passthrough=boat.mapNotNull{mux.boatSentence(NmeaChecksum.append(it),GpsDataSource.SYSTEM)}
        assertEquals(3,passthrough.size);assertTrue(passthrough.any{it.contains("IIHDT")});assertTrue(passthrough.any{it.contains("IIDPT")});assertTrue(passthrough.any{it.contains("IIMWV")})
        val generated=mux.acceptedPosition(NavigationFix(-36.0,174.0,1_720_000_000_000,10_000,sogKnots=2.4,cogTrueDegrees=123.4,horizontalAccuracyMeters=4.0,positionProvider=PositionProvider.ANDROID_GNSS,sourceSentence="SYSTEM",valid=true),10_100)
        assertTrue((passthrough+generated).all{NmeaChecksum.validate(it,true)})
    }

    @Test fun staleNetworkAndMockSystemPositionsAreNotShared(){
        fun fix(provider:PositionProvider=PositionProvider.ANDROID_GNSS,mock:Boolean=false)=NavigationFix(1.0,2.0,receivedElapsedRealtime=1_000,horizontalAccuracyMeters=5.0,positionProvider=provider,isMockLocation=mock,sourceSentence="SYSTEM",valid=true)
        assertTrue(mux.acceptedPosition(fix(),5_000).isEmpty())
        assertTrue(mux.acceptedPosition(fix(PositionProvider.ANDROID_NETWORK),1_100).isEmpty())
        assertTrue(mux.acceptedPosition(fix(mock=true),1_100).isEmpty())
    }

    @Test fun phoneOutputAddsZdaAndEverySentenceHasAValidChecksum(){
        val fix=NavigationFix(-36.8485,174.7633,1_720_000_000_000,10_000,sogKnots=2.4,cogTrueDegrees=123.4,horizontalAccuracyMeters=4.0,positionProvider=PositionProvider.ANDROID_GNSS,sourceSentence="SYSTEM",valid=true)
        val output=mux.phonePosition(fix,10_100)
        assertEquals(listOf("RMC","GGA","VTG","ZDA"),output.mapNotNull(mux::sentenceType))
        assertTrue(output.all{it.endsWith("\r\n")&&NmeaChecksum.validate(it,true)})
        assertTrue(output.last().startsWith("\$GNZDA,094640.00,03,07,2024,00,00"))
    }

    @Test fun phoneOutputNeverReplaysAStaleFix(){
        val fix=NavigationFix(-36.8485,174.7633,receivedElapsedRealtime=1_000,horizontalAccuracyMeters=4.0,positionProvider=PositionProvider.ANDROID_GNSS,sourceSentence="SYSTEM",valid=true)
        assertTrue(mux.phonePosition(fix,4_001).isEmpty())
    }

    @Test fun phoneSensorOutputUsesStandardSentencesAndChecksums(){
        val attitude=VesselAttitude(12.3,-2.4,1.0,2.0,3.0)
        val output=listOfNotNull(mux.phoneHeading(123.4),mux.phoneRateOfTurn(180.0),mux.phoneXdr(attitude,1013.2),mux.phoneProprietary(attitude,VesselMotion(score=42.5),123.4,1013.2))
        assertEquals(listOf("HDT","ROT","XDR","YOK"),output.mapNotNull(mux::sentenceType))
        assertTrue(output.all{NmeaChecksum.validate(it,true)})
        assertTrue(output[2].contains("PHONE_HEEL")&&output[2].contains("PHONE_BARO"))
        assertTrue(output[3].contains(",42.5,"))
    }

    @Test fun defaultOutputDiagnosticCannotBeMistakenForNavigation(){
        val sentence=mux.diagnostic()
        assertEquals("YOK",mux.sentenceType(sentence));assertTrue(NmeaChecksum.validate(sentence,true))
        assertTrue(listOf("RMC","GGA","VTG","HDT","HDG").none{sentence.contains(it)})
    }

    @Test fun knownGoodHdgDiagnosticMatchesTheFiveSentenceContract(){
        val output=List(5){mux.diagnosticMagneticHeading()}
        assertEquals(5,output.size);assertTrue(output.all{mux.sentenceType(it)=="HDG"&&it.contains("IIHDG,123.40,,,,")&&NmeaChecksum.validate(it,true)})
    }
}
