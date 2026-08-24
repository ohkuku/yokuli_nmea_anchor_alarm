package com.yokuli.anchorwatch.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.GeomagneticField
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import android.view.Surface
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
    /** HDT may only be emitted when this is true. */
    val declinationReferenceReady: Boolean = false,
)

data class DeclinationReferenceState(
    val ready:Boolean=false,
    val latitude:Double?=null,
    val longitude:Double?=null,
    val updatedAtUtcMillis:Long?=null,
)

@Suppress("DEPRECATION")
@Singleton
class PhoneHeadingRepository @Inject constructor(
    @ApplicationContext context: Context,
) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val monitor = PhoneHeadingIntegrityMonitor()
    private val rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensors.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    // Some low-cost OEMs expose a working compass only through this deprecated
    // virtual sensor. It is a last-resort presentation source, never estimator evidence.
    private val legacyOrientation = sensors.getDefaultSensor(Sensor.TYPE_ORIENTATION)
    private val gyroscope = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val _sample = MutableStateFlow(PhoneHeadingSample())
    val sample = _sample.asStateFlow()
    private val _declinationReference=MutableStateFlow(DeclinationReferenceState())
    val declinationReference=_declinationReference.asStateFlow()

    private var running = false
    private var runtimeDemand = false
    private var displayDemand = false
    private var approachDemand = false
    private var lastPublishedElapsed = 0L
    private var lastPublishedHeading:Double?=null
    private var lastRotationVectorElapsed = 0L
    private var lastRawCompassElapsed = 0L
    private var acceleration = 9.81
    private var angularVelocity = 0.0
    private var accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    private var magnetometerAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    private var legacyOrientationAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private var hasAccelerometerReading = false
    private var hasMagnetometerReading = false
    private var latitude = 0.0
    private var longitude = 0.0
    private var altitude = 0.0
    private var wallTime = System.currentTimeMillis()
    private var sequence = System.currentTimeMillis() * 1_000L
    private var activationEpoch = System.currentTimeMillis()

    fun isAvailable(): Boolean = rotation != null ||
        (accelerometer != null && magnetometer != null) || legacyOrientation != null

    fun setPosition(latitude: Double, longitude: Double, altitudeMeters: Double?, wallTimeMillis: Long?) {
        if(!latitude.isFinite()||!longitude.isFinite()||latitude !in -90.0..90.0||longitude !in -180.0..180.0)return
        this.latitude = latitude
        this.longitude = longitude
        altitude = altitudeMeters ?: 0.0
        wallTime = wallTimeMillis ?: System.currentTimeMillis()
        _declinationReference.value=DeclinationReferenceState(true,latitude,longitude,wallTime)
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
            running=false;monitor.reset();lastPublishedElapsed=0L;lastPublishedHeading=null
            lastRotationVectorElapsed=0L;lastRawCompassElapsed=0L
            hasAccelerometerReading=false;hasMagnetometerReading=false;_sample.value=PhoneHeadingSample()
            return false
        }
        if (running) return isAvailable()
        if (!isAvailable()) return false
        activationEpoch = maxOf(activationEpoch + 1L, System.currentTimeMillis())
        monitor.reset()
        // GAME is substantially more responsive than NORMAL while still avoiding
        // the battery/CPU cost of FASTEST. UI publication below is capped at 20 Hz.
        val rotationRegistered = rotation?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: false
        val accelerometerRegistered = accelerometer?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: false
        val magnetometerRegistered = magnetometer?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: false
        val legacyOrientationRegistered = legacyOrientation?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: false
        gyroscope?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        running = rotationRegistered || (accelerometerRegistered && magnetometerRegistered) || legacyOrientationRegistered
        if (!running) {
            sensors.unregisterListener(this)
        }
        return running
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                acceleration = magnitude(event.values)
                event.values.copyInto(accelerometerReading, endIndex = 3)
                hasAccelerometerReading = true
                publishCompassFallbackIfNeeded()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                event.values.copyInto(magnetometerReading, endIndex = 3)
                hasMagnetometerReading = true
                publishCompassFallbackIfNeeded()
            }
            Sensor.TYPE_GYROSCOPE -> angularVelocity = magnitude(event.values)
            Sensor.TYPE_ORIENTATION -> publishLegacyOrientationIfNeeded(event.values)
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                lastRotationVectorElapsed = SystemClock.elapsedRealtime()
                val matrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                publishMatrix(matrix, accuracy)
            }
        }
    }

    private fun publishCompassFallbackIfNeeded() {
        if (!hasAccelerometerReading || !hasMagnetometerReading) return
        val nowElapsed = SystemClock.elapsedRealtime()
        // Prefer the OEM-fused rotation vector. A number of otherwise capable
        // phones omit it or stop publishing it, while Google Maps can still use
        // the physical accelerometer + compass path.
        if (rotation != null && nowElapsed - lastRotationVectorElapsed <= ROTATION_VECTOR_TIMEOUT_MILLIS) return
        val matrix = FloatArray(9)
        if (!SensorManager.getRotationMatrix(matrix, null, accelerometerReading, magnetometerReading)) return
        lastRawCompassElapsed = nowElapsed
        publishMatrix(matrix, magnetometerAccuracy)
    }

    private fun publishLegacyOrientationIfNeeded(values: FloatArray) {
        if (values.isEmpty()) return
        val nowElapsed = SystemClock.elapsedRealtime()
        if (rotation != null && nowElapsed - lastRotationVectorElapsed <= ROTATION_VECTOR_TIMEOUT_MILLIS) return
        if (nowElapsed - lastRawCompassElapsed <= RAW_COMPASS_TIMEOUT_MILLIS) return
        val magnetic = ((values[0].toDouble() + displayRotationDegrees()) % 360.0 + 360.0) % 360.0
        val pitch = values.getOrNull(1)?.toDouble() ?: 0.0
        val roll = values.getOrNull(2)?.toDouble() ?: 0.0
        publishMagneticHeading(
            magnetic = magnetic,
            tilt = kotlin.math.hypot(pitch, roll),
            sensorAccuracy = legacyOrientationAccuracy,
            allowEstimatorEvidence = false,
        )
    }

    private fun publishMatrix(deviceMatrix: FloatArray, sensorAccuracy: Int) {
        val matrix = displayAdjustedMatrix(deviceMatrix)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(matrix, orientation)
        val magnetic = (Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0
        val tilt = Math.toDegrees(acos(deviceMatrix[8].toDouble().coerceIn(-1.0, 1.0)))
        publishMagneticHeading(magnetic, tilt, sensorAccuracy)
    }

    private fun publishMagneticHeading(
        magnetic: Double,
        tilt: Double,
        sensorAccuracy: Int,
        allowEstimatorEvidence: Boolean = true,
    ) {
        val declination = if(_declinationReference.value.ready)GeomagneticField(latitude.toFloat(), longitude.toFloat(), altitude.toFloat(), wallTime).declination else 0f
        val trueHeading = (magnetic + declination + 360.0) % 360.0
        val nowElapsed=SystemClock.elapsedRealtime()
        val observation = if (allowEstimatorEvidence) {
            monitor.observe(
                nowElapsed = nowElapsed,
                headingTrueDegrees = trueHeading,
                tiltDegrees = tilt,
                angularVelocityRadPerSecond = angularVelocity,
                accelerationMetersPerSecondSquared = acceleration,
                sensorAccuracy = sensorAccuracy,
            )
        } else {
            PhoneHeadingObservation(HeadingQuality.UNAVAILABLE, null, 0L)
        }
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
            declinationReferenceReady = _declinationReference.value.ready,
        )
    }

    private fun displayAdjustedMatrix(deviceMatrix: FloatArray): FloatArray {
        val axes = when (displayRotation()) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> return deviceMatrix
        }
        val adjusted = FloatArray(9)
        return if (SensorManager.remapCoordinateSystem(deviceMatrix, axes.first, axes.second, adjusted)) adjusted else deviceMatrix
    }

    private fun displayRotation(): Int = displays.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0

    private fun displayRotationDegrees(): Double = when (displayRotation()) {
        Surface.ROTATION_90 -> 90.0
        Surface.ROTATION_180 -> 180.0
        Surface.ROTATION_270 -> 270.0
        else -> 0.0
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == rotation?.type) this.accuracy = accuracy
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) magnetometerAccuracy = accuracy
        if (sensor?.type == Sensor.TYPE_ORIENTATION) legacyOrientationAccuracy = accuracy
    }

    private fun magnitude(values: FloatArray): Double =
        sqrt(values.take(3).sumOf { it.toDouble() * it.toDouble() })

    private companion object {
        const val ROTATION_VECTOR_TIMEOUT_MILLIS = 750L
        const val RAW_COMPASS_TIMEOUT_MILLIS = 750L
    }
}
