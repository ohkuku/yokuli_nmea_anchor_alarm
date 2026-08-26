package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.nmea.NmeaChecksum
import com.yokuli.anchorwatch.data.nmea.NmeaConnectionManager
import com.yokuli.anchorwatch.data.nmea.NmeaConnectionRetryPolicy
import com.yokuli.anchorwatch.data.nmea.Protocol
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class DeterministicNmeaServer(private val scripts:List<List<Pair<Long,String>>>):Closeable{
    private val server=ServerSocket(0);private val sockets=CopyOnWriteArrayList<Socket>();@Volatile private var running=true
    val port=server.localPort
    private val worker=thread(isDaemon=true,name="fake-nmea-server"){
        var index=0
        while(running&&index<scripts.size){val socket=runCatching{server.accept()}.getOrNull()?:break;sockets+=socket;runCatching{scripts[index++].forEach{(delay,line)->if(delay>0)Thread.sleep(delay);socket.getOutputStream().write((line+"\r\n").toByteArray());socket.getOutputStream().flush()}};runCatching{socket.close()}}
    }
    override fun close(){running=false;sockets.forEach{runCatching{it.close()}};runCatching{server.close()};worker.join(2_000)}
}

class NmeaTransportFaultTest{
    @Test fun localTcpFeedCanPauseCloseReconnectAndResumeWithoutDuplicatingOwner(){
        val first=NmeaChecksum.append("GPGGA,120000,3650.9100,S,17445.7980,E,1,10,0.8,0.0,M,0.0,M,,")
        val second=NmeaChecksum.append("GPRMC,120001,A,3650.9100,S,17445.7980,E,0.2,12.0,170826,,,A")
        DeterministicNmeaServer(listOf(listOf(100L to first),listOf(100L to second))).use{server->runBlocking{
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            // Production deliberately protects fragile marine gateways with a
            // 15-second peer-close backoff. This deterministic test verifies
            // ownership/generation recovery, so inject a short policy rather
            // than coupling the assertion timeout to the vessel safety delay.
            val manager=NmeaConnectionManager(
                scope,
                NmeaConnectionRetryPolicy(
                    openFailureRetryMillis=100,
                    peerDisconnectRetryMillis=100,
                    reconnectCoalesceMillis=50,
                    manualReconnectCooldownMillis=100,
                ),
            )
            try{
                assertTrue(manager.connect(ConnectionProfile(protocol=Protocol.TCP,host="127.0.0.1",port=server.port,autoReconnect=true,noDataTimeoutSeconds=3)))
                assertEquals(first,withTimeout(5_000){manager.lines.first()})
                assertEquals(second,withTimeout(8_000){manager.lines.first{it==second}})
            }finally{manager.disconnect();scope.cancel()}
        }}
    }
}
