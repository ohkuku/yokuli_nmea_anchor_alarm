package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.nmea.Protocol
import com.yokuli.anchorwatch.data.sharing.NmeaSelfLoopPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaSelfLoopPolicyTest {
    @Test fun rejectsLoopbackAndLocalAddressOnSharingPort(){
        assertTrue(NmeaSelfLoopPolicy.isLiteralLoop(ConnectionProfile(host="127.0.0.1",port=10111),true,10111,listOf("192.168.20.4")))
        assertTrue(NmeaSelfLoopPolicy.isLiteralLoop(ConnectionProfile(host="192.168.20.4",port=10111),true,10111,listOf("192.168.20.4")))
    }

    @Test fun allowsDifferentPortDisabledServerAndUdp(){
        assertFalse(NmeaSelfLoopPolicy.isLiteralLoop(ConnectionProfile(host="127.0.0.1",port=10110),true,10111,emptyList()))
        assertFalse(NmeaSelfLoopPolicy.isLiteralLoop(ConnectionProfile(host="127.0.0.1",port=10111),false,10111,emptyList()))
        assertFalse(NmeaSelfLoopPolicy.isLiteralLoop(ConnectionProfile(host="",port=10111,protocol=Protocol.UDP),true,10111,emptyList()))
    }
}
