package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.vessel.*
import org.junit.Assert.*
import org.junit.Test

class AbsoluteDirectionPolicyTest{
    @Test fun northCrossingUsesShortestRotation(){
        assertEquals(361.0,AbsoluteDirectionPolicy.shortestTarget(359.0,1.0),.001)
        assertEquals(-1.0,AbsoluteDirectionPolicy.shortestTarget(1.0,359.0),.001)
    }

    @Test fun absoluteRoseUsesTrueReferenceOnly(){
        val state=state(trueHeading=47.0,magneticHeading=32.0,cog=52.0,twd=118.0)
        val result=AbsoluteDirectionPolicy.resolve(state,true)
        assertEquals(47.0,result.headingTrueDegrees!!,.001);assertNull(result.headingMagneticDegrees)
        assertEquals(52.0,result.cogTrueDegrees!!,.001);assertEquals(118.0,result.twdFromTrueDegrees!!,.001)
    }

    @Test fun magneticHeadingIsNotOverlaidWithTrueCogTwd(){
        val state=state(trueHeading=null,magneticHeading=32.0,cog=52.0,twd=118.0)
        val result=AbsoluteDirectionPolicy.resolve(state,true)
        assertNull(result.headingTrueDegrees);assertEquals(32.0,result.headingMagneticDegrees!!,.001)
    }

    @Test fun cogSuppressedBelowCourseTrustThreshold(){assertNull(AbsoluteDirectionPolicy.resolve(state(cog=123.0),false).cogTrueDegrees)}

    @Test fun missingTwdDoesNotHideHdgCog(){val result=AbsoluteDirectionPolicy.resolve(state(trueHeading=10.0,cog=20.0,twd=null),true);assertEquals(10.0,result.headingTrueDegrees!!,.001);assertEquals(20.0,result.cogTrueDegrees!!,.001);assertNull(result.twdFromTrueDegrees)}

    @Test fun stalePointerDisappearsIndependently(){
        val data=state(trueHeading=10.0,cog=20.0,twd=30.0).vesselData
        val stale=MainUiState(vesselData=data.copy(trueWind=data.trueWind.copy(directionDegrees=data.trueWind.directionDegrees.copy(freshness=VesselDataFreshness.STALE))))
        val result=AbsoluteDirectionPolicy.resolve(stale,true);assertNotNull(result.headingTrueDegrees);assertNotNull(result.cogTrueDegrees);assertNull(result.twdFromTrueDegrees)
    }

    private fun state(trueHeading:Double?=null,magneticHeading:Double?=null,cog:Double?=null,twd:Double?=null)=MainUiState(vesselData=VesselDataSnapshot(
        headingTrueDegrees=observation(trueHeading,VesselReference.TrueNorth),headingMagneticDegrees=observation(magneticHeading,VesselReference.MagneticNorth),
        cogTrueDegrees=observation(cog,VesselReference.GroundReferenced),trueWind=VesselWindObservation(directionDegrees=observation(twd,VesselReference.TrueNorth)),
    ))
    private fun observation(value:Double?,reference:VesselReference)=VesselObservation(value=value,source=VesselDataSource.BOAT_NMEA,quality=VesselDataQuality.GOOD,freshness=if(value==null)VesselDataFreshness.UNAVAILABLE else VesselDataFreshness.FRESH,reference=reference)
}
