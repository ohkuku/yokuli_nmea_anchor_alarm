package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.vessel.TrueWindReference
import com.yokuli.anchorwatch.data.vessel.TrueWindResolver
import com.yokuli.anchorwatch.data.vessel.TrueWindSourceHysteresis
import org.junit.Assert.*
import org.junit.Test

class TrueWindResolverTest{
    @Test fun externalTrueWindAlwaysWins(){
        val value=TrueWindResolver.resolve(10.0,30.0,15.0,270.0,null,5.0,0.0,6.0,5.0)!!
        assertEquals(TrueWindReference.EXTERNAL,value.reference);assertEquals(15.0,value.speedKnots!!,0.01);assertEquals(270.0,value.directionTrueDegrees!!,0.01)
    }

    @Test fun waterReferencedDerivationUsesStwAndHeading(){
        val value=TrueWindResolver.resolve(10.0,0.0,null,null,null,5.0,0.0,8.0,90.0)!!
        assertEquals(TrueWindReference.WATER,value.reference);assertEquals(5.0,value.speedKnots!!,0.01);assertEquals(0.0,value.directionTrueDegrees!!,0.01)
    }

    @Test fun tailwindVectorKeepsCorrectFromDirectionAndAddsBoatSpeed(){
        val value=TrueWindResolver.resolve(5.0,180.0,null,null,null,5.0,0.0,null,null)!!
        assertEquals(TrueWindReference.WATER,value.reference);assertEquals(10.0,value.speedKnots!!,0.01);assertEquals(180.0,value.angleDegrees!!,0.01)
    }

    @Test fun groundFallbackIsExplicitWhenStwIsUnavailable(){
        val value=TrueWindResolver.resolve(10.0,0.0,null,null,null,null,0.0,5.0,0.0)!!
        assertEquals(TrueWindReference.GROUND,value.reference);assertTrue(value.provenance.contains("ground fallback"))
    }

    @Test fun apparentWindCannotBecomeTrueWindWithoutPhysicalHeading(){
        assertNull(TrueWindResolver.resolve(10.0,20.0,null,null,null,5.0,null,6.0,30.0))
    }

    @Test fun inputsMoreThanTwoSecondsApartAreNeverCombined(){
        val value=TrueWindResolver.resolve(10.0,20.0,null,null,null,5.0,30.0,null,null,
            apparentSpeedReceivedElapsed=1_000,apparentAngleReceivedElapsed=1_100,
            speedThroughWaterReceivedElapsed=1_200,headingReceivedElapsed=3_201)
        assertNull(value)
    }

    @Test fun portAndStarboardApparentWindKeepOppositeTrueWindSigns(){
        val port=TrueWindResolver.resolve(12.0,-45.0,null,null,null,4.0,0.0,null,null)!!
        val starboard=TrueWindResolver.resolve(12.0,45.0,null,null,null,4.0,0.0,null,null)!!
        assertTrue(port.angleDegrees!!<0);assertTrue(starboard.angleDegrees!!>0)
    }

    @Test fun externalMwvTrueRemainsAvailableWithoutHeadingAsPartialWind(){
        val value=TrueWindResolver.resolve(null,null,12.0,null,-35.0,null,null,null,null)!!
        assertEquals(TrueWindReference.EXTERNAL,value.reference)
        assertEquals(12.0,value.speedKnots!!,0.01)
        assertEquals(-35.0,value.angleDegrees!!,0.01)
        assertNull(value.directionTrueDegrees)
    }

    @Test fun externalDirectionAndAngleKeepIndependentProvenance(){
        val value=TrueWindResolver.resolve(null,null,18.0,240.0,null,null,200.0,null,null)!!
        assertEquals("external true-wind direction",value.directionProvenance)
        assertEquals("external true-wind direction + selected heading",value.angleProvenance)
        assertEquals(40.0,value.angleDegrees!!,0.01)
    }

    @Test fun recoveringExternalWindMustRemainStableBeforeReplacingDerived(){
        fun wind(reference:TrueWindReference)=com.yokuli.anchorwatch.data.vessel.ResolvedTrueWind(12.0,220.0,20.0,reference,reference.name)
        val hysteresis=TrueWindSourceHysteresis(5_000)
        val derived=wind(TrueWindReference.WATER);val external=wind(TrueWindReference.EXTERNAL)
        assertEquals(TrueWindReference.WATER,hysteresis.select(derived,derived,0)?.reference)
        assertEquals(TrueWindReference.WATER,hysteresis.select(external,derived,1_000)?.reference)
        assertEquals(TrueWindReference.WATER,hysteresis.select(external,derived,5_999)?.reference)
        assertEquals(TrueWindReference.EXTERNAL,hysteresis.select(external,derived,6_000)?.reference)
        assertEquals(TrueWindReference.WATER,hysteresis.select(derived,derived,6_100)?.reference)
    }
}
