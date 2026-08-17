package com.yokuli.anchorwatch.runtime.health

import android.content.Context
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class BatteryHealthState(val percent:Int=-1,val low:Boolean=false,val newlyLow:Boolean=false)

class BatteryHealthPolicy(private val warningAt:Int=15,private val recoverAt:Int=20){
    private var low=false
    fun update(percent:Int,active:Boolean):BatteryHealthState{
        if(!active)return BatteryHealthState(percent,low=false)
        val before=low
        if(percent in 0..warningAt)low=true else if(percent>recoverAt)low=false
        return BatteryHealthState(percent,low,low&&!before)
    }
}

@Singleton
class BatteryHealthMonitor @Inject constructor(@ApplicationContext context:Context){
    private val battery=context.getSystemService(BatteryManager::class.java)
    private val policy=BatteryHealthPolicy()
    @Synchronized fun sample(runtimeActive:Boolean)=policy.update(battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),runtimeActive)
}
