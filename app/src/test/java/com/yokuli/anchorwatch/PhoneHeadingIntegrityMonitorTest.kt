package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.model.HeadingQuality
import com.yokuli.anchorwatch.location.PhoneHeadingIntegrityMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneHeadingIntegrityMonitorTest {
    @Test fun pickedUpPhoneStopsHeadingImmediatelyAndRequiresStableRecovery(){
        val monitor=PhoneHeadingIntegrityMonitor(recoveryMillis=20_000)
        monitor.observe(0,10.0,5.0,0.0,9.81,3)
        assertEquals(HeadingQuality.STABLE,monitor.observe(20_000,10.0,5.0,0.0,9.81,3).quality)
        val moving=monitor.observe(21_000,80.0,30.0,1.2,14.0,3)
        assertEquals(HeadingQuality.MOVING,moving.quality);assertNull(moving.headingTrueDegrees)
        assertEquals(HeadingQuality.RECOVERING,monitor.observe(22_000,80.0,5.0,0.0,9.81,3).quality)
        val recovered=monitor.observe(42_000,80.0,5.0,0.0,9.81,3)
        assertEquals(HeadingQuality.STABLE,recovered.quality);assertEquals(1,recovered.headingEpoch)
    }

    @Test fun stableNewPlacementCanRecoverWithANewTiltAndHeadingEpoch(){
        val monitor=PhoneHeadingIntegrityMonitor(recoveryMillis=20_000)
        monitor.observe(0,10.0,5.0,0.0,9.81,3)
        assertEquals(HeadingQuality.STABLE,monitor.observe(20_000,10.0,5.0,0.0,9.81,3).quality)
        assertEquals(HeadingQuality.DISTURBED,monitor.observe(21_000,90.0,28.0,0.0,9.81,3).quality)
        assertEquals(HeadingQuality.RECOVERING,monitor.observe(22_000,90.0,28.0,0.0,9.81,3).quality)
        val recovered=monitor.observe(42_000,90.0,28.0,0.0,9.81,3)
        assertEquals(HeadingQuality.STABLE,recovered.quality)
        assertEquals(1,recovered.headingEpoch)
    }
}
