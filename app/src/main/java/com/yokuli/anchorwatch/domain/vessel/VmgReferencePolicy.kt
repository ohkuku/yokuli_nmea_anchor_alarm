package com.yokuli.anchorwatch.domain.vessel

import kotlin.math.cos

data class VmgResult(val knots:Double,val provenance:String)

/** Rejects mixed water/ground reference frames by construction. */
object VmgReferencePolicy{
    fun calculate(stwKnots:Double?,trueWindAngleDegrees:Double?,sogKnots:Double?,courseOverGroundDegrees:Double?,trueWindDirectionDegrees:Double?):VmgResult?{
        if(stwKnots!=null&&trueWindAngleDegrees!=null)return VmgResult(stwKnots*cos(Math.toRadians(trueWindAngleDegrees)),"STW × cos(TWA)")
        if(sogKnots!=null&&courseOverGroundDegrees!=null&&trueWindDirectionDegrees!=null){val angle=((courseOverGroundDegrees-trueWindDirectionDegrees+540.0)%360.0)-180.0;return VmgResult(sogKnots*cos(Math.toRadians(angle)),"SOG × cos(COG−TWD)")}
        return null
    }
}
