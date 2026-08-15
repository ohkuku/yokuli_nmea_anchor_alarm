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
            withTimeout(5_000) { manager.state.first { it == NmeaConnectionState.CONNECTED } }
            assertFalse(manager.connect(profile))
            delay(250)
            assertEquals(1, accepted.get())
            assertEquals(NmeaConnectionState.CONNECTED, manager.state.value)

            manager.disconnect()
            assertEquals(NmeaConnectionState.DISCONNECTED, manager.state.value)
            assertTrue(manager.connect(profile))
            withTimeout(5_000) { manager.state.first { it == NmeaConnectionState.CONNECTED } }
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
            withTimeout(5_000) { manager.state.first { it == NmeaConnectionState.CONNECTED } }
            assertFalse(manager.ensureConnected(ConnectionProfile(host = "127.0.0.1", port = secondServer.localPort)))
            delay(250)
            assertEquals(1, firstAccepted.get())
            assertEquals(0, secondAccepted.get())
            assertEquals(NmeaConnectionState.CONNECTED, manager.state.value)
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
}
