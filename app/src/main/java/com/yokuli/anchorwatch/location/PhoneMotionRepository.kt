package com.yokuli.anchorwatch.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sqrt
import javax.inject.Inject
import javax.inject.Singleton

data class PhoneMotionState(
    val available: Boolean = false,
    val moving: Boolean = false,
    val disturbed: Boolean = false,
    val accelerationDeltaMetersPerSecondSquared: Double = 0.0,
    val angularVelocityRadPerSecond: Double = 0.0,
    val updatedElapsedRealtime: Long? = null,
)

/**
 * Motion integrity is deliberately independent from phone heading. An active
 * System-GNSS watch keeps this sensor running even when the user has disabled
 * phone-heading evidence, so moving the handset cannot manufacture a trusted
 * position jump.
 */
@Singleton
class PhoneMotionRepository @Inject constructor(
    @ApplicationContext context: Context,
) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val _state = MutableStateFlow(PhoneMotionState(available = accelerometer != null || gyroscope != null))
    val state = _state.asStateFlow()
    private var running = false
    private var acceleration = 9.81
    private var angularVelocity = 0.0

    fun start(): Boolean {
        if (running) return _state.value.available
        val accelerometerStarted = accelerometer?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        } ?: false
        val gyroscopeStarted = gyroscope?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        } ?: false
        running = accelerometerStarted || gyroscopeStarted
        return running
    }

    fun stop() {
        if (running) manager.unregisterListener(this)
        running = false
        acceleration = 9.81
        angularVelocity = 0.0
        _state.value = PhoneMotionState(available = accelerometer != null || gyroscope != null)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> acceleration = magnitude(event.values)
            Sensor.TYPE_GYROSCOPE -> angularVelocity = magnitude(event.values)
        }
        val accelerationDelta = abs(acceleration - 9.81)
        _state.value = PhoneMotionState(
            available = true,
            moving = angularVelocity > .7 || accelerationDelta > 3.0,
            disturbed = angularVelocity > 1.4 || accelerationDelta > 5.0,
            accelerationDeltaMetersPerSecondSquared = accelerationDelta,
            angularVelocityRadPerSecond = angularVelocity,
            updatedElapsedRealtime = SystemClock.elapsedRealtime(),
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun magnitude(values: FloatArray): Double =
        sqrt(values.take(3).sumOf { it.toDouble() * it.toDouble() })
}
