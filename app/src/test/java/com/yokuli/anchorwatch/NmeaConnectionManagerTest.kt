package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.nmea.NmeaConnectionManager
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
        val boundaries=AtomicInteger();val managerScope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val manager=NmeaConnectionManager(managerScope){boundaries.incrementAndGet()}
        try{
            assertTrue(manager.connect(ConnectionProfile(host="127.0.0.1",port=server.localPort,autoReconnect=true)))
            withTimeout(6_000){while(accepted.get()<2)delay(20)}
            assertTrue(manager.diagnostics.value.connectionGeneration>=2)
            assertTrue("Every real socket attempt must clear connection-scoped held data",boundaries.get()>=2)
        }finally{manager.disconnect();managerScope.cancel();clients.forEach{runCatching{it.close()}};runCatching{server.close()};serverJob.cancelAndJoin()}
    }
}
