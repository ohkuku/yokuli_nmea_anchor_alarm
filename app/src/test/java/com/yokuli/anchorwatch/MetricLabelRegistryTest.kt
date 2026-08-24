package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.vessel.InstrumentTileId
import com.yokuli.anchorwatch.domain.vessel.MetricLabelRegistry
import org.junit.Assert.*
import org.junit.Test

class MetricLabelRegistryTest{
    @Test fun marineAcronymsAreStableAcrossLocalizedDescriptions(){
        val trueWind=MetricLabelRegistry.get(InstrumentTileId.TRUE_WIND_SPEED)
        assertEquals("TWS",trueWind.acronym);assertEquals("True wind speed",trueWind.english);assertEquals("真风速",trueWind.simplifiedChinese)
        assertEquals("SOG",MetricLabelRegistry.get(InstrumentTileId.SOG).acronym)
        assertEquals("UKC",MetricLabelRegistry.get(InstrumentTileId.UKC).acronym)
    }
}
