package com.yokuli.anchorwatch.runtime

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import com.yokuli.anchorwatch.location.SystemLocationRepository
import com.yokuli.anchorwatch.runtime.sensor.SensorRuntime
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RuntimeOwner { ANCHOR_WATCH, ANCHOR_TELEMETRY, CONDITION_MONITOR, NMEA_SHARING, GPS_PROXY, SONAR_MAPPING, PHONE_NMEA_OUTPUT, VESSEL_HUB_UI, TRIP_WATCH }
data class RuntimeRequirement(
    val needsSystemLocation:Boolean=false,
    val needsNmeaTransport:Boolean=false,
    val needsWakeLock:Boolean=false,
    val needsWifiLock:Boolean=false,
    val needsPhoneMotion:Boolean=false,
    val needsPhoneHeading:Boolean=false,
    val needsPhonePressure:Boolean=false,
)
data class RuntimeResourceSnapshot(
    val owners:Set<RuntimeOwner> = emptySet(),
    /** Per-owner requirements keep user-facing dependency reviews honest. A
     * feature that merely runs is not necessarily an NMEA transport owner. */
    val ownerRequirements:Map<RuntimeOwner,RuntimeRequirement> = emptyMap(),
    val needsSystemLocation:Boolean=false,
    val needsNmeaTransport:Boolean=false,
    val needsWakeLock:Boolean=false,
    val needsWifiLock:Boolean=false,
    val needsPhoneMotion:Boolean=false,
    val needsPhoneHeading:Boolean=false,
    val needsPhonePressure:Boolean=false,
    val wakeLockHeld:Boolean=false,
    val wifiLockHeld:Boolean=false,
    val phoneMotionActive:Boolean=false,
    val phoneHeadingActive:Boolean=false,
    val phonePressureActive:Boolean=false,
){
    val nmeaOwners:Set<RuntimeOwner> get()=ownerRequirements.filterValues{it.needsNmeaTransport}.keys
}

/** Pure owner aggregation used by both the Android manager and soak tests. */
class RuntimeOwnerRegistry {
    private val requirements=linkedMapOf<RuntimeOwner,RuntimeRequirement>()
    @Synchronized fun set(owner:RuntimeOwner,requirement:RuntimeRequirement?){if(requirement==null)requirements.remove(owner)else requirements[owner]=requirement}
    @Synchronized fun clear(){requirements.clear()}
    /** Applies the user preference to every currently running network owner.
     * NMEA Sharing also needs Wi-Fi while serving System/Demo position even
     * though it does not own the upstream NMEA transport in those modes. */
    @Synchronized fun updateKeepWifiAwake(enabled:Boolean){
        requirements.keys.toList().forEach{owner->
            val requirement=requirements.getValue(owner)
            val networkOwner=requirement.needsNmeaTransport||owner==RuntimeOwner.NMEA_SHARING
            requirements[owner]=requirement.copy(needsWifiLock=enabled&&networkOwner)
        }
    }
    @Synchronized fun snapshot():RuntimeResourceSnapshot{
        val values=requirements.values
        return RuntimeResourceSnapshot(
            owners=requirements.keys.toSet(),
            ownerRequirements=requirements.toMap(),
            needsSystemLocation=values.any{it.needsSystemLocation},
            needsNmeaTransport=values.any{it.needsNmeaTransport},
            needsWakeLock=values.any{it.needsWakeLock},
            needsWifiLock=values.any{it.needsWifiLock},
            needsPhoneMotion=values.any{it.needsPhoneMotion},
            needsPhoneHeading=values.any{it.needsPhoneHeading},
            needsPhonePressure=values.any{it.needsPhonePressure},
        )
    }
}

/** The sole owner of process-wide power, Wi-Fi and phone-sensor resources. */
@Singleton
class RuntimeResourceManager @Inject constructor(
    @ApplicationContext context:Context,
    private val systemLocation:SystemLocationRepository,
    private val sensors:SensorRuntime,
){
    private val power=context.getSystemService(PowerManager::class.java)
    private val wifiManager=context.applicationContext.getSystemService(WifiManager::class.java)
    private val registry=RuntimeOwnerRegistry()
    private var wakeLock:PowerManager.WakeLock?=null
    private var wifiLock:WifiManager.WifiLock?=null
    private val _state=MutableStateFlow(RuntimeResourceSnapshot())
    val state=_state.asStateFlow()

    @Synchronized fun set(owner:RuntimeOwner,requirement:RuntimeRequirement?){registry.set(owner,requirement);reconcile()}
    @Synchronized fun release(owner:RuntimeOwner){set(owner,null)}
    @Synchronized fun releaseAll(){registry.clear();reconcile()}
    @Synchronized fun updateKeepWifiAwake(enabled:Boolean){registry.updateKeepWifiAwake(enabled);reconcile()}
    @Synchronized fun snapshot()=_state.value

    private fun reconcile(){
        val wanted=registry.snapshot()
        if(wanted.needsWakeLock){if(wakeLock?.isHeld!=true)wakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"anchorwatch:runtime").apply{setReferenceCounted(false);acquire()}}
        else{wakeLock?.takeIf{it.isHeld}?.release();wakeLock=null}
        if(wanted.needsWifiLock){if(wifiLock?.isHeld!=true){val mode=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q)WifiManager.WIFI_MODE_FULL_LOW_LATENCY else 3;wifiLock=wifiManager.createWifiLock(mode,"anchorwatch:nmea-runtime").apply{setReferenceCounted(false);acquire()}}}
        else{wifiLock?.takeIf{it.isHeld}?.release();wifiLock=null}
        systemLocation.setBackgroundEnabled(wanted.needsSystemLocation)
        val sensorState=sensors.reconcile(wanted.needsPhoneMotion,wanted.needsPhoneHeading,wanted.needsPhonePressure)
        _state.value=wanted.copy(wakeLockHeld=wakeLock?.isHeld==true,wifiLockHeld=wifiLock?.isHeld==true,phoneMotionActive=sensorState.phoneMotionActive,phoneHeadingActive=sensorState.phoneHeadingActive,phonePressureActive=sensorState.phonePressureActive)
    }
}
