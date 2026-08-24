package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.nmea.NmeaChecksum
import com.yokuli.anchorwatch.data.nmea.NmeaFieldDecoder
import com.yokuli.anchorwatch.data.nmea.NmeaFieldSemantic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaFieldDecoderTest {
    @Test fun decodesNavigationWeatherAndSailingSentences(){
        fun value(body:String,semantic:NmeaFieldSemantic)=NmeaFieldDecoder.decode(NmeaChecksum.append(body),123).first{it.key.semantic==semantic}.value
        assertEquals(-18.0,value("IIVWR,18.0,L,12.3,N,6.3,M,22.8,K",NmeaFieldSemantic.APPARENT_WIND_ANGLE)!!,.001)
        assertEquals(1013.0,value("WIMDA,29.91,I,1.013,B,18.2,C,,,,,,,245.0,T,,M,14.0,N,7.2,M",NmeaFieldSemantic.AIR_PRESSURE)!!,.001)
        assertEquals(2.4,value("IIVDR,123.0,T,121.0,M,2.4,N",NmeaFieldSemantic.CURRENT_DRIFT)!!,.001)
        assertEquals(4.2,value("IIVLW,1300.5,N,4.2,N",NmeaFieldSemantic.TRIP_LOG)!!,.001)
    }

    @Test fun invalidStatusAndEmptyFieldsDoNotPublishFalseValues(){
        val rot=NmeaFieldDecoder.decode(NmeaChecksum.append("IIROT,,V"),123)
        assertTrue(rot.none{it.key.semantic==NmeaFieldSemantic.ROT})
        val rmb=NmeaFieldDecoder.decode(NmeaChecksum.append("GPRMB,V,,,,,,,,,,,,,"),123)
        assertTrue(rmb.none{it.key.semantic!=NmeaFieldSemantic.RAW})
    }

    @Test fun xdrKeepsStableTransducerIdentity(){
        val result=NmeaFieldDecoder.decode(NmeaChecksum.append("IIXDR,A,12.4,D,RUDDER,A,-3.2,D,PHONE_HEEL,A,1.7,D,PHONE_PITCH,A,99.0,D,OTHER,C,19.3,C,WATER"),123)
        assertTrue(result.any{it.key.transducerName=="RUDDER"&&it.key.semantic==NmeaFieldSemantic.RUDDER_ANGLE})
        assertTrue(result.any{it.key.transducerName=="PHONE_HEEL"&&it.key.semantic==NmeaFieldSemantic.HEEL})
        assertTrue(result.any{it.key.transducerName=="PHONE_PITCH"&&it.key.semantic==NmeaFieldSemantic.PITCH})
        assertTrue(result.any{it.key.transducerName=="OTHER"&&it.key.semantic==NmeaFieldSemantic.RAW_ANGULAR})
        assertTrue(result.any{it.key.transducerName=="WATER"&&it.key.semantic==NmeaFieldSemantic.WATER_TEMPERATURE})
    }
}
