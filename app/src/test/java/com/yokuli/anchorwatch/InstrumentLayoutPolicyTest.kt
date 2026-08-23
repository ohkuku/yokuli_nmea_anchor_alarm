package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.vessel.InstrumentLayoutPolicy
import com.yokuli.anchorwatch.domain.vessel.InstrumentTileId
import com.yokuli.anchorwatch.domain.vessel.TripInstrumentPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class InstrumentLayoutPolicyTest {
    @Test fun eachPresetHasAnIndependentUsefulDefault(){
        val layouts=TripInstrumentPreset.entries.associateWith(InstrumentLayoutPolicy::defaults)
        assertFalse(layouts.filterKeys{it!=TripInstrumentPreset.CUSTOM}.values.any{it.isEmpty()})
        assertEquals(emptyList<InstrumentTileId>(),layouts.getValue(TripInstrumentPreset.CUSTOM))
        assertEquals(InstrumentTileId.SOG,layouts.getValue(TripInstrumentPreset.NAV).first())
        assertEquals(InstrumentTileId.PRESSURE,layouts.getValue(TripInstrumentPreset.WEATHER).first())
    }

    @Test fun normalizationKeepsUserOrderRemovesDuplicatesAndRejectsForeignTiles(){
        assertEquals(listOf(InstrumentTileId.COG,InstrumentTileId.SOG),InstrumentLayoutPolicy.normalized(TripInstrumentPreset.NAV,listOf(InstrumentTileId.COG,InstrumentTileId.HEEL,InstrumentTileId.SOG,InstrumentTileId.COG)))
    }
}
