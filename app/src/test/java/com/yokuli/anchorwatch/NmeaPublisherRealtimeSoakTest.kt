package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.nmea.output.DedicatedNmeaTcpClient
import com.yokuli.anchorwatch.data.sharing.NmeaOutputMux
import com.yokuli.anchorwatch.data.vessel.NmeaDeviceOutputSettings
import com.yokuli.anchorwatch.domain.vessel.VesselDataFreshness
import com.yokuli.anchorwatch.domain.vessel.VesselDataQuality
import com.yokuli.anchorwatch.domain.vessel.VesselDataSnapshot
import com.yokuli.anchorwatch.domain.vessel.VesselDataSource
import com.yokuli.anchorwatch.domain.vessel.VesselObservation
import com.yokuli.anchorwatch.domain.vessel.VesselSourceClass
import com.yokuli.anchorwatch.runtime.output.AnchorWatchNmeaFeedEncoder
import com.yokuli.anchorwatch.runtime.output.AnchorWatchNmeaHeartbeat
import com.yokuli.anchorwatch.runtime.output.AnchorWatchNmeaStream
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in wall-clock safety soak. Normal CI uses the accelerated deterministic
 * 10-minute test; release verification enables YOKULI_NMEA_REALTIME_SOAK=1. */
class NmeaPublisherRealtimeSoakTest{
    @Test fun fakeTcpReceiver_tenMinuteHeadingSoak_thenSixtySecondStoppedZeroBytes(){
        assumeTrue(System.getenv("YOKULI_NMEA_REALTIME_SOAK")=="1")
        ServerSocket(0).use{server->
            server.soTimeout=60_000
            val arrivals=Collections.synchronizedList(mutableListOf<Long>())
            val lines=Collections.synchronizedList(mutableListOf<String>())
            val receiverExecutor=Executors.newSingleThreadExecutor()
            val receiver=receiverExecutor.submit<Unit>{
                server.accept().use{socket->
                    socket.soTimeout=2_000
                    socket.getInputStream().bufferedReader().forEachLine{line->arrivals+=System.nanoTime();lines+=line}
                }
            }
            val client=DedicatedNmeaTcpClient()
            val heartbeat=AnchorWatchNmeaHeartbeat()
            val encoder=AnchorWatchNmeaFeedEncoder(NmeaOutputMux())
            val start=System.nanoTime()
            try{
                repeat(600){index->
                    val target=start+TimeUnit.MILLISECONDS.toNanos(index*1_000L)
                    while(true){
                        val remaining=target-System.nanoTime();if(remaining<=0L)break
                        TimeUnit.NANOSECONDS.sleep(remaining)
                    }
                    val elapsed=index*1_000L
                    assertTrue(AnchorWatchNmeaStream.HEADING in heartbeat.due(elapsed))
                    val observation=VesselObservation(
                        value=123.4,source=VesselDataSource.PHONE_MAGNETOMETER,
                        receivedElapsedRealtime=elapsed,sourceHeartbeatElapsedRealtime=elapsed,
                        quality=VesselDataQuality.GOOD,freshness=VesselDataFreshness.FRESH,
                        sourceClass=VesselSourceClass.PHONE_VESSEL_HEADING,
                    )
                    val sentence=encoder.encode(AnchorWatchNmeaStream.HEADING,VesselDataSnapshot(headingTrueDegrees=observation),NmeaDeviceOutputSettings(),elapsed).sentences.single()
                    assertTrue(client.write("127.0.0.1",server.localPort,listOf(sentence)).success)
                }
            }finally{client.close()}
            receiver.get(5,TimeUnit.SECONDS);receiverExecutor.shutdownNow()

            assertEquals(600,lines.size)
            assertTrue(lines.all{it.contains("HDT,123.40,T")&&!it.contains("HDT,,")})
            val maximumGapMillis=arrivals.zipWithNext().maxOf{(left,right)->TimeUnit.NANOSECONDS.toMillis(right-left)}
            assertTrue("Maximum receiver gap was ${maximumGapMillis}ms",maximumGapMillis<=1_200L)

            // Hard Stop already closed the only writer. Keep the fake receiver
            // listening for a real 60 seconds: no stale generation may reconnect.
            try{
                server.accept().use{throw AssertionError("Stopped publisher opened a new TCP connection")}
            }catch(_:SocketTimeoutException){/* expected: full sixty-second zero-connection window */}
            assertEquals(600,lines.size)
            assertFalse(client.isConnected("127.0.0.1",server.localPort))
        }
    }
}
