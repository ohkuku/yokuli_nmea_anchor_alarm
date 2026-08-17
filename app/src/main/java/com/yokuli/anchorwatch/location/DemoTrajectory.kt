package com.yokuli.anchorwatch.location

import com.yokuli.anchorwatch.domain.model.AnchorPlacementMode
import com.yokuli.anchorwatch.domain.model.DemoScenario
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

data class DemoTrajectoryPoint(
    val northMeters: Double,
    val eastMeters: Double,
    val headingDegrees: Double,
    val speedMetersPerSecond: Double,
    val signalAvailable: Boolean = true,
    val anchorNorthMeters: Double = 0.0,
    val anchorEastMeters: Double = 0.0,
    val headingToAnchorDegrees: Double? = null,
    val trueWindDirectionDegrees: Double? = null,
    val trueWindAngleDegrees: Double? = null,
    val apparentWindAngleDegrees: Double? = null,
    val windSpeedKnots: Double? = null,
    val evidenceSequence: Long? = null,
)

/**
 * Seeded, continuous anchoring simulation. The fresh System-GNSS coordinate is
 * always the boat's first position, never the hidden centre. The vessel pays
 * out smoothly, dwells in one sector, then turns gradually into another.
 */
object DemoTrajectory {
    private data class Geometry(
        val radius: Double,
        val downwind: Double,
        val centreNorth: Double,
        val centreEast: Double,
        val payoutSpeed: Double,
    )

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
        val geometry = geometry(alarmRadiusMeters, seed)
        val raw = when (scenario) {
            DemoScenario.SAFE_SWING -> safeSwing(seconds, placement, geometry, speed, seed)
            DemoScenario.WIND_SHIFT -> windShift(seconds, placement, geometry, speed, seed)
            DemoScenario.ANCHOR_DRAG -> anchorDrag(seconds, placement, geometry, speed, seed)
            DemoScenario.DEPTH_SHALLOW,DemoScenario.DEPTH_DEEP,DemoScenario.WIND_ALARM -> safeSwing(seconds,placement,geometry,speed,seed)
            DemoScenario.GPS_DROPOUT -> {
                val cutoff = 42.0 + seeded(seed, 91) * 18.0
                safeSwing(seconds, placement, geometry, speed, seed).copy(
                    signalAvailable = seconds < cutoff || ((seconds - cutoff) % 48.0) > 14.0,
                )
            }
        }
        val evidenced=withEvidence(raw, geometry, seconds, seed)
        return if(scenario==DemoScenario.WIND_ALARM)evidenced.copy(windSpeedKnots=conditionWindSpeed(seconds*simulationRate(speed),seed))else evidenced
    }

    private fun safeSwing(seconds: Double, placement: AnchorPlacementMode, geometry: Geometry, speed: Double, seed: Long): DemoTrajectoryPoint {
        payout(seconds, placement, geometry, speed, seed)?.let { return it }
        val after = seconds - payoutDuration(placement, geometry, speed)
        val simulated = after * simulationRate(speed)
        val transition = smoothStep((after / 18.0).coerceIn(0.0, 1.0))
        val angle = geometry.downwind + lingeringOffset(simulated, seed) * transition + smoothNoise(simulated / 13.0, seed + 8) * .055 * transition
        val targetRadius = geometry.radius * (.90 + .055 * smoothNoise(simulated / 21.0, seed + 9))
        val radius = geometry.radius + (targetRadius - geometry.radius) * transition
        return orbit(geometry, radius, angle, geometry.radius * .028 * simulationRate(speed))
    }

    private fun windShift(seconds: Double, placement: AnchorPlacementMode, geometry: Geometry, speed: Double, seed: Long): DemoTrajectoryPoint {
        payout(seconds, placement, geometry, speed, seed)?.let { return it }
        val after = seconds - payoutDuration(placement, geometry, speed)
        val simulated = after * simulationRate(speed)
        val transition = smoothStep((after / 18.0).coerceIn(0.0, 1.0))
        val veer = smoothStep(((simulated - 70.0) / 85.0).coerceIn(0.0, 1.0)) * (1.25 + seeded(seed, 71) * .55)
        val angle = geometry.downwind + (lingeringOffset(simulated, seed + 70) + veer) * transition + smoothNoise(simulated / 15.0, seed + 72) * .06 * transition
        val targetRadius = geometry.radius * (.88 + .07 * smoothNoise(simulated / 24.0, seed + 73))
        val radius = geometry.radius + (targetRadius - geometry.radius) * transition
        return orbit(geometry, radius, angle, geometry.radius * .032 * simulationRate(speed))
    }

    private fun anchorDrag(seconds: Double, placement: AnchorPlacementMode, geometry: Geometry, speed: Double, seed: Long): DemoTrajectoryPoint {
        payout(seconds, placement, geometry, speed, seed)?.let { return it }
        val after = seconds - payoutDuration(placement, geometry, speed)
        val settleSeconds = 14.0 + seeded(seed, 21) * 5.0
        fun settled(time: Double): DemoTrajectoryPoint {
            val angle = geometry.downwind + lingeringOffset(time * simulationRate(speed), seed + 20) * .24
            return orbit(geometry, geometry.radius * (.91 + .025 * smoothNoise(time / 10.0, seed + 22)), angle, geometry.radius * .02 * simulationRate(speed))
        }
        if (after < settleSeconds) return settled(after)
        val start = settled(settleSeconds)
        val dragSeconds = after - settleSeconds
        val bearing = geometry.downwind + (seeded(seed, 23) - .5) * PI / 5.0
        val dragSpeed = (1.10 + seeded(seed, 24) * .40) * speed
        val drift = dragSeconds * dragSpeed
        return DemoTrajectoryPoint(
            northMeters = start.northMeters + drift * cos(bearing) + smoothNoise(dragSeconds / 8.0, seed + 25) * .8,
            eastMeters = start.eastMeters + drift * sin(bearing) + smoothNoise(dragSeconds / 10.0, seed + 26) * .8,
            headingDegrees = degrees(bearing),
            speedMetersPerSecond = dragSpeed,
        )
    }

    private fun payout(seconds: Double, placement: AnchorPlacementMode, geometry: Geometry, speed: Double, seed: Long): DemoTrajectoryPoint? {
        if (seconds == 0.0) return origin()
        val holdSeconds = if (placement == AnchorPlacementMode.BACKDOWN) 8.0 else 0.0
        if (seconds <= holdSeconds) return stableDrop(seconds, seed)
        val moving = seconds - holdSeconds
        val duration = payoutDuration(placement, geometry, speed) - holdSeconds
        if (moving >= duration) return null
        val progress = smoothStep((moving / duration).coerceIn(0.0, 1.0))
        val endNorth = geometry.centreNorth + geometry.radius * cos(geometry.downwind)
        val endEast = geometry.centreEast + geometry.radius * sin(geometry.downwind)
        val crossTrack = smoothNoise(moving / 7.0, seed + 33) * minOf(1.6, geometry.radius * .03) * progress * (1.0 - progress)
        return DemoTrajectoryPoint(
            northMeters = endNorth * progress - crossTrack * sin(geometry.downwind),
            eastMeters = endEast * progress + crossTrack * cos(geometry.downwind),
            headingDegrees = degrees(geometry.downwind),
            speedMetersPerSecond = geometry.payoutSpeed * speed,
        )
    }

    private fun payoutDuration(placement: AnchorPlacementMode, geometry: Geometry, speed: Double): Double {
        val hold = if (placement == AnchorPlacementMode.BACKDOWN) 8.0 else 0.0
        val travel = hypot(geometry.centreNorth + geometry.radius * cos(geometry.downwind), geometry.centreEast + geometry.radius * sin(geometry.downwind))
        return hold + travel / (geometry.payoutSpeed * speed)
    }

    private fun lingeringOffset(simulatedSeconds: Double, seed: Long): Double {
        val targets = doubleArrayOf(0.0, 1.05, 2.15, 3.25, 2.05, .78, -.52, -1.72, -2.82, -1.45, 0.0)
        // Real anchored boats normally dwell in a sector and sweep across it
        // gradually. A shorter blend could move a 40–70 m swing radius by more
        // than 5 m between one-second fixes, which looked like a GPS teleport
        // and was the cause of the previous CI failure.
        val segmentSeconds = 42.0 + seeded(seed, 94) * 12.0
        val index = floor(simulatedSeconds / segmentSeconds).toInt().coerceAtLeast(0)
        val local = simulatedSeconds - index * segmentSeconds
        val start=(seeded(seed,95)*targets.lastIndex).toInt().coerceIn(0,targets.lastIndex-1);val direction=if(seeded(seed,96)<.5)-1.0 else 1.0;val scale=.90+seeded(seed,97)*.20
        fun target(at:Int)=targets[(start+at)%targets.lastIndex]*direction*scale+(seeded(seed,at+100)-.5)*.16
        val current = target(index);val next = target(index+1)
        val dwell=segmentSeconds*.38;val blend = smoothStep(((local-dwell)/(segmentSeconds-dwell)).coerceIn(0.0, 1.0))
        return current + (next - current) * blend
    }

    private fun geometry(alarmRadius: Double, seed: Long): Geometry {
        val radius = (alarmRadius * (.52 + seeded(seed, 1) * .13)).coerceIn(15.0, 78.0)
        val downwind = seeded(seed, 32) * 2.0 * PI
        val centreOffset = (5.0 + seeded(seed, 2) * 8.0).coerceAtMost(radius * .28)
        return Geometry(
            radius = radius,
            downwind = downwind,
            centreNorth = -centreOffset * cos(downwind),
            centreEast = -centreOffset * sin(downwind),
            payoutSpeed = .62 + seeded(seed, 31) * .30,
        )
    }

    private fun orbit(geometry: Geometry, radius: Double, angle: Double, speed: Double) = DemoTrajectoryPoint(
        northMeters = geometry.centreNorth + radius * cos(angle),
        eastMeters = geometry.centreEast + radius * sin(angle),
        headingDegrees = degrees(angle + PI),
        speedMetersPerSecond = speed,
    )

    private fun withEvidence(point: DemoTrajectoryPoint, geometry: Geometry, seconds: Double, seed: Long): DemoTrajectoryPoint {
        val toAnchor = degrees(atan2(geometry.centreEast - point.eastMeters, geometry.centreNorth - point.northMeters))
        val windAngle = 8.0 + smoothNoise(seconds / 22.0, seed + 81) * 4.0
        val windDirection = (toAnchor + windAngle + 360.0) % 360.0
        val windSpeed = 10.0 + smoothNoise(seconds / 31.0, seed + 82) * 2.0
        return point.copy(
            anchorNorthMeters = geometry.centreNorth,
            anchorEastMeters = geometry.centreEast,
            headingToAnchorDegrees = toAnchor,
            trueWindDirectionDegrees = windDirection,
            trueWindAngleDegrees = windAngle,
            apparentWindAngleDegrees = windAngle + smoothNoise(seconds / 17.0, seed + 83) * 2.0,
            windSpeedKnots = windSpeed,
            evidenceSequence = seconds.toLong(),
        )
    }

    private fun conditionWindSpeed(seconds:Double,seed:Long):Double{
        val noise=smoothNoise(seconds/9.0,seed+182)*.7
        return when{
            seconds<20.0->15.0+noise
            seconds<35.0->15.0+(28.0-15.0)*smoothStep((seconds-20.0)/15.0)+noise
            seconds<60.0->28.0+noise
            seconds<75.0->28.0+(38.0-28.0)*smoothStep((seconds-60.0)/15.0)+noise
            seconds<105.0->38.0+noise
            seconds<125.0->38.0+(18.0-38.0)*smoothStep((seconds-105.0)/20.0)+noise
            else->18.0+noise
        }
    }

    private fun stableDrop(seconds: Double, seed: Long) = DemoTrajectoryPoint(
        northMeters = sin(seconds * .83 + seeded(seed, 41) * PI) * .24,
        eastMeters = sin(seconds * 1.13 + seeded(seed, 42) * PI) * .24,
        headingDegrees = seeded(seed, 43) * 360.0,
        speedMetersPerSecond = .04,
    )

    private fun origin() = DemoTrajectoryPoint(0.0, 0.0, 0.0, 0.0)
    private fun simulationRate(speed:Double)=sqrt(speed)
    private fun smoothStep(value: Double) = value * value * (3.0 - 2.0 * value)

    private fun smoothNoise(value: Double, seed: Long): Double {
        val whole = floor(value).toLong()
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
