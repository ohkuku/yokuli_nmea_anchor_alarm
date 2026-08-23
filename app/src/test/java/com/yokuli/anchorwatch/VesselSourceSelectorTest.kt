package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.vessel.*
import org.junit.Assert.assertEquals
import org.junit.Test

class VesselSourceSelectorTest {
    private fun value(number:Int,source:VesselDataSource,freshness:VesselDataFreshness,quality:VesselDataQuality=VesselDataQuality.GOOD)=VesselObservation(number,source,quality=quality,freshness=freshness)
    @Test fun autoPrefersFreshGoodBoatThenFreshPhone(){val boat=value(1,VesselDataSource.BOAT_NMEA,VesselDataFreshness.FRESH);val phone=value(2,VesselDataSource.PHONE_GNSS,VesselDataFreshness.FRESH);assertEquals(boat,VesselSourceSelector.select(VesselSourcePreference.AUTO,boat,phone));assertEquals(phone,VesselSourceSelector.select(VesselSourcePreference.AUTO,boat.copy(freshness=VesselDataFreshness.STALE),phone))}
    @Test fun explicitPreferenceNeverSilentlyChangesSource(){val boat=value(1,VesselDataSource.BOAT_NMEA,VesselDataFreshness.STALE);val phone=value(2,VesselDataSource.PHONE_GNSS,VesselDataFreshness.FRESH);assertEquals(boat,VesselSourceSelector.select(VesselSourcePreference.BOAT,boat,phone));assertEquals(phone,VesselSourceSelector.select(VesselSourcePreference.PHONE,boat,phone))}
    @Test fun autoHysteresisPreventsOneSecondFlappingAndRequiresStableBoatRecovery(){
        val selector=VesselAutoSourceSelector<Int>()
        val boatFresh=value(1,VesselDataSource.BOAT_NMEA,VesselDataFreshness.FRESH)
        val boatStale=boatFresh.copy(freshness=VesselDataFreshness.STALE)
        val phone=value(2,VesselDataSource.PHONE_GNSS,VesselDataFreshness.FRESH)
        assertEquals(VesselDataSource.BOAT_NMEA,selector.select(VesselSourcePreference.AUTO,boatFresh,phone,0).source)
        assertEquals(VesselDataSource.BOAT_NMEA,selector.select(VesselSourcePreference.AUTO,boatStale,phone,1_000).source)
        assertEquals(VesselDataSource.BOAT_NMEA,selector.select(VesselSourcePreference.AUTO,boatFresh,phone,2_000).source)
        assertEquals(VesselDataSource.BOAT_NMEA,selector.select(VesselSourcePreference.AUTO,boatStale,phone,3_000).source)
        assertEquals(VesselDataSource.PHONE_GNSS,selector.select(VesselSourcePreference.AUTO,boatStale,phone,5_000).source)
        assertEquals(VesselDataSource.PHONE_GNSS,selector.select(VesselSourcePreference.AUTO,boatFresh,phone,6_000).source)
        assertEquals(VesselDataSource.PHONE_GNSS,selector.select(VesselSourcePreference.AUTO,boatFresh,phone,10_999).source)
        assertEquals(VesselDataSource.BOAT_NMEA,selector.select(VesselSourcePreference.AUTO,boatFresh,phone,11_000).source)
    }
}
