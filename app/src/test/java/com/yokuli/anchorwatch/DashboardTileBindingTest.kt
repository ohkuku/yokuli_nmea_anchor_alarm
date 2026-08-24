package com.yokuli.anchorwatch

import com.google.gson.Gson
import com.yokuli.anchorwatch.data.trip.DashboardTileBinding
import com.yokuli.anchorwatch.data.trip.InstrumentSourceOverride
import com.yokuli.anchorwatch.data.trip.InstrumentTileSize
import com.yokuli.anchorwatch.domain.vessel.InstrumentTileId
import org.junit.Assert.*
import org.junit.Test

class DashboardTileBindingTest{
    @Test fun builtInTilePersistsSizeAndDisplayOnlySourceOverride(){
        val original=DashboardTileBinding(tileId=InstrumentTileId.HEADING,size=InstrumentTileSize.HERO,sourceOverride=InstrumentSourceOverride.PHONE)
        val restored=Gson().fromJson(Gson().toJson(original),DashboardTileBinding::class.java)
        assertEquals(InstrumentTileSize.HERO,restored.size);assertEquals(InstrumentSourceOverride.PHONE,restored.sourceOverride)
    }

    @Test fun oldBindingWithoutSourceOverrideRemainsVesselDefaultCompatible(){
        val restored=Gson().fromJson("{\"tileId\":\"HEADING\",\"size\":\"MEDIUM\"}",DashboardTileBinding::class.java)
        assertNull(restored.sourceOverride)
    }
}
