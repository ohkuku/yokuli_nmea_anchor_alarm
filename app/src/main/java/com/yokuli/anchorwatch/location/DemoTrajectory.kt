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

/** Smooth, seeded demo motion. Every scenario starts at the supplied origin. */
object DemoTrajectory {
    fun point(
        elapsedMillis: Long,
        placement: AnchorPlacementMode,
        scenario: DemoScenario,
        alarmRadiusMeters: Double,
        speedMultiplier: Int,
        seed: Long = 0L,
    ): DemoTrajectoryPoint {
        val seconds = elapsedMillis.coerceAtLeast(0L) / 1_000.0
        val speed = speedMultiplier.takeIf { it in listOf(1, 2, 5) }?.toDouble() ?: 1.0
        return when (scenario) {
            DemoScenario.SAFE_SWING -> safeSwing(seconds, placement, alarmRadiusMeters, speed, seed)
            DemoScenario.ANCHOR_DRAG -> anchorDrag(seconds, placement, alarmRadiusMeters, speed, seed)
            DemoScenario.WIND_SHIFT -> windShift(seconds, placement, alarmRadiusMeters, speed, seed)
            DemoScenario.GPS_DROPOUT -> {
                val cutoff = 35.0 + seeded(seed, 91) * 15.0
                val base = safeSwing(seconds.coerceAtMost(cutoff), placement, alarmRadiusMeters, speed, seed)
                base.copy(signalAvailable = seconds < cutoff || ((seconds - cutoff) % 45.0) > 12.0)
            }
        }
    }

    private fun safeSwing(seconds: Double, placement: AnchorPlacementMode, alarmRadius: Double, speed: Double, seed: Long): DemoTrajectoryPoint {
        val targetRadius = (alarmRadius * (0.48 + seeded(seed, 1) * 0.17)).coerceIn(12.0, 75.0)
        backdownPayout(seconds, placement, targetRadius, speed, seed)?.let { return it }
        val afterPayout = seconds - payoutDuration(placement, targetRadius, speed, seed)
        val baseAngle = seeded(seed, 32) * 2.0 * PI
        val sweep = 1.10 + seeded(seed, 3) * 0.55
        val phase = afterPayout * (0.020 + seeded(seed, 4) * 0.012) * speed
        val angle = baseAngle + sin(phase) * sweep + smoothNoise(afterPayout / 18.0, seed + 5) * 0.09
        val radius = targetRadius * (0.84 + 0.12 * smoothNoise(afterPayout / 24.0, seed + 6))
        return polar(radius, angle, targetRadius * 0.025 * speed)
    }

    private fun windShift(seconds: Double, placement: AnchorPlacementMode, alarmRadius: Double, speed: Double, seed: Long): DemoTrajectoryPoint {
        val targetRadius = (alarmRadius * (0.55 + seeded(seed, 11) * 0.15)).coerceIn(15.0, 80.0)
        backdownPayout(seconds, placement, targetRadius, speed, seed)?.let { return it }
        val afterPayout = seconds - payoutDuration(placement, targetRadius, speed, seed)
        val firstDirection = seeded(seed, 32) * 2.0 * PI
        val shiftStart = 45.0 + seeded(seed, 13) * 35.0
        val progress = smoothStep(((afterPayout - shiftStart) / 45.0).coerceIn(0.0, 1.0))
        val directionChange = (1.65 + seeded(seed, 14) * 0.65) * progress
        val swing = sin(afterPayout * 0.027 * speed) * (0.75 + 0.30 * progress)
        val angle = firstDirection + directionChange + swing + smoothNoise(afterPayout / 20.0, seed + 15) * 0.08
        val radius = targetRadius * (0.78 + 0.16 * sin(afterPayout * 0.013 + seeded(seed, 16) * PI))
        return polar(radius, angle, targetRadius * 0.03 * speed)
    }

    private fun anchorDrag(seconds: Double, placement: AnchorPlacementMode, alarmRadius: Double, speed: Double, seed: Long): DemoTrajectoryPoint {
        val settleRadius = (alarmRadius * 0.52).coerceIn(12.0, 55.0)
        backdownPayout(seconds, placement, settleRadius, speed, seed)?.let { return it }
        val afterPayout = seconds - payoutDuration(placement, settleRadius, speed, seed)
        val settleSeconds = 25.0 + seeded(seed, 21) * 20.0
        val baseBearing = seeded(seed, 32) * 2.0 * PI
        fun settledPoint(time:Double):DemoTrajectoryPoint {
            val angle=baseBearing+sin(time*.025*speed)*.38+smoothNoise(time/20.0,seed+26)*.05
            return polar(settleRadius*(.90+.06*sin(time*.017)),angle,settleRadius*.018*speed)
        }
        if (afterPayout < settleSeconds) return settledPoint(afterPayout)
        val start = settledPoint(settleSeconds)
        val dragSeconds = afterPayout - settleSeconds
        val bearing = seeded(seed, 22) * 2.0 * PI
        val dragSpeed = (0.35 + seeded(seed, 23) * 0.45) * speed
        val drift = dragSeconds * dragSpeed
        return DemoTrajectoryPoint(
            northMeters = start.northMeters + drift * cos(bearing) + smoothNoise(dragSeconds / 9.0, seed + 24),
            eastMeters = start.eastMeters + drift * sin(bearing) + smoothNoise(dragSeconds / 11.0, seed + 25),
            headingDegrees = degrees(bearing),
            speedMetersPerSecond = dragSpeed,
        )
    }

    private fun backdownPayout(seconds: Double, placement: AnchorPlacementMode, targetRadius: Double, speed: Double, seed: Long): DemoTrajectoryPoint? {
        val holdSeconds = if (placement == AnchorPlacementMode.BACKDOWN) 8.0 else 0.0
        if (seconds <= holdSeconds) return if (seconds == 0.0) origin() else stableDrop(seconds, seed)
        val payoutSpeed = (0.65 + seeded(seed, 31) * 0.35) * speed
        val travelSeconds = targetRadius / payoutSpeed
        val moving = seconds - holdSeconds
        if (moving >= travelSeconds) return null
        val progress = smoothStep((moving / travelSeconds).coerceIn(0.0, 1.0))
        val bearing = seeded(seed, 32) * 2.0 * PI
        val distance = targetRadius * progress
        val crossTrack = smoothNoise(moving / 7.0, seed + 33) * minOf(1.8, targetRadius * 0.035) * progress
        return DemoTrajectoryPoint(
            northMeters = distance * cos(bearing) - crossTrack * sin(bearing),
            eastMeters = distance * sin(bearing) + crossTrack * cos(bearing),
            headingDegrees = degrees(bearing + PI),
            speedMetersPerSecond = payoutSpeed,
        )
    }

    private fun payoutDuration(placement: AnchorPlacementMode, radius: Double, speed: Double, seed: Long): Double {
        val hold = if (placement == AnchorPlacementMode.BACKDOWN) 8.0 else 0.0
        val payoutSpeed = (0.65 + seeded(seed, 31) * 0.35) * speed
        return hold + radius / payoutSpeed
    }

    private fun stableDrop(seconds: Double, seed: Long) = DemoTrajectoryPoint(
        northMeters = sin(seconds * 0.83 + seeded(seed, 41) * PI) * 0.28,
        eastMeters = sin(seconds * 1.13 + seeded(seed, 42) * PI) * 0.28,
        headingDegrees = seeded(seed, 43) * 360.0,
        speedMetersPerSecond = 0.04,
    )

    private fun origin() = DemoTrajectoryPoint(0.0, 0.0, 0.0, 0.0)

    private fun polar(radius: Double, angle: Double, speed: Double) = DemoTrajectoryPoint(
        northMeters = radius * cos(angle),
        eastMeters = radius * sin(angle),
        headingDegrees = degrees(angle + PI),
        speedMetersPerSecond = speed,
    )

    private fun smoothStep(value: Double) = value * value * (3.0 - 2.0 * value)

    private fun smoothNoise(value: Double, seed: Long): Double {
        val whole = kotlin.math.floor(value).toLong()
        val fraction = smoothStep(value - whole)
        val first = seeded(seed, whole.toInt()) * 2.0 - 1.0
        val second = seeded(seed, (whole + 1).toInt()) * 2.0 - 1.0
        return first + (second - first) * fraction
    }

    private fun seeded(seed: Long, salt: Int): Double {
        var value = seed xor (salt.toLong() * -7046029254386353131L)
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        value = value xor (value ushr 31)
        return (value ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    private fun degrees(radians: Double) = (Math.toDegrees(radians) + 360.0) % 360.0
}
