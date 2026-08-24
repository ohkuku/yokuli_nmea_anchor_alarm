package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.vessel.InstrumentLayoutPolicy
import com.yokuli.anchorwatch.domain.vessel.InstrumentTileId
import com.yokuli.anchorwatch.domain.vessel.TripInstrumentPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import com.yokuli.anchorwatch.data.trip.InstrumentTileSize
import org.junit.Assert.assertTrue

class InstrumentLayoutPolicyTest {
    @Test fun eachPresetHasAnIndependentUsefulDefault(){
        val layouts=TripInstrumentPreset.entries.associateWith(InstrumentLayoutPolicy::defaults)
        assertFalse(layouts.filterKeys{it!=TripInstrumentPreset.CUSTOM}.values.any{it.isEmpty()})
        assertEquals(emptyList<InstrumentTileId>(),layouts.getValue(TripInstrumentPreset.CUSTOM))
        assertEquals(InstrumentTileId.SOG,layouts.getValue(TripInstrumentPreset.NAV).first())
        assertEquals(InstrumentTileId.PRESSURE,layouts.getValue(TripInstrumentPreset.WEATHER).first())
        assertTrue(layouts.getValue(TripInstrumentPreset.SAILING).contains(InstrumentTileId.TRUE_WIND_DIRECTION))
        assertEquals(listOf("SMALL","MEDIUM","WIDE","LARGE","HERO"),InstrumentTileSize.entries.map{it.name})
    }

    @Test fun normalizationKeepsUserOrderRemovesDuplicatesAndRejectsForeignTiles(){
        assertEquals(listOf(InstrumentTileId.COG,InstrumentTileId.SOG),InstrumentLayoutPolicy.normalized(TripInstrumentPreset.NAV,listOf(InstrumentTileId.COG,InstrumentTileId.HEEL,InstrumentTileId.SOG,InstrumentTileId.COG)))
    }
}
