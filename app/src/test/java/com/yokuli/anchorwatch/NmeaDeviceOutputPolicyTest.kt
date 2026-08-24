package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.nmea.Protocol
import com.yokuli.anchorwatch.data.nmea.output.NmeaOutboundLoopGuard
import com.yokuli.anchorwatch.data.nmea.output.NmeaOutputEndpointPolicy
import com.yokuli.anchorwatch.data.nmea.output.DedicatedNmeaTcpClient
import com.yokuli.anchorwatch.data.vessel.NmeaDeviceOutputSettings
import com.yokuli.anchorwatch.data.vessel.NmeaOutputTransportMode
import com.yokuli.anchorwatch.data.vessel.anyEnabled
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class NmeaDeviceOutputPolicyTest{
    private val input=ConnectionProfile(protocol=Protocol.TCP,host="192.168.20.10",port=10110)

    @Test fun dedicatedTxIsValidWithoutOwningTheInputTransport(){
        val settings=NmeaDeviceOutputSettings(transportMode=NmeaOutputTransportMode.DEDICATED_TCP,outputHost="192.168.20.50",outputPort=10111)
        assertTrue(NmeaOutputEndpointPolicy.isValid(settings,input))
        assertFalse(NmeaOutputEndpointPolicy.needsInputTransport(settings))
        assertEquals("192.168.20.50" to 10111,NmeaOutputEndpointPolicy.resolved(settings,input))
    }

    @Test fun firstUseCannotEnableStreamsBeforeTransportChoice(){
        assertFalse(NmeaDeviceOutputSettings(phoneHeadingEnabled=true).anyEnabled)
        assertTrue(NmeaDeviceOutputSettings(phoneHeadingEnabled=true,transportConfigured=true).anyEnabled)
    }

    @Test fun sameConnectionAndDuplicateDedicatedEndpointAreWarned(){
        assertTrue(NmeaOutputEndpointPolicy.duplicateEndpointRisk(NmeaDeviceOutputSettings(),input))
        assertTrue(NmeaOutputEndpointPolicy.duplicateEndpointRisk(NmeaDeviceOutputSettings(transportMode=NmeaOutputTransportMode.DEDICATED_TCP,outputHost=input.host,outputPort=input.port),input))
    }

    @Test fun echoedOutboundSentenceIsQuarantinedOnlyForTheShortWindow(){
        val guard=NmeaOutboundLoopGuard();val sentence="\$IIHDT,123.00,T*00"
        guard.record(listOf(sentence),1_000)
        assertTrue(guard.isRecentOutbound(sentence,2_000))
        assertFalse(guard.isRecentOutbound(sentence,7_001))
    }

    @Test fun semanticEchoWithAnotherTalkerAndChecksumIsStillQuarantined(){
        val guard=NmeaOutboundLoopGuard()
        guard.record(listOf("\$IIHDT,123.00,T*00\r\n"),1_000)
        guard.record(listOf("\$IIHDT,123.02,T*00\r\n"),1_200)
        assertTrue(guard.isRecentOutbound("\$HCHDT,123.01,T*7F",2_000))
        assertFalse(guard.isRecentOutbound("\$HCHDT,124.00,T*7F",2_000))
    }

    @Test fun semanticCoincidenceNeverPermanentlyHidesARealSource(){
        val guard=NmeaOutboundLoopGuard()
        guard.record(listOf("\$IIHDT,123.00,T*00"),1_000)
        guard.record(listOf("\$IIHDT,123.02,T*00"),1_200)
        assertTrue(guard.isRecentOutbound("\$HCHDT,123.01,T*7F",2_000))
        // The phone heartbeat continues, but the bounded semantic quarantine
        // expires so an independent instrument with the same value is visible.
        guard.record(listOf("\$IIHDT,123.00,T*00"),6_500)
        guard.record(listOf("\$IIHDT,123.01,T*00"),6_700)
        assertFalse(guard.isRecentOutbound("\$HCHDT,123.02,T*7F",7_100))
    }


    @Test fun dedicatedWriterUsesOnlyItsOwnTxPort(){
        ServerSocket(0).use{rx->ServerSocket(0).use{tx->
            rx.soTimeout=350
            val received=java.util.concurrent.Executors.newSingleThreadExecutor().let{executor->
                val future=executor.submit<String>{tx.accept().use{it.getInputStream().bufferedReader().readLine()}}
                val client=DedicatedNmeaTcpClient()
                try{
                    val result=client.write("127.0.0.1",tx.localPort,listOf("\$PYOK,TEST*00\r\n"))
                    assertTrue(result.success);assertEquals("\$PYOK,TEST*00",future.get(2,TimeUnit.SECONDS))
                    try{rx.accept();fail("Dedicated TX must never connect to the RX port")}catch(_:SocketTimeoutException){/* expected */}
                }finally{client.close();executor.shutdownNow()}
            }
        }}
    }

    @Test fun dedicatedFailureDoesNotReportAWrite(){
        val closedPort=ServerSocket(0).use{it.localPort};val client=DedicatedNmeaTcpClient()
        try{val result=client.write("127.0.0.1",closedPort,listOf("\$PYOK,TEST*00\r\n"));assertFalse(result.success);assertNotNull(result.error)}finally{client.close()}
    }
}
