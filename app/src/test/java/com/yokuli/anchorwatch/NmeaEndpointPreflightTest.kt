package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.nmea.NmeaChecksum
import com.yokuli.anchorwatch.data.nmea.NmeaEndpointPreflight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class NmeaEndpointPreflightTest {
    @Test fun rejectsUrlsAndInvalidPorts(){
        val preflight=NmeaEndpointPreflight()
        assertNotNull(preflight.validate(ConnectionProfile(host="tcp://boat.local")))
        assertNotNull(preflight.validate(ConnectionProfile(host="boat.local",port=0)))
    }

    @Test fun requiresRealNmeaTrafficBeforeSuccess()=runBlocking{
        val server=ServerSocket(0)
        val sender=async(Dispatchers.IO){server.accept().use{client->client.getOutputStream().write((NmeaChecksum.append("GPRMC,073000.00,A,3650.9100,S,17445.7980,E,0.2,12.0,150826,,,A")+"\r\n").toByteArray())}}
        try{assertTrue(NmeaEndpointPreflight().check(ConnectionProfile(host="127.0.0.1",port=server.localPort)).isSuccess)}finally{server.close();sender.await()}
    }
}
