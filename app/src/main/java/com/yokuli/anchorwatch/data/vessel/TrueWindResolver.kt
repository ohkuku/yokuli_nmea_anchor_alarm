package com.yokuli.anchorwatch.data.vessel

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class TrueWindReference { EXTERNAL, WATER, GROUND }
data class ResolvedTrueWind(
    val speedKnots:Double?,
    val directionTrueDegrees:Double?,
    val angleDegrees:Double?,
    val reference:TrueWindReference,
    val provenance:String,
    val speedProvenance:String=provenance,
    val directionProvenance:String=provenance,
    val angleProvenance:String=provenance,
)

/** Prevents a recovering external wind source from replacing a healthy
 * derived solution until it has stayed available for the configured window.
 * Loss of the external source still falls back immediately. */
class TrueWindSourceHysteresis(private val externalRecoveryMillis:Long=5_000L){
    private var activeReference:TrueWindReference?=null
    private var recoverySince:Long?=null
    fun select(preferred:ResolvedTrueWind?,derivedFallback:ResolvedTrueWind?,nowElapsed:Long):ResolvedTrueWind?{
        if(preferred==null){activeReference=derivedFallback?.reference;recoverySince=null;return derivedFallback}
        if(preferred.reference!=TrueWindReference.EXTERNAL){activeReference=preferred.reference;recoverySince=null;return preferred}
        val derivedActive=activeReference in setOf(TrueWindReference.WATER,TrueWindReference.GROUND)
        if(!derivedActive||derivedFallback==null){activeReference=TrueWindReference.EXTERNAL;recoverySince=null;return preferred}
        if(recoverySince==null)recoverySince=nowElapsed
        if(nowElapsed-(recoverySince?:nowElapsed)<externalRecoveryMillis)return derivedFallback
        activeReference=TrueWindReference.EXTERNAL;recoverySince=null;return preferred
    }
    fun reset(){activeReference=null;recoverySince=null}
}

/** Presentation-only true-wind arbitration. Derived values never become an
 * Anchor safety guard until a separately reviewed safety policy opts in. */
object TrueWindResolver{
    fun resolve(
        apparentSpeedKnots:Double?,apparentAngleDegrees:Double?,
        externalSpeedKnots:Double?,externalDirectionTrueDegrees:Double?,externalAngleDegrees:Double?,
        speedThroughWaterKnots:Double?,headingTrueDegrees:Double?,
        speedOverGroundKnots:Double?,courseOverGroundTrueDegrees:Double?,
        apparentSpeedReceivedElapsed:Long?=null,apparentAngleReceivedElapsed:Long?=null,
        externalSpeedReceivedElapsed:Long?=null,externalDirectionReceivedElapsed:Long?=null,externalAngleReceivedElapsed:Long?=null,
        speedThroughWaterReceivedElapsed:Long?=null,headingReceivedElapsed:Long?=null,speedOverGroundReceivedElapsed:Long?=null,courseOverGroundReceivedElapsed:Long?=null,
    ):ResolvedTrueWind?{
        val heading=headingTrueDegrees?.normalized()
        val directDirection=externalDirectionTrueDegrees?.takeIf{coherent(externalSpeedReceivedElapsed,externalDirectionReceivedElapsed)}?.normalized()
        val directAngle=externalAngleDegrees?.takeIf{coherent(externalSpeedReceivedElapsed,externalAngleReceivedElapsed)}?.let(::signed)
        if(externalSpeedKnots!=null&&(directDirection!=null||directAngle!=null)){
            val derivedDirection=if(directDirection==null&&directAngle!=null&&heading!=null&&coherent(externalSpeedReceivedElapsed,externalAngleReceivedElapsed,headingReceivedElapsed))(heading+directAngle).normalized()else null
            val derivedAngle=if(directAngle==null&&directDirection!=null&&heading!=null&&coherent(externalSpeedReceivedElapsed,externalDirectionReceivedElapsed,headingReceivedElapsed))signed(directDirection-heading)else null
            return ResolvedTrueWind(
                externalSpeedKnots.coerceAtLeast(0.0),directDirection?:derivedDirection,directAngle?:derivedAngle,
                TrueWindReference.EXTERNAL,"external NMEA true wind",speedProvenance="external true-wind speed",
                directionProvenance=if(directDirection!=null)"external true-wind direction" else "external true-wind angle + selected heading",
                angleProvenance=if(directAngle!=null)"external true-wind angle" else "external true-wind direction + selected heading",
            )
        }
        val aws=apparentSpeedKnots?:return null;val awa=apparentAngleDegrees?:return null;val h=heading?:return null
        if(!coherent(apparentSpeedReceivedElapsed,apparentAngleReceivedElapsed,headingReceivedElapsed))return null
        val apparentFrom=(h+signed(awa)).normalized()
        val apparentTo=(apparentFrom+180.0).normalized()
        val apparentEast=aws*sin(Math.toRadians(apparentTo));val apparentNorth=aws*cos(Math.toRadians(apparentTo))
        val water=speedThroughWaterKnots?.takeIf{coherent(apparentSpeedReceivedElapsed,apparentAngleReceivedElapsed,speedThroughWaterReceivedElapsed,headingReceivedElapsed)}?.let{speed->derive(apparentEast,apparentNorth,speed,h,h,TrueWindReference.WATER,"derived from AWA/AWS + STW/HDT (water reference)")}
        if(water!=null)return water
        return if(speedOverGroundKnots!=null&&courseOverGroundTrueDegrees!=null&&coherent(apparentSpeedReceivedElapsed,apparentAngleReceivedElapsed,speedOverGroundReceivedElapsed,courseOverGroundReceivedElapsed,headingReceivedElapsed))derive(apparentEast,apparentNorth,speedOverGroundKnots,courseOverGroundTrueDegrees,h,TrueWindReference.GROUND,"derived from AWA/AWS + SOG/COG/HDT (ground fallback)") else null
    }
    private fun derive(apparentEast:Double,apparentNorth:Double,vesselSpeed:Double,vesselDirection:Double,heading:Double,reference:TrueWindReference,provenance:String):ResolvedTrueWind{
        val east=apparentEast+vesselSpeed*sin(Math.toRadians(vesselDirection));val north=apparentNorth+vesselSpeed*cos(Math.toRadians(vesselDirection));val speed=hypot(east,north);val to=(Math.toDegrees(atan2(east,north))+360.0)%360.0;val from=(to+180.0)%360.0
        return ResolvedTrueWind(speed,from,signed(from-heading),reference,provenance)
    }
    private fun Double.normalized()=(this%360.0+360.0)%360.0
    private fun signed(value:Double):Double{val normalized=((value+540.0)%360.0)-180.0;return if(normalized==-180.0&&value>0)180.0 else normalized}
    private fun coherent(vararg timestamps:Long?):Boolean{val values=timestamps.filterNotNull();return values.size<2||values.max()-values.min()<=MAX_INPUT_SKEW_MILLIS}
    private const val MAX_INPUT_SKEW_MILLIS=2_000L
}
