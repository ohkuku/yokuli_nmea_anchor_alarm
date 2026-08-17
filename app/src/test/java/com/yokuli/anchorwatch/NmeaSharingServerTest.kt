package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.sharing.NetworkAddressProvider
import com.yokuli.anchorwatch.data.sharing.NmeaSharingServer
import com.yokuli.anchorwatch.data.sharing.SharingServerState
import com.yokuli.anchorwatch.data.sharing.NmeaOutputMux
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.PositionProvider
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaSharingServerTest {
    @Test fun capacityMeetsProductMinimumAndQueuesAreBounded(){assertTrue(NmeaSharingServer.MAX_CLIENTS>=5);assertTrue(NmeaSharingServer.CLIENT_QUEUE_CAPACITY in 16..1024)}

    @Test fun broadcastsCrlfSentenceToTcpClientAndStopsCleanly(){
        val port=ServerSocket(0).use{it.localPort};val server=NmeaSharingServer(NetworkAddressProvider())
        try{
            server.start(port);waitUntil{server.status.value.state==SharingServerState.RUNNING}
            Socket("127.0.0.1",port).use{client->client.soTimeout=2_000;waitUntil{server.status.value.clientCount==1};server.publish("\$GNRMC,1*00\r\n");assertEquals("\$GNRMC,1*00",client.getInputStream().bufferedReader().readLine())}
        }finally{server.stop()}
        assertEquals(SharingServerState.STOPPED,server.status.value.state)
    }

    @Test fun broadcastsTheSameOrderedStreamToThreeClientsAndReportsTheirAddresses(){
        val port=ServerSocket(0).use{it.localPort};val server=NmeaSharingServer(NetworkAddressProvider())
        try{
            server.start(port);waitUntil{server.status.value.state==SharingServerState.RUNNING}
            val clients=List(3){Socket("127.0.0.1",port).apply{soTimeout=2_000}}
            clients.useAll{connected->waitUntil{server.status.value.clientCount==3};server.publish("\$A*00\r\n");server.publish("\$B*00\r\n");connected.forEach{socket->val reader=socket.getInputStream().bufferedReader();assertEquals("\$A*00",reader.readLine());assertEquals("\$B*00",reader.readLine())};assertEquals(3,server.status.value.clients.size);assertTrue(server.status.value.clients.all{it.address.contains("127.0.0.1")})}
        }finally{server.stop()}
    }

    @Test fun systemOnlyServerPublishesMappedGnPositionWithoutBoatInput(){
        val port=ServerSocket(0).use{it.localPort};val server=NmeaSharingServer(NetworkAddressProvider());val mux=NmeaOutputMux()
        try{
            server.start(port);waitUntil{server.status.value.state==SharingServerState.RUNNING}
            Socket("127.0.0.1",port).use{client->client.soTimeout=2_000;waitUntil{server.status.value.clientCount==1};mux.acceptedPosition(NavigationFix(-36.8485,174.7633,1_720_000_000_000,10_000,sogKnots=2.4,cogTrueDegrees=123.4,horizontalAccuracyMeters=4.0,positionProvider=PositionProvider.ANDROID_GNSS,sourceSentence="SYSTEM",valid=true),10_100).forEach(server::publish);val reader=client.getInputStream().bufferedReader();assertTrue(reader.readLine().startsWith("\$GNRMC"));assertTrue(reader.readLine().startsWith("\$GNGGA"));assertTrue(reader.readLine().startsWith("\$GNVTG"))}
        }finally{server.stop()}
    }

    @Test fun abnormalListenerClosureAutomaticallyRebindsTheSamePort(){
        val port=ServerSocket(0).use{it.localPort};val server=NmeaSharingServer(NetworkAddressProvider())
        try{server.start(port);waitUntil{server.status.value.state==SharingServerState.RUNNING};server.forceRebindForTest();waitUntil{server.status.value.state==SharingServerState.ERROR};waitUntil{server.status.value.state==SharingServerState.RUNNING};Socket("127.0.0.1",port).use{}}
        finally{server.stop()}
    }

    @Test fun slowClientIsDroppedWithoutBlockingProducerOrFastClient(){
        val port=ServerSocket(0).use{it.localPort};val server=NmeaSharingServer(NetworkAddressProvider());val reading=AtomicBoolean(true)
        try{
            server.start(port);waitUntil{server.status.value.state==SharingServerState.RUNNING}
            val slow=Socket("127.0.0.1",port).apply{receiveBufferSize=1_024};val fast=Socket("127.0.0.1",port)
            val reader=thread(start=true,isDaemon=true){val input=fast.getInputStream();val buffer=ByteArray(8_192);while(reading.get()){val count=runCatching{input.read(buffer)}.getOrDefault(-1);if(count<0)break}}
            waitUntil{server.status.value.clientCount==2}
            val payload="\$GPTXT,"+"X".repeat(2_000)+"*00\r\n";for(index in 0 until 3_000){server.publish(payload);Thread.sleep(1);if(server.status.value.droppedSlowClients>0)break}
            waitUntil{server.status.value.droppedSlowClients>0};assertTrue(server.status.value.clientCount>=1);assertTrue(server.status.value.sentSentences>0)
            reading.set(false);runCatching{fast.close()};runCatching{slow.close()};reader.join(500)
        }finally{reading.set(false);server.stop()}
    }

    @Test fun clientWriteFailureIsIsolatedAndListenerKeepsRunning(){
        val port=ServerSocket(0).use{it.localPort};val server=NmeaSharingServer(NetworkAddressProvider())
        try{
            server.start(port);waitUntil{server.status.value.state==SharingServerState.RUNNING}
            val client=Socket("127.0.0.1",port).apply{setSoLinger(true,0)}
            waitUntil{server.status.value.clientCount==1};client.close()
            repeat(100){server.publish("\$GPRMC,DISCONNECTED*00\r\n");Thread.sleep(2)}
            waitUntil{server.status.value.clientCount==0}
            assertEquals(SharingServerState.RUNNING,server.status.value.state)
            Socket("127.0.0.1",port).use{replacement->waitUntil{server.status.value.clientCount==1};server.publish("\$GPRMC,RECONNECTED*00\r\n");assertEquals("\$GPRMC,RECONNECTED*00",replacement.getInputStream().bufferedReader().readLine())}
        }finally{server.stop()}
    }

    private inline fun <T:AutoCloseable,R> List<T>.useAll(block:(List<T>)->R):R=try{block(this)}finally{forEach{runCatching{it.close()}}}

    private fun waitUntil(condition:()->Boolean){repeat(300){if(condition())return;Thread.sleep(20)};assertTrue("condition timed out",condition())}
}
