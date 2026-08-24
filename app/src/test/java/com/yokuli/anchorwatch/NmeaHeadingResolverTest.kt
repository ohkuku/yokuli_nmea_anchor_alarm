package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.nmea.NmeaHeadingResolver
import com.yokuli.anchorwatch.data.nmea.NmeaUpdate
import org.junit.Assert.*
import org.junit.Test

class NmeaHeadingResolverTest{
    private fun heading(id:String,type:String,trueDegrees:Double?=null,magneticDegrees:Double?=null)=NmeaUpdate(trueHeading=trueDegrees,magneticHeading=magneticDegrees,type=type,sentenceId=id)

    @Test fun qualityPriorityIsIndependentOfPacketOrder(){
        fun selected(updates:List<NmeaUpdate>):String?{val resolver=NmeaHeadingResolver();updates.forEachIndexed{i,value->resolver.accept(value,i.toLong())};return resolver.resolve(10).selected?.sourceId}
        val hdt=heading("IIHDT","HDT",10.0);val hdg=heading("HCHDG","HDG",11.0,9.0);val vhw=heading("SDVHW","VHW",12.0,10.0)
        assertEquals("IIHDT",selected(listOf(vhw,hdg,hdt)))
        assertEquals("IIHDT",selected(listOf(hdt,hdg,vhw)))
    }

    @Test fun equalPrioritySourceRemainsStickyWhileFresh(){
        val resolver=NmeaHeadingResolver()
        resolver.accept(heading("IIHDT","HDT",10.0),0)
        val result=resolver.accept(heading("HCHDT","HDT",11.0),1_000)
        assertEquals("IIHDT",result.selected?.sourceId)
    }

    @Test fun conflictRequiresSustainedDisagreement(){
        val resolver=NmeaHeadingResolver()
        resolver.accept(heading("IIHDT","HDT",0.0),0)
        assertFalse(resolver.accept(heading("HCHDT","HDT",30.0),100).conflict)
        assertFalse(resolver.resolve(3_099).conflict)
        val conflict=resolver.resolve(3_100)
        assertTrue(conflict.conflict);assertEquals(30.0,conflict.conflictDegrees?:Double.NaN,0.01)
    }

    @Test fun pinnedSourceNeverSilentlyFallsBack(){
        val resolver=NmeaHeadingResolver();resolver.accept(heading("IIHDT","HDT",20.0),0)
        resolver.pin("HCHDT")
        val missing=resolver.resolve(1_000)
        assertNull(missing.selected);assertTrue(missing.pinnedSourceUnavailable)
        val available=resolver.accept(heading("HCHDT","HDT",21.0),1_100)
        assertEquals("HCHDT",available.selected?.sourceId);assertFalse(available.pinnedSourceUnavailable)
    }

    @Test fun explicitInvalidityRemovesOnlyThatPhysicalHeadingSource(){
        val resolver=NmeaHeadingResolver()
        resolver.accept(heading("IIHDT","HDT",20.0),0)
        resolver.accept(heading("HCHDT","HDT",21.0),100)
        val afterInvalid=resolver.accept(NmeaUpdate(type="HDT",sentenceId="IIHDT",holdAllowed=false),200)
        assertEquals("HCHDT",afterInvalid.selected?.sourceId)
        assertTrue(afterInvalid.candidates.none{it.sourceId=="IIHDT"})
    }
}
