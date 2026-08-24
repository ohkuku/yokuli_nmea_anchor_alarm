package com.yokuli.anchorwatch.location.vessel

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.yokuli.anchorwatch.domain.vessel.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class DeviceBowAxis { TOP, BOTTOM, LEFT, RIGHT }
enum class PhoneVesselMountState { HANDHELD, VESSEL_MOUNTED, MOUNT_SUSPECT, UNCALIBRATED }
data class SensorQuaternion(val w:Double,val x:Double,val y:Double,val z:Double){
    fun inverse()=SensorQuaternion(w,-x,-y,-z)
    operator fun times(other:SensorQuaternion)=SensorQuaternion(w*other.w-x*other.x-y*other.y-z*other.z,w*other.x+x*other.w+y*other.z-z*other.y,w*other.y-x*other.z+y*other.w+z*other.x,w*other.z+x*other.y-y*other.x+z*other.w)
    fun normalized():SensorQuaternion{val n=sqrt(w*w+x*x+y*y+z*z).coerceAtLeast(1e-9);return SensorQuaternion(w/n,x/n,y/n,z/n)}
}
data class VesselMountCalibration(val version:Int=1,val bowAxis:DeviceBowAxis=DeviceBowAxis.TOP,val neutralQuaternion:SensorQuaternion=SensorQuaternion(1.0,0.0,0.0,0.0),val calibratedAt:Long=0,val mountState:PhoneVesselMountState=PhoneVesselMountState.UNCALIBRATED,val headingAlignmentOffsetDegrees:Double=0.0,val automaticMountRecovery:Boolean=true)
data class PhoneSensorCapabilities(val attitudeAvailable:Boolean=false,val gyroAvailable:Boolean=false,val magnetometerAvailable:Boolean=false,val pressureAvailable:Boolean=false,val linearAccelerationAvailable:Boolean=false)
data class PhoneVesselAttitudeSample(val attitude:VesselAttitude?=null,val dynamicAccelerationG:Double=0.0,val mountSuspect:Boolean=false,val receivedElapsedRealtime:Long?=null)
data class PhonePressureSample(val pressureHpa:Double?=null,val receivedElapsedRealtime:Long?=null)

private val Context.mountStore by preferencesDataStore("vessel_mount_calibration")

@Singleton
class VesselMountCalibrationRepository @Inject constructor(@ApplicationContext private val context:Context){
    private object K{val version=intPreferencesKey("calibration_version");val axis=stringPreferencesKey("bow_axis");val w=doublePreferencesKey("neutral_w");val x=doublePreferencesKey("neutral_x");val y=doublePreferencesKey("neutral_y");val z=doublePreferencesKey("neutral_z");val at=longPreferencesKey("calibrated_at");val mount=stringPreferencesKey("mount_state");val headingOffset=doublePreferencesKey("heading_alignment_offset");val automaticRecovery=booleanPreferencesKey("automatic_mount_recovery")}
    val calibration=context.mountStore.data.map{p->val at=p[K.at]?:0;VesselMountCalibration(version=p[K.version]?:1,bowAxis=p[K.axis]?.let{runCatching{DeviceBowAxis.valueOf(it)}.getOrNull()}?:DeviceBowAxis.TOP,neutralQuaternion=SensorQuaternion(p[K.w]?:1.0,p[K.x]?:0.0,p[K.y]?:0.0,p[K.z]?:0.0).normalized(),calibratedAt=at,mountState=p[K.mount]?.let{runCatching{PhoneVesselMountState.valueOf(it)}.getOrNull()}?:if(at>0)PhoneVesselMountState.HANDHELD else PhoneVesselMountState.UNCALIBRATED,headingAlignmentOffsetDegrees=p[K.headingOffset]?:0.0,automaticMountRecovery=p[K.automaticRecovery]?:true)}
    suspend fun save(axis:DeviceBowAxis,q:SensorQuaternion){context.mountStore.edit{p->p[K.version]=(p[K.version]?:0)+1;p[K.axis]=axis.name;p[K.w]=q.w;p[K.x]=q.x;p[K.y]=q.y;p[K.z]=q.z;p[K.at]=System.currentTimeMillis();p[K.mount]=PhoneVesselMountState.VESSEL_MOUNTED.name}}
    suspend fun setMountState(value:PhoneVesselMountState)=context.mountStore.edit{it[K.mount]=value.name}
    suspend fun setHeadingAlignment(offsetDegrees:Double)=context.mountStore.edit{it[K.headingOffset]=((offsetDegrees+540.0)%360.0)-180.0}
    suspend fun setAutomaticRecovery(enabled:Boolean)=context.mountStore.edit{it[K.automaticRecovery]=enabled}
    suspend fun restore(value:VesselMountCalibration){
        val normalized=value.neutralQuaternion.normalized()
        context.mountStore.edit{p->
            p[K.version]=value.version.coerceAtLeast(1)
            p[K.axis]=value.bowAxis.name
            p[K.w]=normalized.w;p[K.x]=normalized.x;p[K.y]=normalized.y;p[K.z]=normalized.z
            p[K.at]=value.calibratedAt.coerceAtLeast(0L);p[K.mount]=value.mountState.name;p[K.headingOffset]=value.headingAlignmentOffsetDegrees;p[K.automaticRecovery]=value.automaticMountRecovery
        }
    }
}

@Singleton
class PhoneVesselAttitudeRepository @Inject constructor(@ApplicationContext context:Context,private val calibrationRepository:VesselMountCalibrationRepository):SensorEventListener{
    private val manager=context.getSystemService(SensorManager::class.java)
    private val rotation=manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?:manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val gyro=manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val linear=manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val magnetometer=manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    val capabilities=PhoneSensorCapabilities(rotation!=null,gyro!=null,magnetometer!=null,manager.getDefaultSensor(Sensor.TYPE_PRESSURE)!=null,linear!=null)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val _sample=MutableStateFlow(PhoneVesselAttitudeSample());val sample=_sample.asStateFlow()
    private val _mountState=MutableStateFlow(PhoneVesselMountState.UNCALIBRATED);val mountState=_mountState.asStateFlow()
    @Volatile private var calibration=VesselMountCalibration();private var currentQuaternion:SensorQuaternion?=null;private var gyroValues=DoubleArray(3);private var dynamicG=0.0;private var running=false;private var previousQuaternion:SensorQuaternion?=null;private var stableSince:Long?=null
    init{scope.launch{calibrationRepository.calibration.collect{calibration=it;if(_mountState.value!=PhoneVesselMountState.MOUNT_SUSPECT)_mountState.value=if(it.calibratedAt<=0)PhoneVesselMountState.UNCALIBRATED else it.mountState}}}
    @Synchronized fun start():Boolean{if(running)return capabilities.attitudeAvailable;val a=rotation?.let{manager.registerListener(this,it,SensorManager.SENSOR_DELAY_GAME)}?:false;if(a){gyro?.let{manager.registerListener(this,it,SensorManager.SENSOR_DELAY_GAME)};linear?.let{manager.registerListener(this,it,SensorManager.SENSOR_DELAY_GAME)}};running=a;return running}
    @Synchronized fun stop(){if(running)manager.unregisterListener(this);running=false;previousQuaternion=null;_sample.value=PhoneVesselAttitudeSample()}
    suspend fun calibrate(axis:DeviceBowAxis):Boolean{val q=currentQuaternion?:return false;calibrationRepository.save(axis,q);return true}
    suspend fun setMounted(mounted:Boolean){calibrationRepository.setMountState(if(mounted)PhoneVesselMountState.VESSEL_MOUNTED else PhoneVesselMountState.HANDHELD)}
    suspend fun alignHeading(offsetDegrees:Double)=calibrationRepository.setHeadingAlignment(offsetDegrees)
    override fun onSensorChanged(event:SensorEvent){when(event.sensor.type){Sensor.TYPE_GYROSCOPE->{gyroValues=doubleArrayOf(event.values[0].toDouble(),event.values[1].toDouble(),event.values[2].toDouble())};Sensor.TYPE_LINEAR_ACCELERATION->{dynamicG=sqrt(event.values.take(3).sumOf{it.toDouble()*it.toDouble()})/SensorManager.GRAVITY_EARTH};Sensor.TYPE_ROTATION_VECTOR,Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR->{val values=FloatArray(4);SensorManager.getQuaternionFromVector(values,event.values);val current=SensorQuaternion(values[0].toDouble(),values[1].toDouble(),values[2].toDouble(),values[3].toDouble()).normalized();currentQuaternion=current;publish(current)}}}
    private fun publish(current:SensorQuaternion){
        val now=SystemClock.elapsedRealtime()
        // Keep currentQuaternion available so the user can calibrate, but never
        // expose the Android device frame as if it were a vessel frame.
        if(calibration.calibratedAt<=0L){previousQuaternion=current;_mountState.value=PhoneVesselMountState.UNCALIBRATED;_sample.value=PhoneVesselAttitudeSample(receivedElapsedRealtime=now);return}
        val corrected=(calibration.neutralQuaternion.inverse()*current*axisCorrection(calibration.bowAxis)).normalized();val sinRoll=2*(corrected.w*corrected.x+corrected.y*corrected.z);val cosRoll=1-2*(corrected.x*corrected.x+corrected.y*corrected.y);val roll=Math.toDegrees(atan2(sinRoll,cosRoll));val sinPitch=2*(corrected.w*corrected.y-corrected.z*corrected.x);val pitch=Math.toDegrees(if(abs(sinPitch)>=1)if(sinPitch>=0)Math.PI/2 else -Math.PI/2 else asin(sinPitch));val rates=mapRates(gyroValues,calibration.bowAxis);val previous=previousQuaternion;previousQuaternion=current;val jump=previous?.let{old->2*Math.toDegrees(acos(abs(old.w*current.w+old.x*current.x+old.y*current.y+old.z*current.z).coerceIn(0.0,1.0)))>35.0}?:false;val angular=sqrt(gyroValues.sumOf{it*it});val suspect=jump||angular>3.5
        val configuredMounted=calibration.mountState==PhoneVesselMountState.VESSEL_MOUNTED
        if(configuredMounted&&suspect){_mountState.value=PhoneVesselMountState.MOUNT_SUSPECT;stableSince=null}
        else if(_mountState.value==PhoneVesselMountState.MOUNT_SUSPECT){if(!suspect&&abs(roll)<20&&abs(pitch)<20){if(stableSince==null)stableSince=now;if(calibration.automaticMountRecovery&&now-(stableSince?:now)>=7_000L)_mountState.value=PhoneVesselMountState.VESSEL_MOUNTED}else stableSince=null}
        else _mountState.value=if(configuredMounted)PhoneVesselMountState.VESSEL_MOUNTED else PhoneVesselMountState.HANDHELD
        val vesselFrame=_mountState.value==PhoneVesselMountState.VESSEL_MOUNTED
        _sample.value=if(vesselFrame)PhoneVesselAttitudeSample(VesselAttitude(roll,pitch,Math.toDegrees(rates[0]),Math.toDegrees(rates[1]),Math.toDegrees(rates[2])),dynamicG,false,now)else PhoneVesselAttitudeSample(dynamicAccelerationG=dynamicG,mountSuspect=_mountState.value==PhoneVesselMountState.MOUNT_SUSPECT,receivedElapsedRealtime=now)
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int)=Unit
    private fun axisCorrection(axis:DeviceBowAxis):SensorQuaternion{val angle=when(axis){DeviceBowAxis.TOP->0.0;DeviceBowAxis.RIGHT->-Math.PI/2;DeviceBowAxis.BOTTOM->Math.PI;DeviceBowAxis.LEFT->Math.PI/2};return SensorQuaternion(cos(angle/2),0.0,0.0,sin(angle/2))}
    private fun mapRates(v:DoubleArray,axis:DeviceBowAxis)=when(axis){DeviceBowAxis.TOP->doubleArrayOf(v[0],v[1],v[2]);DeviceBowAxis.BOTTOM->doubleArrayOf(-v[0],-v[1],v[2]);DeviceBowAxis.LEFT->doubleArrayOf(-v[1],v[0],v[2]);DeviceBowAxis.RIGHT->doubleArrayOf(v[1],-v[0],v[2])}
}

@Singleton
class PhonePressureRepository @Inject constructor(@ApplicationContext context:Context):SensorEventListener{
    private val manager=context.getSystemService(SensorManager::class.java);private val sensor=manager.getDefaultSensor(Sensor.TYPE_PRESSURE);private val _sample=MutableStateFlow(PhonePressureSample());val sample=_sample.asStateFlow();private var running=false
    fun start():Boolean{if(running)return sensor!=null;running=sensor?.let{manager.registerListener(this,it,SensorManager.SENSOR_DELAY_NORMAL)}?:false;return running}
    fun stop(){if(running)manager.unregisterListener(this);running=false;_sample.value=PhonePressureSample()}
    override fun onSensorChanged(event:SensorEvent){event.values.firstOrNull()?.takeIf{it in 800f..1_200f}?.let{_sample.value=PhonePressureSample(it.toDouble(),SystemClock.elapsedRealtime())}}
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int)=Unit
}
