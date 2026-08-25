package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.nmea.Protocol
import com.yokuli.anchorwatch.data.nmea.output.NmeaOutboundLoopGuard
import com.yokuli.anchorwatch.data.nmea.output.NmeaOutputEndpointPolicy
import com.yokuli.anchorwatch.data.nmea.output.DedicatedNmeaTcpClient
import com.yokuli.anchorwatch.data.vessel.NmeaDeviceOutputSettings
import com.yokuli.anchorwatch.data.vessel.NmeaOutputTransportMode
import com.yokuli.anchorwatch.data.vessel.anyEnabled
import com.yokuli.anchorwatch.data.vessel.anyStreamSelected
import com.yokuli.anchorwatch.data.vessel.NmeaOutputLeasePolicy
import com.yokuli.anchorwatch.data.vessel.NmeaOutputTransportDefaults
import com.yokuli.anchorwatch.domain.vessel.NmeaOutputPurpose
import com.yokuli.anchorwatch.domain.vessel.*
import com.yokuli.anchorwatch.runtime.output.canonicalPublisherConfiguration
import com.yokuli.anchorwatch.runtime.output.NmeaPublisherConfig
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NmeaDeviceOutputPolicyTest{
    private val input=ConnectionProfile(protocol=Protocol.TCP,host="192.168.20.10",port=10110)

    @Test fun sameInputSocketIsTheAuthoritativeFreshInstallRouteWhileStoredAdvancedRoutesSurvive(){
        assertEquals(NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION,NmeaOutputTransportDefaults.restore(null))
        assertEquals(NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION,NmeaOutputTransportDefaults.restore("BROKEN_LEGACY_VALUE"))
        assertEquals(NmeaOutputTransportMode.DEDICATED_TCP,NmeaOutputTransportDefaults.restore(NmeaOutputTransportMode.DEDICATED_TCP.name))
        assertEquals(NmeaDestinationTransport.SAME_AS_INPUT_TCP_SOCKET,NmeaOutputDestination().transport)
    }

    @Test fun dedicatedTxIsValidWithoutOwningTheInputTransport(){
        val settings=NmeaDeviceOutputSettings(transportMode=NmeaOutputTransportMode.DEDICATED_TCP,outputHost="192.168.20.50",outputPort=10111)
        assertTrue(NmeaOutputEndpointPolicy.isValid(settings,input))
        assertFalse(NmeaOutputEndpointPolicy.needsInputTransport(settings))
        assertEquals("192.168.20.50" to 10111,NmeaOutputEndpointPolicy.resolved(settings,input))
    }

    @Test fun firstUseCannotEnableStreamsBeforeTransportChoice(){
        assertTrue(NmeaDeviceOutputSettings(phoneHeadingEnabled=true).anyStreamSelected)
        assertFalse(NmeaDeviceOutputSettings(phoneHeadingEnabled=true).anyEnabled)
        assertFalse(NmeaDeviceOutputSettings(phoneHeadingEnabled=true,transportConfigured=true).anyEnabled)
        assertTrue(NmeaDeviceOutputSettings(phoneHeadingEnabled=true,transportConfigured=true,publicationEnabled=true).anyEnabled)
    }

    @Test fun legacyCanonicalPurposeCannotPublishWithoutExplicitPhoneAppStreams(){
        val value=NmeaDeviceOutputSettings(purpose=NmeaOutputPurpose.CANONICAL_CLIENT_FEED,transportConfigured=true,publicationEnabled=true)
        assertFalse(value.anyStreamSelected);assertFalse(value.anyEnabled)
    }

    @Test fun processRestartNeverResumesRuntimeLeaseEvenForLegacyAutoStartConfiguration(){
        val configured=NmeaDeviceOutputSettings(phoneHeadingEnabled=true,transportConfigured=true,publicationEnabled=true)
        assertFalse(NmeaOutputLeasePolicy.shouldAutoStart(configured))
        assertFalse(NmeaOutputLeasePolicy.shouldAutoStart(configured.copy(publicationEnabled=false,autoStartOutput=true)))
        assertFalse(NmeaOutputLeasePolicy.shouldAutoStart(configured.copy(autoStartOutput=true,transportConfigured=false)))
    }

    @Test fun restoreAlwaysClearsRuntimeAndAutoStartLeases(){
        val restored=NmeaOutputLeasePolicy.afterRestore(NmeaDeviceOutputSettings(phoneHeadingEnabled=true,transportConfigured=true,publicationEnabled=true,autoStartOutput=true))
        assertFalse(restored.publicationEnabled);assertFalse(restored.autoStartOutput)
    }

    @Test fun legacySameSocketBackupPoliciesMigrateToAlwaysLocalInjection(){
        val migrated=NmeaDeviceOutputSettings(
            purpose=NmeaOutputPurpose.BOAT_BUS_INJECTION,
            phonePositionEnabled=true,phoneHeadingEnabled=true,phoneMotionEnabled=true,phonePressureEnabled=true,
            proprietaryStatusEnabled=true,
            positionPolicy=PublicationPolicy.BACKUP,headingPolicy=PublicationPolicy.ALWAYS,
            motionPolicy=PublicationPolicy.BACKUP,pressurePolicy=PublicationPolicy.ALWAYS,
            derivedWindPolicy=PublicationPolicy.BACKUP,autoStartOutput=true,
        ).canonicalPublisherConfiguration()
        assertEquals(NmeaOutputPurpose.BOAT_BUS_INJECTION,migrated.purpose)
        assertTrue(migrated.phonePositionEnabled);assertTrue(migrated.phoneHeadingEnabled)
        assertTrue(migrated.phoneMotionEnabled);assertTrue(migrated.phonePressureEnabled)
        assertFalse(migrated.proprietaryStatusEnabled);assertFalse(migrated.autoStartOutput)
        assertTrue(listOf(migrated.positionPolicy,migrated.headingPolicy,migrated.motionPolicy,migrated.pressurePolicy,migrated.derivedWindPolicy).all{it==PublicationPolicy.ALWAYS})
    }

    @Test fun livePublisherConfigCannotReadLegacyPerStreamOrBackupFlags(){
        val live=NmeaPublisherConfig.from(NmeaDeviceOutputSettings(
            purpose=NmeaOutputPurpose.BOAT_BUS_INJECTION,
            phoneHeadingEnabled=true,headingPolicy=PublicationPolicy.BACKUP,
            proprietaryStatusEnabled=true,autoStartOutput=true,
            transportMode=NmeaOutputTransportMode.DEDICATED_TCP,
            outputHost="10.0.0.2",outputPort=10111,
            transportConfigured=true,publicationEnabled=true,
        ))
        assertTrue(live.running);assertEquals(NmeaOutputTransportMode.DEDICATED_TCP,live.transportMode)
        val canonical=live.asOutputSettings()
        assertEquals(NmeaOutputPurpose.BOAT_BUS_INJECTION,canonical.purpose)
        assertTrue(canonical.phoneHeadingEnabled);assertEquals(PublicationPolicy.ALWAYS,canonical.headingPolicy)
        assertFalse(canonical.proprietaryStatusEnabled);assertFalse(canonical.autoStartOutput)
    }

    @Test fun sameConnectionAndDuplicateDedicatedEndpointAreWarned(){
        assertTrue(NmeaOutputEndpointPolicy.duplicateEndpointRisk(NmeaDeviceOutputSettings(),input))
        val duplicate=NmeaDeviceOutputSettings(transportMode=NmeaOutputTransportMode.DEDICATED_TCP,outputHost=input.host,outputPort=input.port)
        assertTrue(NmeaOutputEndpointPolicy.duplicateEndpointRisk(duplicate,input))
        assertTrue(NmeaOutputEndpointPolicy.opensSecondTransportOnInputEndpoint(duplicate,input))
        assertFalse(NmeaOutputEndpointPolicy.isValid(duplicate,input))
    }

    @Test fun duplicateIndependentTxIsBlockedByConfigurationEvenBeforeRxEverOpened(){
        val neverStartedRx=ConnectionProfile(protocol=Protocol.TCP,host="Fragile-Gateway.local.",port=10110)
        val tx=NmeaDeviceOutputSettings(
            transportMode=NmeaOutputTransportMode.DEDICATED_TCP,
            outputHost="fragile-gateway.LOCAL",
            outputPort=10110,
            transportConfigured=true,
            publicationEnabled=true,
            phoneHeadingEnabled=true,
        )
        assertTrue(NmeaOutputEndpointPolicy.opensSecondTransportOnInputEndpoint(tx,neverStartedRx))
        assertFalse("No RX runtime state is needed to reject a second transport",NmeaOutputEndpointPolicy.isValid(tx,neverStartedRx))
    }

    @Test fun explicitSameSocketModeNeverRepresentsASecondTransport(){
        val tx=NmeaDeviceOutputSettings(
            transportMode=NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION,
            transportConfigured=true,
            publicationEnabled=true,
            phoneHeadingEnabled=true,
        )
        assertFalse(NmeaOutputEndpointPolicy.opensSecondTransportOnInputEndpoint(tx,input))
        assertTrue(NmeaOutputEndpointPolicy.needsInputTransport(tx))
        assertTrue(NmeaOutputEndpointPolicy.isValid(tx,input))
    }

    @Test fun independentTxMayUseTheSameHostOnlyOnTheServersSeparateReceivePort(){
        val tx=NmeaDeviceOutputSettings(transportMode=NmeaOutputTransportMode.DEDICATED_TCP,outputHost=input.host,outputPort=input.port+1)
        assertFalse(NmeaOutputEndpointPolicy.opensSecondTransportOnInputEndpoint(tx,input))
        assertTrue(NmeaOutputEndpointPolicy.isValid(tx,input))
    }

    @Test fun protectedWriterNeverCreatesDuplicateSocketWhenRxIsAlreadyOpen(){
        ServerSocket(0).use{server->
            Socket("127.0.0.1",server.localPort).use{rxClient->server.accept().use{
                server.soTimeout=350
                val profile=ConnectionProfile(protocol=Protocol.TCP,host="127.0.0.1",port=server.localPort)
                val settings=NmeaDeviceOutputSettings(transportMode=NmeaOutputTransportMode.DEDICATED_TCP,outputHost=profile.host,outputPort=profile.port)
                val writer=DedicatedNmeaTcpClient()
                try{
                    val result=writer.write(settings,profile,listOf("\$PYOK,NOSECOND*00\r\n"))
                    assertFalse(result.success)
                    assertEquals(NmeaOutputEndpointPolicy.DUPLICATE_ENDPOINT_MESSAGE,result.error)
                    try{server.accept();fail("Independent TX must not create a second socket on the open RX endpoint")}catch(_:SocketTimeoutException){/* expected */}
                }finally{writer.close()}
                assertFalse(rxClient.isClosed)
            }}
        }
    }

    @Test fun protectedWriterNeverCreatesDuplicateSocketBeforeRxHasEverOpened(){
        ServerSocket(0).use{server->
            server.soTimeout=350
            val profile=ConnectionProfile(protocol=Protocol.TCP,host="127.0.0.1",port=server.localPort)
            val settings=NmeaDeviceOutputSettings(transportMode=NmeaOutputTransportMode.DEDICATED_TCP,outputHost=profile.host,outputPort=profile.port)
            val writer=DedicatedNmeaTcpClient()
            try{
                assertFalse(writer.write(settings,profile,listOf("\$PYOK,NOFIRST*00\r\n")).success)
                try{server.accept();fail("A matching TX configuration must be blocked without consulting RX runtime state")}catch(_:SocketTimeoutException){/* expected */}
            }finally{writer.close()}
        }
    }

    @Test fun tcpServerIsOneCanonicalOutputTransportAndDoesNotReuseInput(){
        val server=NmeaDeviceOutputSettings(transportMode=NmeaOutputTransportMode.TCP_SERVER,outputPort=10111,transportConfigured=true)
        assertTrue(NmeaOutputEndpointPolicy.isValid(server,input))
        assertFalse(NmeaOutputEndpointPolicy.needsInputTransport(server))
        assertFalse(NmeaOutputEndpointPolicy.duplicateEndpointRisk(server,input))
        assertEquals("0.0.0.0" to 10111,NmeaOutputEndpointPolicy.resolved(server,input))
    }

    @Test fun echoedOutboundSentenceIsQuarantinedOnlyForTheShortWindow(){
        val guard=NmeaOutboundLoopGuard();val sentence="\$IIHDT,123.00,T*00"
        guard.record(listOf(sentence),1_000)
        assertTrue(guard.isRecentOutbound(sentence,2_000))
        assertFalse(guard.isRecentOutbound(sentence,7_001))
    }

    @Test fun exactEchoQuarantineConsumesOneOccurrencePerOutboundFrame(){
        val guard=NmeaOutboundLoopGuard();val sentence="\$IIHDT,123.00,T*00"
        guard.record(listOf(sentence,sentence),1_000)
        assertTrue(guard.isRecentOutbound(sentence,1_100))
        assertTrue(guard.isRecentOutbound(sentence,1_200))
        assertFalse("A third identical boat sentence is not an App echo",guard.isRecentOutbound(sentence,1_300))
    }

    @Test fun semanticEchoWithAnotherTalkerAndChecksumIsStillQuarantined(){
        val guard=NmeaOutboundLoopGuard()
        guard.record(listOf("\$IIHDT,123.00,T*00\r\n"),1_000)
        guard.record(listOf("\$IIHDT,123.02,T*00\r\n"),1_200)
        assertTrue(guard.isRecentOutbound("\$HCHDT,123.01,T*7F",2_000))
        assertFalse(guard.isRecentOutbound("\$HCHDT,124.00,T*7F",2_000))
    }

    @Test fun semanticEchoAlsoConsumesOneOutboundOccurrence(){
        val guard=NmeaOutboundLoopGuard()
        guard.record(listOf("\$IIHDT,123.00,T*00\r\n","\$IIHDT,123.02,T*00\r\n"),1_000)
        assertTrue(guard.isRecentOutbound("\$HCHDT,123.01,T*7F",1_100))
        assertTrue(guard.isRecentOutbound("\$HCHDT,123.01,T*7F",1_200))
        assertFalse(guard.isRecentOutbound("\$HCHDT,123.01,T*7F",1_300))
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
        val client=DedicatedNmeaTcpClient().apply{socketFactory={object:Socket(){override fun connect(endpoint:SocketAddress?,timeout:Int){throw java.net.ConnectException("deterministic refusal")}}}}
        try{val result=client.write("127.0.0.1",10111,listOf("\$PYOK,TEST*00\r\n"));assertFalse(result.success);assertNotNull(result.error)}finally{client.close()}
    }

    @Test fun stopClosesAnInFlightConnectCandidateInsteadOfWaitingForTimeout(){
        val entered=CountDownLatch(1);val closed=CountDownLatch(1)
        val blocking=object:Socket(){
            override fun connect(endpoint:SocketAddress?,timeout:Int){entered.countDown();closed.await(5,TimeUnit.SECONDS);throw SocketException("closed by stop")}
            override fun close(){super.close();closed.countDown()}
        }
        val client=DedicatedNmeaTcpClient().also{it.socketFactory={blocking}}
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        try{
            val pending=executor.submit<com.yokuli.anchorwatch.data.nmea.output.DedicatedNmeaWriteResult>{client.write("fragile.invalid",10111,listOf("\$IIHDT,123.4,T*00\r\n"))}
            assertTrue(entered.await(1,TimeUnit.SECONDS))
            val started=System.nanoTime();client.close();val result=pending.get(1,TimeUnit.SECONDS)
            assertFalse(result.success);assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<1_000)
        }finally{client.close();executor.shutdownNow()}
    }
}
