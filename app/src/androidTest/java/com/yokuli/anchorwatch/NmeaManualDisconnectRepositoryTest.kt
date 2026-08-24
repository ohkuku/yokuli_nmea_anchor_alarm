package com.yokuli.anchorwatch

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yokuli.anchorwatch.runtime.nmea.NmeaManualDisconnectRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NmeaManualDisconnectRepositoryTest{
    @Test fun explicitDisconnectLatchSurvivesRepositoryRecreationUntilExplicitConnectClearsIt()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val first=NmeaManualDisconnectRepository(context);first.clear()
        try{
            first.suppress(1234L)
            val restored=NmeaManualDisconnectRepository(context).current()
            assertTrue(restored.suppressed);assertEquals(1234L,restored.disconnectedAtUtcMillis)
            NmeaManualDisconnectRepository(context).clear()
            assertFalse(first.current().suppressed);assertNull(first.current().disconnectedAtUtcMillis)
        }finally{first.clear()}
    }
}
