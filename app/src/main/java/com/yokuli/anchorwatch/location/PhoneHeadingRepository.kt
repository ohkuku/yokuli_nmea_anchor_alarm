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
    val trueHeadingDegrees: Double? = null,
    val quality: HeadingQuality = HeadingQuality.UNAVAILABLE,
    val epoch: Long = 0L,
    val sequence: Long = 0L,
)

@Singleton
class PhoneHeadingRepository @Inject constructor(
    @ApplicationContext context: Context,
) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val monitor = PhoneHeadingIntegrityMonitor()
    private val rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val _sample = MutableStateFlow(PhoneHeadingSample())
    val sample = _sample.asStateFlow()

    private var running = false
    private var acceleration = 9.81
    private var angularVelocity = 0.0
    private var accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    private var latitude = 0.0
    private var longitude = 0.0
    private var altitude = 0.0
    private var wallTime = System.currentTimeMillis()
    private var sequence = 0L

    fun setPosition(latitude: Double, longitude: Double, altitudeMeters: Double?, wallTimeMillis: Long?) {
        this.latitude = latitude
        this.longitude = longitude
        altitude = altitudeMeters ?: 0.0
        wallTime = wallTimeMillis ?: System.currentTimeMillis()
    }

    fun start(): Boolean {
        if (running) return rotation != null
        val orientation = rotation ?: return false
        monitor.reset()
        running = sensors.registerListener(this, orientation, SensorManager.SENSOR_DELAY_NORMAL)
        accelerometer?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        gyroscope?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        return running
    }

    fun stop() {
        if (running) sensors.unregisterListener(this)
        running = false
        monitor.reset()
        _sample.value = PhoneHeadingSample()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> acceleration = magnitude(event.values)
            Sensor.TYPE_GYROSCOPE -> angularVelocity = magnitude(event.values)
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> publishRotation(event.values)
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
        val observation = monitor.observe(
            nowElapsed = SystemClock.elapsedRealtime(),
            headingTrueDegrees = trueHeading,
            tiltDegrees = tilt,
            angularVelocityRadPerSecond = angularVelocity,
            accelerationMetersPerSecondSquared = acceleration,
            sensorAccuracy = accuracy,
        )
        sequence++
        _sample.value = PhoneHeadingSample(observation.headingTrueDegrees, observation.quality, observation.headingEpoch, sequence)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == rotation?.type) this.accuracy = accuracy
    }

    private fun magnitude(values: FloatArray): Double =
        sqrt(values.take(3).sumOf { it.toDouble() * it.toDouble() })
}
