package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.location.NmeaPositionAwaiter
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class NmeaPositionAwaiterTest {
    @Test fun freshFixWakesWaiterWithoutWaitingForAConnectionLabelPromotion() = runBlocking {
        val connection = MutableStateFlow(NmeaConnectionState.CONNECTED_NO_FIX)
        val fix = MutableStateFlow<NavigationFix?>(null)
        val started = MutableStateFlow<Long?>(1_000L)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            NmeaPositionAwaiter.awaitUsable(connection, fix, started, 5_000L, 15_000L) { 3_000L }
        }
        val expected = NavigationFix(
            latitude = -36.8485,
            longitude = 174.7633,
            receivedElapsedRealtime = 2_000L,
            sourceSentence = "GPRMC",
            valid = true,
        )

        fix.value = expected

        assertEquals(expected, waiting.await())
    }
}
