package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.nmea.NmeaConnectionManager
import com.yokuli.anchorwatch.data.nmea.NmeaConnectionRetryPolicy
import com.yokuli.anchorwatch.data.nmea.NmeaTransportWriteFailure
import com.yokuli.anchorwatch.data.nmea.NmeaWireBatch
import com.yokuli.anchorwatch.data.nmea.output.DedicatedNmeaTcpClient
import com.yokuli.anchorwatch.data.nmea.output.NmeaWriteBackpressurePolicy
import com.yokuli.anchorwatch.data.nmea.output.NmeaWriteBackpressureState
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class NmeaConnectionManagerTest {
    @Test fun writeBackpressureHasAWarningWindowBeforeTheHardStallBoundary(){
        assertEquals(NmeaWriteBackpressureState.NORMAL,NmeaWriteBackpressurePolicy.evaluate(499))
        assertEquals(NmeaWriteBackpressureState.CONGESTED,NmeaWriteBackpressurePolicy.evaluate(500))
        assertEquals(NmeaWriteBackpressureState.CONGESTED,NmeaWriteBackpressurePolicy.evaluate(2_999))
        assertEquals(NmeaWriteBackpressureState.STALLED,NmeaWriteBackpressurePolicy.evaluate(3_000))
    }

    @Test fun oneHertzSchedulerTickIsOneContiguousCrlfWirePayload(){
        val sentences=listOf("\$IIHDT,123.40,T*2B\r\n","\$GNRMC,000000.00,V,,,,,,,260826,,,N*00\r\n")
        assertArrayEquals(sentences.joinToString("").toByteArray(Charsets.US_ASCII),NmeaWireBatch.encode(sentences))
        assertEquals(2,NmeaWireBatch.encode(sentences).toString(Charsets.US_ASCII).split("\r\n").count{it.isNotBlank()})
    }

    @Test fun refusedConnectionKeepsOneVisibleErrorAndDoesNotOpenAnotherSocket() = runBlocking {
        val closedPort=ServerSocket(0).use{it.localPort}
        val managerScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val manager=NmeaConnectionManager(managerScope)
        try{
            assertTrue(manager.connect(ConnectionProfile(host="127.0.0.1",port=closedPort,autoReconnect=false)))
            withTimeout(3_000){manager.state.first{it==NmeaConnectionState.ERROR}}
            val failure=manager.diagnostics.value
            assertEquals(1,failure.connectionGeneration)
            assertEquals("CONNECTION_REFUSED",failure.lastFailureCategory)
            assertEquals("USER_CONNECT",failure.lastOperation)
            assertTrue(failure.desiredConnected)
            assertFalse("A disabled auto-reconnect policy is not a tripped retry circuit",failure.circuitOpen)
            delay(250)
            assertEquals("The error must remain visible instead of becoming Disconnected",NmeaConnectionState.ERROR,manager.state.value)
            assertEquals("No hidden second transport attempt is allowed",1,manager.diagnostics.value.connectionGeneration)
        }finally{manager.disconnect();managerScope.cancel()}
    }

    @Test fun explicitConnectAfterTerminalErrorWaitsForTheOldAttemptToCloseBeforeOpeningReplacement() = runBlocking {
        val closedPort=ServerSocket(0).use{it.localPort}
        val liveServer=ServerSocket(0);val accepted=CompletableDeferred<Socket>()
        val serverJob=launch(Dispatchers.IO){runCatching{accepted.complete(liveServer.accept())}}
        val managerScope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val manager=NmeaConnectionManager(managerScope)
        var client:Socket?=null
        try{
            assertTrue(manager.connect(ConnectionProfile(host="127.0.0.1",port=closedPort)))
            withTimeout(3_000){manager.state.first{it==NmeaConnectionState.ERROR}}
            val replacement=ConnectionProfile(host="127.0.0.1",port=liveServer.localPort)
            withTimeout(3_000){while(!manager.connect(replacement))yield()}
            client=withTimeout(3_000){accepted.await()}
            withTimeout(3_000){manager.state.first{it==NmeaConnectionState.CONNECTED_NO_DATA}}
            Unit // Keep the JUnit4 method's generated JVM return type void.
        }finally{manager.disconnect();managerScope.cancel();runCatching{client?.close()};runCatching{liveServer.close()};serverJob.cancelAndJoin()}
    }

    @Test fun automaticOpenFailuresBackOffAndStopAfterTheBoundedCircuitLimit() = runBlocking {
        val closedPort=ServerSocket(0).use{it.localPort}
        val managerScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val manager=NmeaConnectionManager(
            managerScope,
            NmeaConnectionRetryPolicy(openFailureRetryMillis=80,peerDisconnectRetryMillis=40,reconnectCoalesceMillis=20,manualReconnectCooldownMillis=100,maxContinuousFailures=3),
        )
        try{
            assertTrue(manager.connect(ConnectionProfile(host="127.0.0.1",port=closedPort,autoReconnect=true)))
            withTimeout(3_000){manager.diagnostics.first{it.circuitOpen}}
            assertEquals(NmeaConnectionState.ERROR,manager.state.value)
            assertEquals(3,manager.diagnostics.value.connectionGeneration)
            assertEquals(3,manager.diagnostics.value.reconnectAttempt)
            assertEquals("AUTO_RETRY",manager.diagnostics.value.lastOperation)
            delay(200)
            assertEquals("The open circuit must stop retry storms",3,manager.diagnostics.value.connectionGeneration)
        }finally{manager.disconnect();managerScope.cancel()}
    }

    @Test fun dedicatedTxUsesAnotherPortWithoutInterruptingRx() = runBlocking {
        val rxServer=ServerSocket(0);val txServer=ServerSocket(0)
        val rxAccepted=CompletableDeferred<Unit>();val txReceived=CompletableDeferred<String>()
        val rxJob=launch(Dispatchers.IO){runCatching{rxServer.accept().use{socket->rxAccepted.complete(Unit);socket.getOutputStream().write("\$PYOK,RX*00\r\n".toByteArray());socket.getOutputStream().flush();awaitCancellation()}}}
        val txJob=launch(Dispatchers.IO){runCatching{txServer.accept().use{socket->txReceived.complete(socket.getInputStream().bufferedReader().readLine())}}}
        val managerScope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val manager=NmeaConnectionManager(managerScope);val txClient=DedicatedNmeaTcpClient()
        try{
            assertTrue(manager.connect(ConnectionProfile(host="127.0.0.1",port=rxServer.localPort,autoReconnect=false)))
            withTimeout(3_000){rxAccepted.await()}
            val result=txClient.write("127.0.0.1",txServer.localPort,listOf("\$PYOK,TX*00\r\n"))
            assertTrue(result.success);assertEquals("\$PYOK,TX*00",withTimeout(3_000){txReceived.await()})
            assertTrue(manager.state.value !in setOf(NmeaConnectionState.DISCONNECTED,NmeaConnectionState.ERROR))
        }finally{
            txClient.close();manager.disconnect();managerScope.cancel();runCatching{rxServer.close()};runCatching{txServer.close()};rxJob.cancelAndJoin();txJob.cancelAndJoin()
        }
    }

    @Test fun sameTcpSocketCanReceiveAndWrite(){runBlocking{
        val server=ServerSocket(0);val received=CompletableDeferred<String>();val serverJob=launch(Dispatchers.IO){runCatching{server.accept().use{socket->received.complete(socket.getInputStream().bufferedReader().readLine())}}}
        val managerScope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val manager=NmeaConnectionManager(managerScope)
        try{
            assertTrue(manager.connect(ConnectionProfile(host="127.0.0.1",port=server.localPort,autoReconnect=false)))
            withTimeout(3_000){while(manager.state.value==NmeaConnectionState.CONNECTING)delay(10)}
            assertTrue(manager.write(listOf("\$PYOK,SAME*00\r\n")))
            assertEquals("\$PYOK,SAME*00",withTimeout(3_000){received.await()})
        }finally{manager.disconnect();managerScope.cancel();runCatching{server.close()};serverJob.cancelAndJoin()}
    }}

    @Test fun sharedWriteStallAbortTargetsOnlyTheExpectedTransportGenerationAndRunsOnce() = runBlocking {
        val server=ServerSocket(0);val accepted=CompletableDeferred<Socket>()
        var serverClient:Socket?=null
        val serverJob=launch(Dispatchers.IO){runCatching{accepted.complete(server.accept());awaitCancellation()}}
        val managerScope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val manager=NmeaConnectionManager(managerScope)
        try{
            assertTrue(manager.connect(ConnectionProfile(host="127.0.0.1",port=server.localPort,autoReconnect=false)))
            serverClient=withTimeout(3_000){accepted.await()}
            withTimeout(3_000){manager.state.first{it==NmeaConnectionState.CONNECTED_NO_DATA}}
            val generation=manager.diagnostics.value.connectionGeneration
            assertFalse("An old queued generation must never close the current socket",manager.abortWriteStall(generation-1,"old generation"))
            assertTrue(manager.abortWriteStall(generation,"test write stall"))
            assertFalse("The same generation may be aborted only once",manager.abortWriteStall(generation,"duplicate abort"))
            assertEquals("TX_WRITE_STALL",manager.diagnostics.value.lastFailureCategory)
            assertEquals("TX_WRITE_STALL_ABORT",manager.diagnostics.value.lastOperation)
        }finally{
            manager.disconnect();managerScope.cancel();runCatching{serverClient?.close()};runCatching{server.close()};serverJob.cancelAndJoin()
        }
    }

    @Test fun queuedBatchFromOldTransportGenerationIsNeverWrittenAfterReconnect() = runBlocking {
        val server=ServerSocket(0);val clients=CopyOnWriteArrayList<Socket>();val accepted=AtomicInteger()
        val serverJob=launch(Dispatchers.IO){runCatching{while(isActive)server.accept().also{clients+=it;accepted.incrementAndGet()}}}
        val managerScope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val manager=NmeaConnectionManager(managerScope)
        val profile=ConnectionProfile(host="127.0.0.1",port=server.localPort)
        try{
            assertTrue(manager.connect(profile));withTimeout(5_000){manager.state.first{it==NmeaConnectionState.CONNECTED_NO_DATA}}
            val oldGeneration=manager.diagnostics.value.connectionGeneration
            manager.disconnect();assertTrue(manager.connect(profile));withTimeout(5_000){while(accepted.get()<2)delay(20)}
            val result=manager.writeExpected(listOf("\$PYOK,STALE*00\r\n"),oldGeneration)
            assertFalse(result.success);assertEquals(NmeaTransportWriteFailure.STALE_TRANSPORT_GENERATION,result.failure)
            assertNotEquals(oldGeneration,result.actualGeneration)
        }finally{manager.disconnect();managerScope.cancel();clients.forEach{runCatching{it.close()}};runCatching{server.close()};serverJob.cancelAndJoin()}
    }

    @Test fun quietTcpStreamReportsNoDataWithoutOpeningAnotherSocket() = runBlocking {
        val server=ServerSocket(0);val accepted=AtomicInteger();val clients=CopyOnWriteArrayList<Socket>()
        val serverJob=launch(Dispatchers.IO){runCatching{while(isActive)server.accept().also{clients+=it;accepted.incrementAndGet()}}}
        val managerScope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val manager=NmeaConnectionManager(managerScope)
        try{
            assertTrue(manager.connect(ConnectionProfile(host="127.0.0.1",port=server.localPort,noDataTimeoutSeconds=3)))
            withTimeout(5_000){manager.state.first{it==NmeaConnectionState.CONNECTED_NO_DATA}}
            delay(3_500)
            assertEquals(NmeaConnectionState.CONNECTED_NO_DATA,manager.state.value)
            assertEquals(1,accepted.get())
        }finally{manager.disconnect();managerScope.cancel();clients.forEach{runCatching{it.close()}};runCatching{server.close()};serverJob.cancelAndJoin()}
    }

    @Test fun explicitReconnectReplacesSameProfileOnceAndRapidTapsAreDebounced() = runBlocking {
        val server=ServerSocket(0);val accepted=AtomicInteger();val clients=CopyOnWriteArrayList<Socket>()
        val serverJob=launch(Dispatchers.IO){runCatching{while(isActive)server.accept().also{clients+=it;accepted.incrementAndGet()}}}
        val generationBoundaries=AtomicInteger()
        val managerScope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val manager=NmeaConnectionManager(managerScope){generationBoundaries.incrementAndGet()}
        val profile=ConnectionProfile(host="127.0.0.1",port=server.localPort)
        try{
            assertTrue(manager.connect(profile));withTimeout(5_000){manager.state.first{it==NmeaConnectionState.CONNECTED_NO_DATA}}
            assertTrue(manager.reconnect(profile))
            repeat(4){assertFalse(manager.reconnect(profile))}
            withTimeout(5_000){while(accepted.get()<2)delay(20)}
            delay(200);assertEquals(2,accepted.get())
            assertEquals("Only accepted connect/reconnect operations clear held NMEA fields",2,generationBoundaries.get())
        }finally{manager.disconnect();managerScope.cancel();clients.forEach{runCatching{it.close()}};runCatching{server.close()};serverJob.cancelAndJoin()}
    }

    @Test fun sameProfileIsReusedAndDisconnectClosesTheSession() = runBlocking {
        val server = ServerSocket(0)
        val accepted = AtomicInteger()
        val clients = CopyOnWriteArrayList<Socket>()
        val serverJob = launch(Dispatchers.IO) {
            try {
                while (isActive) server.accept().also { clients += it; accepted.incrementAndGet() }
            } catch (_: Exception) {
                // Closing the server socket ends the test accept loop.
            }
        }
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = NmeaConnectionManager(managerScope)
        val profile = ConnectionProfile(host = "127.0.0.1", port = server.localPort)
        try {
            assertTrue(manager.connect(profile))
            withTimeout(5_000) { manager.state.first { it == NmeaConnectionState.CONNECTED_NO_DATA } }
            assertFalse(manager.connect(profile))
            delay(250)
            assertEquals(1, accepted.get())
            assertEquals(NmeaConnectionState.CONNECTED_NO_DATA, manager.state.value)

            manager.disconnect()
            assertEquals(NmeaConnectionState.DISCONNECTED, manager.state.value)
            assertTrue(manager.connect(profile))
            withTimeout(5_000) { manager.state.first { it == NmeaConnectionState.CONNECTED_NO_DATA } }
            withTimeout(5_000) { while (accepted.get() < 2) delay(20) }
            assertEquals(2, accepted.get())
        } finally {
            manager.disconnect()
            managerScope.cancel()
            clients.forEach { runCatching { it.close() } }
            runCatching { server.close() }
            serverJob.cancelAndJoin()
        }
    }

    @Test fun ensureConnectedNeverReplacesAnExistingConnection() = runBlocking {
        val firstServer = ServerSocket(0)
        val secondServer = ServerSocket(0)
        val firstAccepted = AtomicInteger()
        val secondAccepted = AtomicInteger()
        val clients = CopyOnWriteArrayList<Socket>()
        val firstJob = launch(Dispatchers.IO) { runCatching { clients += firstServer.accept(); firstAccepted.incrementAndGet() } }
        val secondJob = launch(Dispatchers.IO) { runCatching { clients += secondServer.accept(); secondAccepted.incrementAndGet() } }
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = NmeaConnectionManager(managerScope)
        try {
            assertTrue(manager.connect(ConnectionProfile(host = "127.0.0.1", port = firstServer.localPort)))
            withTimeout(5_000) { manager.state.first { it == NmeaConnectionState.CONNECTED_NO_DATA } }
            assertFalse(manager.ensureConnected(ConnectionProfile(host = "127.0.0.1", port = secondServer.localPort)))
            delay(250)
            assertEquals(1, firstAccepted.get())
            assertEquals(0, secondAccepted.get())
            assertEquals(NmeaConnectionState.CONNECTED_NO_DATA, manager.state.value)
        } finally {
            manager.disconnect()
            managerScope.cancel()
            clients.forEach { runCatching { it.close() } }
            runCatching { firstServer.close() }
            runCatching { secondServer.close() }
            firstJob.cancelAndJoin()
            secondJob.cancelAndJoin()
        }
    }

    @Test fun automaticReconnectCreatesANewTransportGeneration() = runBlocking {
        val server=ServerSocket(0);val accepted=AtomicInteger();val clients=CopyOnWriteArrayList<Socket>()
        val serverJob=launch(Dispatchers.IO){runCatching{while(isActive){val socket=server.accept();clients+=socket;val count=accepted.incrementAndGet();if(count==1)socket.close()}}}
        val boundaries=AtomicInteger();val managerScope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val manager=NmeaConnectionManager(managerScope,NmeaConnectionRetryPolicy(openFailureRetryMillis=80,peerDisconnectRetryMillis=80,reconnectCoalesceMillis=20,manualReconnectCooldownMillis=100,maxContinuousFailures=3)){boundaries.incrementAndGet()}
        try{
            assertTrue(manager.connect(ConnectionProfile(host="127.0.0.1",port=server.localPort,autoReconnect=true)))
            withTimeout(6_000){while(accepted.get()<2)delay(20)}
            assertTrue(manager.diagnostics.value.connectionGeneration>=2)
            assertTrue("Every real socket attempt must clear connection-scoped held data",boundaries.get()>=2)
        }finally{manager.disconnect();managerScope.cancel();clients.forEach{runCatching{it.close()}};runCatching{server.close()};serverJob.cancelAndJoin()}
    }
}
