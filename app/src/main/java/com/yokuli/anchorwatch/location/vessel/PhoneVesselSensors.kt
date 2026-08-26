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
data class VesselMountCalibration(
    val version:Int=1,
    val bowAxis:DeviceBowAxis=DeviceBowAxis.TOP,
    val neutralQuaternion:SensorQuaternion=SensorQuaternion(1.0,0.0,0.0,0.0),
    val calibratedAt:Long=0,
    val mountState:PhoneVesselMountState=PhoneVesselMountState.UNCALIBRATED,
    val headingAlignmentOffsetDegrees:Double=0.0,
    /** Legacy backup field. Automatic recovery is deliberately disabled: an
     * invalidated Trip attitude segment always needs a new user confirmation. */
    val automaticMountRecovery:Boolean=false,
    val headingAlignmentCompletedAt:Long=0,
    val mountConfirmedVersion:Int=0,
    val headingAlignmentVersion:Int=0,
    val attitudeInvalidatedAt:Long=0,
){
    val attitudeFrameConfirmed:Boolean get()=calibratedAt>attitudeInvalidatedAt
    val mountConfirmed:Boolean get()=attitudeFrameConfirmed&&mountState==PhoneVesselMountState.VESSEL_MOUNTED&&mountConfirmedVersion==version
    /** Heading alignment is a separate, durable coordinate relationship. A
     * later Trip attitude-frame confirmation must not erase or stale it. */
    val headingAligned:Boolean get()=headingAlignmentCompletedAt>0L
}
enum class PhoneVesselOutputBlocker{VESSEL_ZERO_REQUIRED,MOUNT_CONFIRMATION_REQUIRED,HEADING_ALIGNMENT_REQUIRED,MOUNT_SUSPECT}
data class PhoneVesselOutputReadiness(val ready:Boolean,val blockers:Set<PhoneVesselOutputBlocker>)
object PhoneVesselOutputReadinessPolicy{
    fun evaluate(calibration:VesselMountCalibration,@Suppress("UNUSED_PARAMETER") runtimeMountState:PhoneVesselMountState):PhoneVesselOutputReadiness{
        val blockers=buildSet{
            if(!calibration.headingAligned)add(PhoneVesselOutputBlocker.HEADING_ALIGNMENT_REQUIRED)
        }
        return PhoneVesselOutputReadiness(blockers.isEmpty(),blockers)
    }
}
enum class PhoneHeadingAlignmentReference{TRUE_NORTH,MAGNETIC_NORTH}
data class PhoneHeadingAlignmentMatch(val offsetDegrees:Double,val reference:PhoneHeadingAlignmentReference)

/** Keeps the persisted angular correction as an implementation detail. The
 * user either points the phone toward the bow (zero correction), or asks the
 * App to match two simultaneous headings with the same north reference. */
object PhoneHeadingAlignmentPolicy{
    fun matchLiveReference(
        phoneTrueDegrees:Double?,
        phoneMagneticDegrees:Double?,
        vesselTrueDegrees:Double?,
        vesselMagneticDegrees:Double?,
    ):PhoneHeadingAlignmentMatch?=when{
        phoneTrueDegrees.isHeading()&&vesselTrueDegrees.isHeading()->PhoneHeadingAlignmentMatch(shortestOffset(vesselTrueDegrees!!,phoneTrueDegrees!!),PhoneHeadingAlignmentReference.TRUE_NORTH)
        phoneMagneticDegrees.isHeading()&&vesselMagneticDegrees.isHeading()->PhoneHeadingAlignmentMatch(shortestOffset(vesselMagneticDegrees!!,phoneMagneticDegrees!!),PhoneHeadingAlignmentReference.MAGNETIC_NORTH)
        else->null
    }

    fun shortestOffset(referenceDegrees:Double,phoneDegrees:Double):Double{
        require(referenceDegrees.isHeading()&&phoneDegrees.isHeading())
        return ((referenceDegrees-phoneDegrees+540.0)%360.0)-180.0
    }

    private fun Double?.isHeading()=this!=null&&isFinite()&&this in 0.0..360.0
}
data class PhoneSensorCapabilities(val attitudeAvailable:Boolean=false,val gyroAvailable:Boolean=false,val magnetometerAvailable:Boolean=false,val pressureAvailable:Boolean=false,val linearAccelerationAvailable:Boolean=false)
data class PhoneVesselAttitudeSample(val attitude:VesselAttitude?=null,val dynamicAccelerationG:Double=0.0,val mountSuspect:Boolean=false,val receivedElapsedRealtime:Long?=null)
data class PhonePressureSample(val pressureHpa:Double?=null,val receivedElapsedRealtime:Long?=null)

/** Maps the Android device frame into the vessel frame without subtracting the
 * orientation observed at confirmation time. That preserves real heel/pitch
 * when a trip begins while the vessel is already inclined. */
object PhoneVesselAttitudeFrame{
    fun resolve(current:SensorQuaternion,gyroValues:DoubleArray,axis:DeviceBowAxis):VesselAttitude{
        val corrected=(current*axisCorrection(axis)).normalized()
        val sinRoll=2*(corrected.w*corrected.x+corrected.y*corrected.z)
        val cosRoll=1-2*(corrected.x*corrected.x+corrected.y*corrected.y)
        val roll=Math.toDegrees(atan2(sinRoll,cosRoll))
        val sinPitch=2*(corrected.w*corrected.y-corrected.z*corrected.x)
        val pitch=Math.toDegrees(if(abs(sinPitch)>=1)if(sinPitch>=0)Math.PI/2 else -Math.PI/2 else asin(sinPitch))
        val rates=mapRates(gyroValues,axis)
        return VesselAttitude(roll,pitch,Math.toDegrees(rates[0]),Math.toDegrees(rates[1]),Math.toDegrees(rates[2]))
    }
    private fun axisCorrection(axis:DeviceBowAxis):SensorQuaternion{val angle=when(axis){DeviceBowAxis.TOP->0.0;DeviceBowAxis.RIGHT->-Math.PI/2;DeviceBowAxis.BOTTOM->Math.PI;DeviceBowAxis.LEFT->Math.PI/2};return SensorQuaternion(cos(angle/2),0.0,0.0,sin(angle/2))}
    private fun mapRates(v:DoubleArray,axis:DeviceBowAxis)=when(axis){DeviceBowAxis.TOP->doubleArrayOf(v[0],v[1],v[2]);DeviceBowAxis.BOTTOM->doubleArrayOf(-v[0],-v[1],v[2]);DeviceBowAxis.LEFT->doubleArrayOf(-v[1],v[0],v[2]);DeviceBowAxis.RIGHT->doubleArrayOf(v[1],-v[0],v[2])}
}

private val Context.mountStore by preferencesDataStore("vessel_mount_calibration")

@Singleton
class VesselMountCalibrationRepository @Inject constructor(@ApplicationContext private val context:Context){
    private object K{val version=intPreferencesKey("calibration_version");val axis=stringPreferencesKey("bow_axis");val w=doublePreferencesKey("neutral_w");val x=doublePreferencesKey("neutral_x");val y=doublePreferencesKey("neutral_y");val z=doublePreferencesKey("neutral_z");val at=longPreferencesKey("calibrated_at");val mount=stringPreferencesKey("mount_state");val mountConfirmedVersion=intPreferencesKey("mount_confirmed_version");val headingOffset=doublePreferencesKey("heading_alignment_offset");val headingAlignedAt=longPreferencesKey("heading_alignment_completed_at");val headingAlignmentVersion=intPreferencesKey("heading_alignment_version");val automaticRecovery=booleanPreferencesKey("automatic_mount_recovery");val attitudeInvalidatedAt=longPreferencesKey("attitude_invalidated_at")}
    val calibration=context.mountStore.data.map{p->
        val at=p[K.at]?:0;val version=p[K.version]?:1
        VesselMountCalibration(version=version,bowAxis=p[K.axis]?.let{runCatching{DeviceBowAxis.valueOf(it)}.getOrNull()}?:DeviceBowAxis.TOP,neutralQuaternion=SensorQuaternion(p[K.w]?:1.0,p[K.x]?:0.0,p[K.y]?:0.0,p[K.z]?:0.0).normalized(),calibratedAt=at,mountState=p[K.mount]?.let{runCatching{PhoneVesselMountState.valueOf(it)}.getOrNull()}?:if(at>0)PhoneVesselMountState.HANDHELD else PhoneVesselMountState.UNCALIBRATED,headingAlignmentOffsetDegrees=p[K.headingOffset]?:0.0,automaticMountRecovery=false,headingAlignmentCompletedAt=p[K.headingAlignedAt]?:0L,mountConfirmedVersion=p[K.mountConfirmedVersion]?:0,headingAlignmentVersion=p[K.headingAlignmentVersion]?:0,attitudeInvalidatedAt=p[K.attitudeInvalidatedAt]?:0L)
    }
    /** Confirms the phone-to-vessel attitude axes for a Trip segment. The
     * current boat attitude is not treated as zero; the quaternion is retained
     * only for backward-compatible diagnostics/backups. */
    suspend fun save(axis:DeviceBowAxis,q:SensorQuaternion){context.mountStore.edit{p->val version=(p[K.version]?:0)+1;p[K.version]=version;p[K.axis]=axis.name;p[K.w]=q.w;p[K.x]=q.x;p[K.y]=q.y;p[K.z]=q.z;p[K.at]=System.currentTimeMillis();p[K.attitudeInvalidatedAt]=0L;p[K.mount]=PhoneVesselMountState.HANDHELD.name;p[K.mountConfirmedVersion]=0}}
    suspend fun setMountState(value:PhoneVesselMountState)=context.mountStore.edit{p->p[K.mount]=value.name;p[K.mountConfirmedVersion]=if(value==PhoneVesselMountState.VESSEL_MOUNTED)p[K.version]?:1 else 0}
    suspend fun setHeadingAlignment(offsetDegrees:Double)=context.mountStore.edit{p->p[K.headingOffset]=((offsetDegrees+540.0)%360.0)-180.0;p[K.headingAlignedAt]=System.currentTimeMillis();p[K.headingAlignmentVersion]=p[K.version]?:1}
    suspend fun invalidateAttitudeSegment(atWallTime:Long)=context.mountStore.edit{p->
        p[K.attitudeInvalidatedAt]=maxOf(p[K.attitudeInvalidatedAt]?:0L,atWallTime)
        p[K.mount]=PhoneVesselMountState.MOUNT_SUSPECT.name
        p[K.mountConfirmedVersion]=0
    }
    suspend fun restore(value:VesselMountCalibration){
        val normalized=value.neutralQuaternion.normalized()
        context.mountStore.edit{p->
            p[K.version]=value.version.coerceAtLeast(1)
            p[K.axis]=value.bowAxis.name
            p[K.w]=normalized.w;p[K.x]=normalized.x;p[K.y]=normalized.y;p[K.z]=normalized.z
            p[K.at]=value.calibratedAt.coerceAtLeast(0L);p[K.mount]=value.mountState.name;p[K.mountConfirmedVersion]=value.mountConfirmedVersion;p[K.headingOffset]=value.headingAlignmentOffsetDegrees;p[K.headingAlignedAt]=value.headingAlignmentCompletedAt.coerceAtLeast(0L);p[K.headingAlignmentVersion]=value.headingAlignmentVersion;p[K.automaticRecovery]=false;p[K.attitudeInvalidatedAt]=value.attitudeInvalidatedAt.coerceAtLeast(0L)
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
    @Volatile private var calibration=VesselMountCalibration();private var currentQuaternion:SensorQuaternion?=null;private var gyroValues=DoubleArray(3);private var dynamicG=0.0;private var running=false
    init{scope.launch{calibrationRepository.calibration.collect{calibration=it;_mountState.value=when{it.calibratedAt<=0->PhoneVesselMountState.UNCALIBRATED;!it.attitudeFrameConfirmed->PhoneVesselMountState.MOUNT_SUSPECT;else->it.mountState}}}}
    @Synchronized fun start():Boolean{if(running)return capabilities.attitudeAvailable;val a=rotation?.let{manager.registerListener(this,it,SensorManager.SENSOR_DELAY_GAME)}?:false;if(a){gyro?.let{manager.registerListener(this,it,SensorManager.SENSOR_DELAY_GAME)};linear?.let{manager.registerListener(this,it,SensorManager.SENSOR_DELAY_GAME)}};running=a;return running}
    @Synchronized fun stop(){if(running)manager.unregisterListener(this);running=false;_sample.value=PhoneVesselAttitudeSample()}
    suspend fun calibrate(axis:DeviceBowAxis):Boolean{val q=currentQuaternion?:return false;calibrationRepository.save(axis,q);return true}
    suspend fun setMounted(mounted:Boolean){calibrationRepository.setMountState(if(mounted)PhoneVesselMountState.VESSEL_MOUNTED else PhoneVesselMountState.HANDHELD);_mountState.value=if(mounted)PhoneVesselMountState.VESSEL_MOUNTED else PhoneVesselMountState.HANDHELD}
    suspend fun alignHeading(offsetDegrees:Double)=calibrationRepository.setHeadingAlignment(offsetDegrees)
    override fun onSensorChanged(event:SensorEvent){when(event.sensor.type){Sensor.TYPE_GYROSCOPE->{gyroValues=doubleArrayOf(event.values[0].toDouble(),event.values[1].toDouble(),event.values[2].toDouble())};Sensor.TYPE_LINEAR_ACCELERATION->{dynamicG=sqrt(event.values.take(3).sumOf{it.toDouble()*it.toDouble()})/SensorManager.GRAVITY_EARTH};Sensor.TYPE_ROTATION_VECTOR,Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR->{val values=FloatArray(4);SensorManager.getQuaternionFromVector(values,event.values);val current=SensorQuaternion(values[0].toDouble(),values[1].toDouble(),values[2].toDouble(),values[3].toDouble()).normalized();currentQuaternion=current;publish(current)}}}
    private fun publish(current:SensorQuaternion){
        val now=SystemClock.elapsedRealtime()
        // Keep currentQuaternion available so the user can calibrate, but never
        // expose the Android device frame as if it were a vessel frame.
        if(calibration.calibratedAt<=0L){_mountState.value=PhoneVesselMountState.UNCALIBRATED;_sample.value=PhoneVesselAttitudeSample(receivedElapsedRealtime=now);return}
        // Do not subtract the current orientation. During a sailing trip the
        // vessel may already be heeled when the user confirms the phone frame;
        // zeroing that quaternion would erase the very attitude being measured.
        val attitude=PhoneVesselAttitudeFrame.resolve(current,gyroValues,calibration.bowAxis)
        val configuredMounted=calibration.mountConfirmed&&calibration.mountState==PhoneVesselMountState.VESSEL_MOUNTED
        if(!calibration.attitudeFrameConfirmed||calibration.mountState==PhoneVesselMountState.MOUNT_SUSPECT)_mountState.value=PhoneVesselMountState.MOUNT_SUSPECT
        else _mountState.value=if(configuredMounted)PhoneVesselMountState.VESSEL_MOUNTED else PhoneVesselMountState.HANDHELD
        val vesselFrame=_mountState.value==PhoneVesselMountState.VESSEL_MOUNTED
        _sample.value=if(vesselFrame)PhoneVesselAttitudeSample(attitude,dynamicG,false,now)else PhoneVesselAttitudeSample(dynamicAccelerationG=dynamicG,mountSuspect=_mountState.value==PhoneVesselMountState.MOUNT_SUSPECT,receivedElapsedRealtime=now)
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int)=Unit
}

@Singleton
class PhonePressureRepository @Inject constructor(@ApplicationContext context:Context):SensorEventListener{
    private val manager=context.getSystemService(SensorManager::class.java);private val sensor=manager.getDefaultSensor(Sensor.TYPE_PRESSURE);private val _sample=MutableStateFlow(PhonePressureSample());val sample=_sample.asStateFlow();private var running=false
    fun start():Boolean{if(running)return sensor!=null;running=sensor?.let{manager.registerListener(this,it,SensorManager.SENSOR_DELAY_NORMAL)}?:false;return running}
    fun stop(){if(running)manager.unregisterListener(this);running=false;_sample.value=PhonePressureSample()}
    override fun onSensorChanged(event:SensorEvent){event.values.firstOrNull()?.takeIf{it in 800f..1_200f}?.let{_sample.value=PhonePressureSample(it.toDouble(),SystemClock.elapsedRealtime())}}
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int)=Unit
}
