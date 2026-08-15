package com.yokuli.anchorwatch.location

import android.os.SystemClock
import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import com.yokuli.anchorwatch.domain.model.AnchorPlacementMode
import com.yokuli.anchorwatch.domain.model.DemoScenario
import com.yokuli.anchorwatch.domain.model.NavigationFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.hypot

data class DemoGpsStatus(
    val running:Boolean=false,
    val paused:Boolean=false,
    val signalAvailable:Boolean=true,
    val scenario:DemoScenario=DemoScenario.SAFE_SWING,
)

@Singleton
class DemoLocationRepository @Inject constructor(){
    private data class Run(
        val originLatitude:Double,
        val originLongitude:Double,
        val placement:AnchorPlacementMode,
        val scenario:DemoScenario,
        val alarmRadiusMeters:Double,
        val speedMultiplier:Int,
        val startedElapsed:Long,
        val pausedElapsed:Long?=null,
        val accumulatedPauseMillis:Long=0L,
        val seed:Long=0L,
    )

    private val guard=Any()
    private val _fix=MutableStateFlow<NavigationFix?>(null);val fix=_fix.asStateFlow()
    private val _status=MutableStateFlow(DemoGpsStatus());val status=_status.asStateFlow()
    private var run:Run?=null

    fun start(originLatitude:Double,originLongitude:Double,placement:AnchorPlacementMode,scenario:DemoScenario,alarmRadiusMeters:Double,speedMultiplier:Int,nowElapsed:Long=SystemClock.elapsedRealtime(),initialElapsedMillis:Long=0L,seed:Long=System.nanoTime()):NavigationFix?=synchronized(guard){
        run=Run(originLatitude,originLongitude,placement,scenario,alarmRadiusMeters,speedMultiplier,nowElapsed-initialElapsedMillis.coerceAtLeast(0L),seed=seed)
        _status.value=DemoGpsStatus(running=true,scenario=scenario)
        tickLocked(nowElapsed)
    }

    fun tick(nowElapsed:Long=SystemClock.elapsedRealtime()):NavigationFix?=synchronized(guard){tickLocked(nowElapsed)}

    fun pause(nowElapsed:Long=SystemClock.elapsedRealtime())=synchronized(guard){
        val current=run?:return@synchronized
        if(current.pausedElapsed==null)run=current.copy(pausedElapsed=nowElapsed)
        _status.value=_status.value.copy(paused=true)
    }

    fun resume(nowElapsed:Long=SystemClock.elapsedRealtime()):NavigationFix?=synchronized(guard){
        val current=run?:return@synchronized null
        val pausedAt=current.pausedElapsed
        if(pausedAt!=null)run=current.copy(pausedElapsed=null,accumulatedPauseMillis=current.accumulatedPauseMillis+(nowElapsed-pausedAt).coerceAtLeast(0L))
        _status.value=_status.value.copy(paused=false)
        tickLocked(nowElapsed)
    }

    fun stop()=synchronized(guard){run=null;_fix.value=null;_status.value=DemoGpsStatus()}

    fun reconfigure(scenario:DemoScenario,alarmRadiusMeters:Double,speedMultiplier:Int)=synchronized(guard){
        val current=run?:return@synchronized
        run=current.copy(scenario=scenario,alarmRadiusMeters=alarmRadiusMeters,speedMultiplier=speedMultiplier)
        _status.value=_status.value.copy(scenario=scenario)
    }

    private fun tickLocked(nowElapsed:Long):NavigationFix?{
        val current=run?:return null
        if(current.pausedElapsed!=null)return _fix.value
        val elapsed=(nowElapsed-current.startedElapsed-current.accumulatedPauseMillis).coerceAtLeast(0L)
        val point=DemoTrajectory.point(elapsed,current.placement,current.scenario,current.alarmRadiusMeters,current.speedMultiplier,current.seed)
        _status.value=_status.value.copy(signalAvailable=point.signalAvailable)
        if(!point.signalAvailable)return null
        val distance=hypot(point.northMeters,point.eastMeters)
        val bearing=(Math.toDegrees(atan2(point.eastMeters,point.northMeters))+360.0)%360.0
        val coordinate=if(distance<.001)current.originLatitude to current.originLongitude else AnchorGeometry.project(current.originLatitude,current.originLongitude,bearing,distance)
        return NavigationFix(latitude=coordinate.first,longitude=coordinate.second,timestampUtcMillis=System.currentTimeMillis(),receivedElapsedRealtime=nowElapsed,sogKnots=point.speedMetersPerSecond*1.943844,cogTrueDegrees=point.headingDegrees,headingTrueDegrees=point.headingDegrees,hdop=.8,fixQuality=1,satellites=12,sourceSentence="DEMO:${current.scenario.name}",valid=true).also{_fix.value=it}
    }
}
