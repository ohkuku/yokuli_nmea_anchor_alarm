package com.yokuli.anchorwatch.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.GeomagneticField
import android.os.SystemClock
import com.yokuli.anchorwatch.domain.model.HeadingQuality
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.acos
import kotlin.math.sqrt
import javax.inject.Inject
import javax.inject.Singleton

data class PhoneHeadingSample(
    /** Responsive sensor heading for map/navigation presentation only. */
    val liveTrueHeadingDegrees: Double? = null,
    /** Integrity-gated heading that is safe to persist as estimator evidence. */
    val trueHeadingDegrees: Double? = null,
    val quality: HeadingQuality = HeadingQuality.UNAVAILABLE,
    val epoch: Long = 0L,
    val sequence: Long = 0L,
    val receivedElapsedRealtime: Long? = null,
)

@Singleton
class PhoneHeadingRepository @Inject constructor(
    @ApplicationContext context: Context,
) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val monitor = PhoneHeadingIntegrityMonitor()
    private val rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensors.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val _sample = MutableStateFlow(PhoneHeadingSample())
    val sample = _sample.asStateFlow()

    private var running = false
    private var runtimeDemand = false
    private var displayDemand = false
    private var approachDemand = false
    private var lastPublishedElapsed = 0L
    private var lastPublishedHeading:Double?=null
    private var acceleration = 9.81
    private var angularVelocity = 0.0
    private var accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    private var latitude = 0.0
    private var longitude = 0.0
    private var altitude = 0.0
    private var wallTime = System.currentTimeMillis()
    private var sequence = System.currentTimeMillis() * 1_000L
    private var activationEpoch = System.currentTimeMillis()

    fun isAvailable(): Boolean = rotation != null

    fun setPosition(latitude: Double, longitude: Double, altitudeMeters: Double?, wallTimeMillis: Long?) {
        this.latitude = latitude
        this.longitude = longitude
        altitude = altitudeMeters ?: 0.0
        wallTime = wallTimeMillis ?: System.currentTimeMillis()
    }

    /** Safety-runtime ownership; display and approach use independent demands. */
    @Synchronized fun start(): Boolean { runtimeDemand=true;return reconcile() }

    @Synchronized fun stop() { runtimeDemand=false;reconcile() }

    @Synchronized fun setDisplayDemand(active:Boolean):Boolean{displayDemand=active;return reconcile()}

    @Synchronized fun setApproachDemand(active:Boolean):Boolean{approachDemand=active;return reconcile()}

    @Synchronized private fun reconcile(): Boolean {
        val wanted=runtimeDemand||displayDemand||approachDemand
        if(!wanted){
            if(running)sensors.unregisterListener(this)
            running=false;monitor.reset();lastPublishedElapsed=0L;lastPublishedHeading=null;_sample.value=PhoneHeadingSample()
            return false
        }
        if (running) return rotation != null
        val orientation = rotation ?: return false
        activationEpoch = maxOf(activationEpoch + 1L, System.currentTimeMillis())
        monitor.reset()
        // GAME is substantially more responsive than NORMAL while still avoiding
        // the battery/CPU cost of FASTEST. UI publication below is capped at 20 Hz.
        running = sensors.registerListener(this, orientation, SensorManager.SENSOR_DELAY_GAME)
        if (running) {
            accelerometer?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            gyroscope?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        } else {
            sensors.unregisterListener(this)
        }
        return running
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> acceleration = magnitude(event.values)
            Sensor.TYPE_GYROSCOPE -> angularVelocity = magnitude(event.values)
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> publishRotation(event.values)
        }
    }

    private fun publishRotation(values: FloatArray) {
        val matrix = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(matrix, values)
        SensorManager.getOrientation(matrix, orientation)
        val magnetic = (Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0
        val tilt = Math.toDegrees(acos(matrix[8].toDouble().coerceIn(-1.0, 1.0)))
        val declination = GeomagneticField(latitude.toFloat(), longitude.toFloat(), altitude.toFloat(), wallTime).declination
        val trueHeading = (magnetic + declination + 360.0) % 360.0
        val nowElapsed=SystemClock.elapsedRealtime()
        val observation = monitor.observe(
            nowElapsed = nowElapsed,
            headingTrueDegrees = trueHeading,
            tiltDegrees = tilt,
            angularVelocityRadPerSecond = angularVelocity,
            accelerationMetersPerSecondSquared = acceleration,
            sensorAccuracy = accuracy,
        )
        val previous=lastPublishedHeading
        val delta=previous?.let{kotlin.math.abs(((trueHeading-it+540.0)%360.0)-180.0)}?:Double.POSITIVE_INFINITY
        if(nowElapsed-lastPublishedElapsed<50L&&delta<1.0)return
        lastPublishedElapsed=nowElapsed;lastPublishedHeading=trueHeading
        // Keep sequence IDs unique across service/process restarts so persisted
        // evidence from an earlier activation never deduplicates newer samples.
        sequence = maxOf(sequence + 1L, System.currentTimeMillis() * 1_000L)
        // A user can disable and later re-enable phone heading after physically
        // moving the handset. Keep those activations in separate epochs so old
        // and new calibration evidence can coexist without being blended.
        val epoch = activationEpoch * 1_000L + observation.headingEpoch
        // Navigation presentation must follow the handset while it is turning.
        // The integrity monitor deliberately suppresses estimator evidence during
        // motion and for its recovery window, so exposing only that value made the
        // on-screen arrow appear frozen. Keep both channels explicit: the Android
        // rotation vector drives the live UI, while only the monitor output may be
        // persisted as anchor-centre evidence.
        // TODO(physical-device): add a sensor-injection instrumentation regression
        // once CI has a deterministic rotation-vector source.
        _sample.value = PhoneHeadingSample(
            liveTrueHeadingDegrees = trueHeading,
            trueHeadingDegrees = observation.headingTrueDegrees,
            quality = observation.quality,
            epoch = epoch,
            sequence = sequence,
            receivedElapsedRealtime = nowElapsed,
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == rotation?.type) this.accuracy = accuracy
    }

    private fun magnitude(values: FloatArray): Double =
        sqrt(values.take(3).sumOf { it.toDouble() * it.toDouble() })
}
