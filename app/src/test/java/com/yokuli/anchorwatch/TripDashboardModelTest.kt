package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.trip.DashboardTileBinding
import com.yokuli.anchorwatch.data.trip.InstrumentTileSize
import com.yokuli.anchorwatch.data.trip.TripCustomMetricRecordingPolicy
import com.yokuli.anchorwatch.data.trip.TripDashboard
import com.yokuli.anchorwatch.domain.vessel.InstrumentTileId
import com.yokuli.anchorwatch.domain.vessel.TripInstrumentPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripDashboardModelTest {
    @Test fun bindingRepresentsEitherCanonicalOrRawField(){
        val canonical=DashboardTileBinding(tileId=InstrumentTileId.SOG,size=InstrumentTileSize.LARGE)
        val raw=DashboardTileBinding(nmeaFieldId="II:XDR:2:RAW:RUDDER")
        assertEquals(InstrumentTileId.SOG,canonical.tileId);assertNull(canonical.nmeaFieldId)
        assertEquals("II:XDR:2:RAW:RUDDER",raw.nmeaFieldId);assertNull(raw.tileId)
    }

    @Test fun rawFieldTransformIsExplicitAndDoesNotChangeMissingData(){
        val binding=DashboardTileBinding(nmeaFieldId="II:XYZ:3:RAW:",label="Foil",unitOverride="deg",scale=2.0,offset=-1.0,recordInTrips=true)
        assertEquals(7.0,binding.transformed(4.0)!!,.001)
        assertNull(binding.transformed(null))
    }

    @Test fun dashboardVisibilityDoesNotImplicitlyRecordRawFields(){
        val visibleOnly=DashboardTileBinding(nmeaFieldId="II:XDR:1:RAW:RUDDER",recordInTrips=false)
        val recorded=DashboardTileBinding(nmeaFieldId="II:XDR:2:RAW:FOIL",recordInTrips=true)
        val selected=TripCustomMetricRecordingPolicy.bindings(listOf(TripDashboard("one",TripInstrumentPreset.CUSTOM,"Race",listOf(visibleOnly,recorded))))
        assertEquals(setOf("II:XDR:2:RAW:FOIL"),selected.keys)
    }
}
