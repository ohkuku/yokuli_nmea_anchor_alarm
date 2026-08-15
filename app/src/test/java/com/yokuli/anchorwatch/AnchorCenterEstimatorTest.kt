package com.yokuli.anchorwatch
import com.yokuli.anchorwatch.domain.anchor.*
import com.yokuli.anchorwatch.domain.model.Confidence
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import kotlin.random.Random
class AnchorCenterEstimatorTest{private fun arc(degrees:Int,n:Int):List<AnchorCenterEstimator.Point>{val r=Random(7);return (0 until n).map{val a=Math.toRadians(degrees*it.toDouble()/(n-1));val radius=40+r.nextDouble(-2.0,2.0);AnchorCenterEstimator.Point(cos(a)*radius/110540,sin(a)*radius/111320)}}
 @Test fun fullCircle(){val x=AnchorCenterEstimator(Random(2)).estimate(arc(360,160),40.0)!!;assertTrue(kotlin.math.hypot(x.latitude*110540,x.longitude*111320)<=5);assertEquals(Confidence.HIGH,x.confidence)}
 @Test fun smallArcNotHigh(){val x=AnchorCenterEstimator(Random(2)).estimate(arc(20,30),40.0)!!;assertNotEquals(Confidence.HIGH,x.confidence)}
 @Test fun sixtyDegreeArcStillCannotFinalizeTheCentre(){val x=AnchorCenterEstimator(Random(2)).estimate(arc(60,60),40.0)!!;assertNotEquals(Confidence.HIGH,x.confidence)}
 @Test fun oneHundredEightyDegreeArcStillCannotFinalizeTheCentre(){val x=AnchorCenterEstimator(Random(2)).estimate(arc(180,180),40.0)!!;assertNotEquals(Confidence.HIGH,x.confidence)}
}
