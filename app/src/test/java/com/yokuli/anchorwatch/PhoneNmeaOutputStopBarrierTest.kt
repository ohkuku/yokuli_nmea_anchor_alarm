package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.nmea.output.NmeaOutputStopBarrier
import java.io.FilterOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Proves byte-level Stop semantics rather than merely checking TX counters. */
class PhoneNmeaOutputStopBarrierTest{
    @Test fun stopCannotReturnBeforeAnInFlightSocketWriteHasJoined(){
        val payload="\$IIHDT,123.40,T*2B\r\n".toByteArray(Charsets.US_ASCII)
        val barrier=NmeaOutputStopBarrier()
        val writeEntered=CountDownLatch(1)
        val releaseWrite=CountDownLatch(1)
        val stopReturned=CountDownLatch(1)
        val localSocketBytes=AtomicLong(0)
        val bytesAtStopReturn=AtomicLong(-1)
        val executor=Executors.newFixedThreadPool(4)

        ServerSocket(0).use{server->
            val acceptedFuture=executor.submit<Socket>{server.accept()}
            Socket("127.0.0.1",server.localPort).use{client->
                acceptedFuture.get(1,TimeUnit.SECONDS).use{receiver->
                    val received=executor.submit<ByteArray>{receiver.getInputStream().readNBytes(payload.size)}
                    val socketOutput=object:FilterOutputStream(client.getOutputStream()){
                        override fun write(buffer:ByteArray,offset:Int,length:Int){
                            writeEntered.countDown()
                            assertTrue("test writer was never released",releaseWrite.await(2,TimeUnit.SECONDS))
                            out.write(buffer,offset,length)
                            out.flush()
                            localSocketBytes.addAndGet(length.toLong())
                        }
                    }
                    val writer=executor.submit<Unit>{barrier.withWriteLease{socketOutput.write(payload)}}
                    assertTrue(writeEntered.await(1,TimeUnit.SECONDS))

                    val stopper=executor.submit<Unit>{
                        barrier.stopAndJoin{bytesAtStopReturn.set(localSocketBytes.get())}
                        stopReturned.countDown()
                    }

                    // This is the prohibited sequence from the audit: Stop must
                    // not return while the old writer is still releasable.
                    assertFalse("STOP returned while an old socket write was in flight",stopReturned.await(200,TimeUnit.MILLISECONDS))
                    assertEquals(0,localSocketBytes.get())

                    releaseWrite.countDown()
                    writer.get(1,TimeUnit.SECONDS)
                    stopper.get(1,TimeUnit.SECONDS)
                    assertTrue(stopReturned.await(1,TimeUnit.SECONDS))
                    assertEquals(payload.size.toLong(),bytesAtStopReturn.get())
                    assertEquals(bytesAtStopReturn.get(),localSocketBytes.get())
                    assertArrayEquals(payload,received.get(1,TimeUnit.SECONDS))

                    // No old-session OutputStream call can occur after the
                    // write lease has joined and STOP has returned.
                    Thread.sleep(100)
                    assertEquals(bytesAtStopReturn.get(),localSocketBytes.get())
                }
            }
        }
        executor.shutdownNow()
    }
}
