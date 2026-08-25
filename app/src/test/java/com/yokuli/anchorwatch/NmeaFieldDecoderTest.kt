package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.nmea.NmeaChecksum
import com.yokuli.anchorwatch.data.nmea.NmeaFieldDecoder
import com.yokuli.anchorwatch.data.nmea.NmeaFieldSemantic
import com.yokuli.anchorwatch.data.nmea.NmeaFieldRetentionBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertFalse(NmeaFieldDecoder.heartbeat(NmeaChecksum.append("IIROT,,V"))!!.allowsHold)
        assertFalse(NmeaFieldDecoder.heartbeat(NmeaChecksum.append("IIMWV,30.0,R,12.0,N,V"))!!.allowsHold)
        assertTrue(NmeaFieldDecoder.heartbeat(NmeaChecksum.append("WIMDA,,I,,B,,,,,,,,,,,,,,,"))!!.allowsHold)
    }

    @Test fun xdrKeepsStableTransducerIdentity(){
        val result=NmeaFieldDecoder.decode(NmeaChecksum.append("IIXDR,A,12.4,D,RUDDER,A,-3.2,D,PHONE_HEEL,A,1.7,D,PHONE_PITCH,A,99.0,D,OTHER,C,19.3,C,WATER,P,1.01320,B,PHONE_BARO"),123)
        assertTrue(result.any{it.key.transducerName=="RUDDER"&&it.key.semantic==NmeaFieldSemantic.RUDDER_ANGLE})
        assertTrue(result.any{it.key.transducerName=="PHONE_HEEL"&&it.key.semantic==NmeaFieldSemantic.HEEL})
        assertTrue(result.any{it.key.transducerName=="PHONE_PITCH"&&it.key.semantic==NmeaFieldSemantic.PITCH})
        assertTrue(result.any{it.key.transducerName=="OTHER"&&it.key.semantic==NmeaFieldSemantic.RAW_ANGULAR})
        assertTrue(result.any{it.key.transducerName=="WATER"&&it.key.semantic==NmeaFieldSemantic.WATER_TEMPERATURE})
        assertEquals(1013.2,result.single{it.key.transducerName=="PHONE_BARO"&&it.key.semantic==NmeaFieldSemantic.AIR_PRESSURE}.value!!,.001)
    }

    @Test fun genericWeatherFieldsHoldOnlyWithinTheSameTalkerAndSentence(){
        val cache=NmeaFieldRetentionBuffer()
        fun accept(body:String,elapsed:Long)=NmeaChecksum.append(body).let{line->cache.accept(NmeaFieldDecoder.decode(line,elapsed),NmeaFieldDecoder.heartbeat(line),line,elapsed)}
        val first=accept("WIMDA,29.91,I,1.013,B,18.2,C,,,,,,,245.0,T,,M,14.0,N,7.2,M",100)
        assertEquals(1013.0,first.first{it.key.semantic==NmeaFieldSemantic.AIR_PRESSURE}.value!!,.001)
        val held=accept("WIMDA,,I,,B,18.2,C,,,,,,,245.0,T,,M,14.0,N,7.2,M",500)
        val pressure=held.first{it.key.semantic==NmeaFieldSemantic.AIR_PRESSURE}
        assertEquals(1013.0,pressure.value!!,.001);assertEquals(100,pressure.receivedElapsedRealtime)
        assertEquals(500,pressure.sourceHeartbeatElapsedRealtime)
        assertEquals(com.yokuli.anchorwatch.data.nmea.NmeaMeasurementConfirmation.UNCHANGED_HEARTBEAT,pressure.confirmation)
        val otherTalker=accept("IIMDA,,I,,B,,,,,,,,,,,,,,,",600)
        assertEquals(1,otherTalker.count{it.key.semantic==NmeaFieldSemantic.AIR_PRESSURE})
    }

    @Test fun blankMdaHeartbeatDoesNotCreatePressureMeasurementSamples(){
        val cache=NmeaFieldRetentionBuffer()
        fun accept(body:String,elapsed:Long)=NmeaChecksum.append(body).let{line->cache.accept(NmeaFieldDecoder.decode(line,elapsed),NmeaFieldDecoder.heartbeat(line),line,elapsed)}
        accept("WIMDA,29.91,I,1.013,B,,,,,,,,,,,,,,,",1_000)
        val held=accept("WIMDA,,I,,B,,,,,,,,,,,,,,,",61_000).single{it.key.semantic==NmeaFieldSemantic.AIR_PRESSURE}
        assertEquals(1_000,held.receivedElapsedRealtime)
        assertEquals(61_000,held.sourceHeartbeatElapsedRealtime)
        assertEquals(com.yokuli.anchorwatch.data.nmea.NmeaMeasurementConfirmation.UNCHANGED_HEARTBEAT,held.confirmation)
    }

    @Test fun explicitInvalidStatusClearsRetainedGenericFieldImmediately(){
        val cache=NmeaFieldRetentionBuffer()
        fun accept(body:String,elapsed:Long)=NmeaChecksum.append(body).let{line->cache.accept(NmeaFieldDecoder.decode(line,elapsed),NmeaFieldDecoder.heartbeat(line),line,elapsed)}
        assertTrue(accept("IIROT,3.2,A",100).any{it.key.semantic==NmeaFieldSemantic.ROT})
        assertFalse(accept("IIROT,,V",200).any{it.key.semantic==NmeaFieldSemantic.ROT})
    }

    @Test fun retainedFieldsExpireWithoutWaitingForAnotherSentence(){
        val cache=NmeaFieldRetentionBuffer(retentionMillis=1_000)
        val line=NmeaChecksum.append("IIXDR,P,1.01320,B,PHONE_BARO")
        assertEquals(1,cache.accept(NmeaFieldDecoder.decode(line,100),NmeaFieldDecoder.heartbeat(line),line,100).count{it.key.semantic==NmeaFieldSemantic.AIR_PRESSURE})
        assertTrue(cache.expire(1_101).none{it.key.semantic==NmeaFieldSemantic.AIR_PRESSURE})
    }
}
