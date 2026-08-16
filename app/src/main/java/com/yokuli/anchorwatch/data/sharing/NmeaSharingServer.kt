package com.yokuli.anchorwatch.data.sharing

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SharingServerState { STOPPED, STARTING, RUNNING, ERROR }

data class NmeaSharingClientStatus(
    val id: Long,
    val address: String,
    val connectedAtMillis: Long,
    val sentSentences: Long = 0,
)

data class NmeaSharingStatus(
    val state: SharingServerState = SharingServerState.STOPPED,
    val port: Int = 10111,
    val clientCount: Int = 0,
    val addresses: List<String> = emptyList(),
    val sentSentences: Long = 0,
    val droppedSlowClients: Long = 0,
    val lastOutputElapsed: Long? = null,
    val clients: List<NmeaSharingClientStatus> = emptyList(),
    val lastEvent: String = "",
    val message: String = "",
)

@Singleton
class NmeaSharingServer @Inject constructor(private val addresses: NetworkAddressProvider) {
    private data class Client(val socket: Socket, val queue: Channel<String>, val job: Job, val connectedAtMillis:Long, val address:String, val sent:AtomicLong=AtomicLong())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = ConcurrentHashMap<Long, Client>()
    private val ids = AtomicLong()
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val _status = MutableStateFlow(NmeaSharingStatus())
    val status = _status.asStateFlow()

    @Synchronized
    fun start(port: Int) {
        val safePort = port.takeIf { it in 1024..65535 } ?: 10111
        if (acceptJob?.isActive == true && _status.value.port == safePort) return
        stopLocked()
        _status.value = NmeaSharingStatus(SharingServerState.STARTING, safePort, addresses = addresses.localAddresses())
        acceptJob = scope.launch {
            while(isActive){
                try {
                    val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("0.0.0.0", safePort), 16) }
                    serverSocket = server
                    update(state = SharingServerState.RUNNING, message = "Listening on all interfaces",lastEvent="NMEA_SHARING_STARTED")
                    while (isActive) addClient(server.accept())
                } catch (error: Exception) {
                    if(!isActive)break
                    update(state = SharingServerState.ERROR, message = error.message ?: "Unable to start NMEA sharing",lastEvent="NMEA_SHARING_BIND_FAILED")
                    delay(REBIND_DELAY_MILLIS)
                    if(isActive)update(state=SharingServerState.STARTING,message="Rebinding NMEA Sharing on all interfaces")
                } finally {
                    runCatching{serverSocket?.close()};serverSocket=null
                }
            }
        }
    }

    @Synchronized
    fun stop() = stopLocked()

    internal fun forceRebindForTest(){runCatching{serverSocket?.close()}}

    fun publish(sentence: String) {
        if (_status.value.state != SharingServerState.RUNNING) return
        var dropped = 0L
        clients.forEach { (id, client) ->
            if (client.queue.trySend(sentence).isFailure) {
                dropped++
                closeClient(id, client)
            }
        }
        _status.update{current->current.copy(
            clientCount = clients.size,
            sentSentences = current.sentSentences + 1,
            droppedSlowClients = current.droppedSlowClients + dropped,
            lastOutputElapsed = System.nanoTime()/1_000_000L,
            clients = clientStatuses(),
            lastEvent = if(dropped>0)"NMEA_CLIENT_DROPPED_SLOW" else current.lastEvent,
        )}
    }

    private fun addClient(socket: Socket) {
        if (clients.size >= MAX_CLIENTS) { runCatching { socket.close() }; return }
        if(runCatching{socket.tcpNoDelay = true;socket.keepAlive = true;socket.sendBufferSize = 16 * 1024}.isFailure){runCatching{socket.close()};update(lastEvent="NMEA_CLIENT_DISCONNECTED");return}
        val id = ids.incrementAndGet()
        val queue = Channel<String>(CLIENT_QUEUE_CAPACITY)
        val connectedAt=System.currentTimeMillis();val address=socket.remoteSocketAddress?.toString()?.removePrefix("/")?:"unknown"
        val job = scope.launch {
            try {
                socket.getOutputStream().buffered().use { output ->
                    for (sentence in queue) { output.write(sentence.toByteArray(Charsets.US_ASCII)); output.flush();clients[id]?.sent?.incrementAndGet();update(clientCount=clients.size) }
                }
            } finally {
                clients.remove(id)?.let { runCatching { it.socket.close() } }
                update(clientCount = clients.size,lastEvent="NMEA_CLIENT_DISCONNECTED")
            }
        }
        clients[id] = Client(socket, queue, job,connectedAt,address)
        update(clientCount = clients.size,lastEvent="NMEA_CLIENT_CONNECTED")
    }

    private fun closeClient(id: Long, client: Client) {
        if (clients.remove(id, client)) {
            client.queue.close()
            runCatching { client.socket.close() }
            client.job.cancel()
        }
    }

    @Synchronized
    private fun stopLocked() {
        acceptJob?.cancel(); acceptJob = null
        runCatching { serverSocket?.close() }; serverSocket = null
        clients.toMap().forEach { (id, client) -> closeClient(id, client) }
        _status.value = NmeaSharingStatus(port = _status.value.port, addresses = addresses.localAddresses(),lastEvent="NMEA_SHARING_STOPPED")
    }

    private fun update(
        state: SharingServerState = _status.value.state,
        clientCount: Int = _status.value.clientCount,
        message: String = _status.value.message,
        lastEvent:String = _status.value.lastEvent,
    ) { _status.update{it.copy(state = state, clientCount = clientCount, addresses = addresses.localAddresses(),clients=clientStatuses(),message = message,lastEvent=lastEvent)} }

    private fun clientStatuses()=clients.map{(id,client)->NmeaSharingClientStatus(id,client.address,client.connectedAtMillis,client.sent.get())}.sortedBy{it.id}

    companion object { const val MAX_CLIENTS = 8; const val CLIENT_QUEUE_CAPACITY = 128;const val REBIND_DELAY_MILLIS=2_000L }
}
