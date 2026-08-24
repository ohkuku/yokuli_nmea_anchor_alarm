package com.yokuli.anchorwatch.testsupport

import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** A controllable boat -> App endpoint. It can stay quiet after accepting the
 * formal connection, begin streaming later, and drop only live clients. */
class FakeNmeaInputServer:Closeable{
    private val server=ServerSocket(0)
    private val running=AtomicBoolean(true)
    private val clients=CopyOnWriteArrayList<Socket>()
    private val pending=LinkedBlockingQueue<String>()
    val acceptedCount=AtomicInteger()
    val port:Int get()=server.localPort
    private val acceptThread=thread(isDaemon=true,name="fake-nmea-rx-accept"){
        while(running.get()){
            val socket=runCatching{server.accept()}.getOrNull()?:break
            clients+=socket;acceptedCount.incrementAndGet()
            thread(isDaemon=true,name="fake-nmea-rx-client"){
                try{
                    while(running.get()&&!socket.isClosed){
                        val sentence=pending.poll(250,TimeUnit.MILLISECONDS)?:continue
                        socket.getOutputStream().write(sentence.withLineEnding().toByteArray())
                        socket.getOutputStream().flush()
                    }
                }catch(_:Exception){}finally{clients.remove(socket);runCatching{socket.close()}}
            }
        }
    }

    fun emit(sentence:String){pending.offer(sentence)}
    fun dropClients(){clients.toList().forEach{runCatching{it.close()}}}
    override fun close(){running.set(false);dropClients();runCatching{server.close()};acceptThread.interrupt()}
}

/** A separate App -> boat receiver. Tests use it to prove TX never steals or
 * restarts the FakeNmeaInputServer socket. */
class FakeNmeaOutputReceiver:Closeable{
    private val server=ServerSocket(0)
    private val running=AtomicBoolean(true)
    private val clients=CopyOnWriteArrayList<Socket>()
    private val lines=LinkedBlockingQueue<String>()
    val acceptedCount=AtomicInteger()
    val port:Int get()=server.localPort
    private val acceptThread=thread(isDaemon=true,name="fake-nmea-tx-accept"){
        while(running.get()){
            val socket=runCatching{server.accept()}.getOrNull()?:break
            clients+=socket;acceptedCount.incrementAndGet()
            thread(isDaemon=true,name="fake-nmea-tx-client"){
                try{socket.getInputStream().bufferedReader().useLines{values->values.forEach{lines.offer(it)}}}
                catch(_:Exception){}finally{clients.remove(socket);runCatching{socket.close()}}
            }
        }
    }

    fun awaitLine(timeoutMillis:Long=3_000L):String?=lines.poll(timeoutMillis,TimeUnit.MILLISECONDS)
    override fun close(){running.set(false);clients.toList().forEach{runCatching{it.close()}};runCatching{server.close()};acceptThread.interrupt()}
}

private fun String.withLineEnding()=trimEnd('\r','\n')+"\r\n"
