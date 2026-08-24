package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.nmea.NmeaChecksum
import com.yokuli.anchorwatch.data.nmea.NmeaConnectionManager
import com.yokuli.anchorwatch.data.nmea.output.DedicatedNmeaTcpClient
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.testsupport.FakeNmeaInputServer
import com.yokuli.anchorwatch.testsupport.FakeNmeaOutputReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class P0NmeaEndpointStoryTest{
    @Test fun formalInputOwnsOneSocketWhileQuietThenReceivesLater()=runBlocking{
        FakeNmeaInputServer().use{input->
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val manager=NmeaConnectionManager(scope)
            try{
                assertTrue(manager.connect(ConnectionProfile(host="127.0.0.1",port=input.port,noDataTimeoutSeconds=1,autoReconnect=true)))
                withTimeout(3_000){manager.state.first{it==NmeaConnectionState.CONNECTED_NO_DATA}}
                assertEquals(1,input.acceptedCount.get())
                input.emit(NmeaChecksum.append("GPRMC,073000.00,A,3650.9100,S,17445.7980,E,0.2,180.0,250826,,,A"))
                withTimeout(3_000){while(manager.diagnostics.value.lastPacketElapsed==null)delay(20)}
                assertEquals("A quiet endpoint must not be probed by a second disposable connection",1,input.acceptedCount.get())
            }finally{manager.disconnect();scope.cancel()}
        }
    }

    @Test fun dedicatedOutputReceiverIsIndependentFromFormalInput()=runBlocking{
        FakeNmeaInputServer().use{input->FakeNmeaOutputReceiver().use{output->
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val manager=NmeaConnectionManager(scope);val writer=DedicatedNmeaTcpClient()
            try{
                manager.connect(ConnectionProfile(host="127.0.0.1",port=input.port,noDataTimeoutSeconds=1))
                withTimeout(3_000){manager.state.first{it==NmeaConnectionState.CONNECTED_NO_DATA}}
                val result=writer.write("127.0.0.1",output.port,listOf("\$PYOK,HEARTBEAT*00\r\n"))
                assertTrue(result.success);assertEquals("\$PYOK,HEARTBEAT*00",output.awaitLine())
                assertEquals(1,input.acceptedCount.get());assertEquals(1,output.acceptedCount.get())
                assertNotNull(manager.diagnostics.value.connectedAtElapsedRealtime)
            }finally{writer.close();manager.disconnect();scope.cancel()}
        }}
    }
}
