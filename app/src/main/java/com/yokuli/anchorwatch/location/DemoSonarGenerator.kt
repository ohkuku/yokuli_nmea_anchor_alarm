package com.yokuli.anchorwatch.location

import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.sonar.DepthObservation
import com.yokuli.anchorwatch.domain.sonar.DepthReference
import com.yokuli.anchorwatch.domain.sonar.DepthSentenceType
import com.yokuli.anchorwatch.domain.sonar.SonarGrid
import kotlin.math.cos
import kotlin.math.sin

/** Smooth seeded seabed used only by Developer Demo mode. */
class DemoSonarGenerator(private val seed:Long){
    private var origin:Pair<Double,Double>?=null
    fun observation(fix:NavigationFix,elapsedRealtime:Long):DepthObservation{
        val projected=SonarGrid.project(fix.latitude,fix.longitude);val base=origin?:projected.also{origin=it};val dx=projected.first-base.first;val dy=projected.second-base.second
        val slow=sin(dx/34.0+seed%29*.07)*1.15+cos(dy/27.0-seed%17*.09)*.85;val ripple=sin((dx+dy)/8.0+elapsedRealtime/13_000.0)*.16;val depth=(9.0+slow+ripple).coerceIn(2.0,30.0)
        return DepthObservation(depth,null,DepthReference.BELOW_SURFACE,DepthSentenceType.DPT,elapsedRealtime,"DEMO_DPT")
    }
}
