package com.yokuli.anchorwatch.location

import com.yokuli.anchorwatch.domain.model.AnchorPlacementMode
import com.yokuli.anchorwatch.domain.model.DemoScenario
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class DemoTrajectoryPoint(
    val northMeters: Double,
    val eastMeters: Double,
    val headingDegrees: Double,
    val speedMetersPerSecond: Double,
    val signalAvailable: Boolean = true,
)

object DemoTrajectory {
    fun point(
        elapsedMillis: Long,
        placement: AnchorPlacementMode,
        scenario: DemoScenario,
        alarmRadiusMeters: Double,
        speedMultiplier: Int,
    ): DemoTrajectoryPoint {
        val seconds=elapsedMillis.coerceAtLeast(0L)/1_000.0
        val speed=speedMultiplier.takeIf{it in listOf(1,2,5)}?.toDouble()?:1.0
        return when(scenario){
            DemoScenario.SAFE_SWING->safeSwing(seconds,placement,alarmRadiusMeters,speed)
            DemoScenario.ANCHOR_DRAG->anchorDrag(seconds,placement,speed)
            DemoScenario.WIND_SHIFT->windShift(seconds,placement,alarmRadiusMeters,speed)
            DemoScenario.GPS_DROPOUT->if(seconds>=25.0)safeSwing(25.0,placement,alarmRadiusMeters,speed).copy(signalAvailable=false)else safeSwing(seconds,placement,alarmRadiusMeters,speed)
        }
    }

    private fun safeSwing(seconds:Double,placement:AnchorPlacementMode,alarmRadius:Double,speed:Double):DemoTrajectoryPoint{
        val radius=(alarmRadius*.55).coerceIn(12.0,60.0)
        if(placement==AnchorPlacementMode.BACKDOWN){
            initialBackdown(seconds,radius,speed)?.let{return it}
            val travelSeconds=radius/(.9*speed);val angle=PI+(seconds-8.0-travelSeconds)*.035*speed
            return DemoTrajectoryPoint(radius*cos(angle),radius*sin(angle),(Math.toDegrees(angle)+90.0)%360.0,radius*.035*speed)
        }
        val currentRadius=radius*(seconds/12.0).coerceIn(0.0,1.0);val angle=seconds*.035*speed
        return DemoTrajectoryPoint(currentRadius*cos(angle),currentRadius*sin(angle),(Math.toDegrees(angle)+90.0)%360.0,currentRadius*.035*speed)
    }

    private fun anchorDrag(seconds:Double,placement:AnchorPlacementMode,speed:Double):DemoTrajectoryPoint{
        if(placement==AnchorPlacementMode.BACKDOWN&&seconds<8.0)return stableDrop(seconds)
        val movingSeconds=if(placement==AnchorPlacementMode.BACKDOWN)seconds-8.0 else (seconds-3.0).coerceAtLeast(0.0)
        val distance=movingSeconds*1.0*speed
        return DemoTrajectoryPoint(-distance,sin(seconds*.15)*1.5,180.0,1.0*speed)
    }

    private fun windShift(seconds:Double,placement:AnchorPlacementMode,alarmRadius:Double,speed:Double):DemoTrajectoryPoint{
        val radius=(alarmRadius*.65).coerceIn(15.0,70.0)
        if(placement==AnchorPlacementMode.BACKDOWN){
            initialBackdown(seconds,radius,speed)?.let{return it}
            val travelSeconds=radius/(.9*speed);val phase=(seconds-8.0-travelSeconds)*.045*speed
            val angle=PI+sin(phase)*1.25;val changingRadius=radius*(.72+.22*sin(phase*.43))
            return DemoTrajectoryPoint(changingRadius*cos(angle),changingRadius*sin(angle),(Math.toDegrees(angle)+90.0)%360.0,.75*speed)
        }
        val phase=seconds*.045*speed;val changingRadius=radius*(.65+.25*sin(phase*.37));val angle=phase+sin(phase*.28)*.9
        return DemoTrajectoryPoint(changingRadius*cos(angle),changingRadius*.7*sin(angle),(Math.toDegrees(angle)+90.0)%360.0,.65*speed)
    }

    private fun initialBackdown(seconds:Double,targetRadius:Double,speed:Double):DemoTrajectoryPoint?{
        if(seconds<8.0)return stableDrop(seconds)
        val distance=(seconds-8.0)*.9*speed
        if(distance>=targetRadius)return null
        return DemoTrajectoryPoint(-distance,sin(seconds*.45)*.35,180.0,.9*speed)
    }

    private fun stableDrop(seconds:Double)=DemoTrajectoryPoint(sin(seconds*1.7)*.25,cos(seconds*1.3)*.25,180.0,.05)
}
