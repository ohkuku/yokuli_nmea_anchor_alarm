package com.yokuli.anchorwatch.location

import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.sonar.DepthObservation
import com.yokuli.anchorwatch.domain.sonar.DepthReference
import com.yokuli.anchorwatch.domain.sonar.DepthSentenceType
import com.yokuli.anchorwatch.domain.sonar.SonarGrid
import com.yokuli.anchorwatch.domain.model.DemoScenario
import kotlin.math.cos
import kotlin.math.sin

/** Smooth seeded seabed used only by Developer Demo mode. */
class DemoSonarGenerator(private val seed:Long,private val scenario:DemoScenario=DemoScenario.SAFE_SWING){
    private var origin:Pair<Double,Double>?=null
    private var startedElapsed:Long?=null
    fun observation(fix:NavigationFix,elapsedRealtime:Long):DepthObservation{
        val projected=SonarGrid.project(fix.latitude,fix.longitude);val base=origin?:projected.also{origin=it};val dx=projected.first-base.first;val dy=projected.second-base.second
        val start=startedElapsed?:elapsedRealtime.also{startedElapsed=it};val seconds=(elapsedRealtime-start).coerceAtLeast(0L)/1_000.0
        val slow=sin(dx/34.0+seed%29*.07)*1.15+cos(dy/27.0-seed%17*.09)*.85;val ripple=sin((dx+dy)/8.0+elapsedRealtime/13_000.0)*.16
        val normal=(9.0+slow+ripple).coerceIn(4.0,18.0)
        val depth=when(scenario){
            DemoScenario.DEPTH_SHALLOW->conditionDepth(seconds,normal,1.5)
            DemoScenario.DEPTH_DEEP->conditionDepth(seconds,normal,20.0)
            else->normal
        }
        return DepthObservation(depth,null,DepthReference.BELOW_SURFACE,DepthSentenceType.DPT,elapsedRealtime,"DEMO_DPT")
    }
    private fun conditionDepth(seconds:Double,normal:Double,target:Double):Double=when{
        seconds<25.0->normal
        seconds<45.0->normal+(target-normal)*smoothStep((seconds-25.0)/20.0)
        seconds<80.0->target
        seconds<100.0->target+(normal-target)*smoothStep((seconds-80.0)/20.0)
        else->normal
    }
    private fun smoothStep(value:Double):Double{val safe=value.coerceIn(0.0,1.0);return safe*safe*(3.0-2.0*safe)}
}
