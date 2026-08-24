package com.yokuli.anchorwatch.data.nmea

/**
 * Holds the latest wind components without pretending that unrelated NMEA
 * sentences form one simultaneous observation. A component must be both fresh
 * and close in time to the newest component before it can enter a snapshot.
 */
class WindSnapshotAccumulator(
    private val maximumAgeMillis: Long = 10_000L,
    private val maximumSkewMillis: Long = 2_500L,
) {
    data class Snapshot(
        val trueDirectionDegrees: Double? = null,
        val trueSpeedKnots: Double? = null,
        val apparentSpeedKnots: Double? = null,
        val apparentAngleDegrees: Double? = null,
        val trueAngleDegrees: Double? = null,
        val sampleSequence: Long? = null,
    )

    private data class Timed(val value: Double, val elapsedMillis: Long)

    private var trueDirection: Timed? = null
    private var trueSpeed: Timed? = null
    private var apparentSpeed: Timed? = null
    private var apparentAngle: Timed? = null
    private var trueAngle: Timed? = null
    private var sequence = 0L

    fun update(update: NmeaUpdate, elapsedMillis: Long) {
        var changed = false
        fun measured(metric:NmeaMetric)=update.measuredAt(metric)?:elapsedMillis
        update.trueWindDirection?.takeIf{update.metricTimings.isEmpty()||update.isNumeric(NmeaMetric.TRUE_WIND_DIRECTION)}?.let { trueDirection = Timed(it, measured(NmeaMetric.TRUE_WIND_DIRECTION)); changed = true }
        update.trueWindSpeedKnots?.takeIf{update.metricTimings.isEmpty()||update.isNumeric(NmeaMetric.TRUE_WIND_SPEED)}?.let { trueSpeed = Timed(it, measured(NmeaMetric.TRUE_WIND_SPEED)); changed = true }
        update.apparentWindSpeedKnots?.takeIf{update.metricTimings.isEmpty()||update.isNumeric(NmeaMetric.APPARENT_WIND_SPEED)}?.let { apparentSpeed = Timed(it, measured(NmeaMetric.APPARENT_WIND_SPEED)); changed = true }
        update.apparentWindAngle?.takeIf{update.metricTimings.isEmpty()||update.isNumeric(NmeaMetric.APPARENT_WIND_ANGLE)}?.let { apparentAngle = Timed(it, measured(NmeaMetric.APPARENT_WIND_ANGLE)); changed = true }
        update.trueWindAngle?.takeIf{update.metricTimings.isEmpty()||update.isNumeric(NmeaMetric.TRUE_WIND_ANGLE)}?.let { trueAngle = Timed(it, measured(NmeaMetric.TRUE_WIND_ANGLE)); changed = true }
        if (changed) sequence++
    }

    fun snapshot(elapsedMillis: Long): Snapshot {
        val fresh = listOfNotNull(trueDirection, trueSpeed, apparentSpeed, apparentAngle, trueAngle)
            .filter { elapsedMillis - it.elapsedMillis in 0..maximumAgeMillis }
        val newest = fresh.maxOfOrNull { it.elapsedMillis } ?: return Snapshot()
        fun Timed?.coherent(): Double? = this
            ?.takeIf { elapsedMillis - it.elapsedMillis in 0..maximumAgeMillis }
            ?.takeIf { newest - it.elapsedMillis <= maximumSkewMillis }
            ?.value
        return Snapshot(
            trueDirectionDegrees = trueDirection.coherent(),
            trueSpeedKnots = trueSpeed.coherent(),
            apparentSpeedKnots = apparentSpeed.coherent(),
            apparentAngleDegrees = apparentAngle.coherent(),
            trueAngleDegrees = trueAngle.coherent(),
            sampleSequence = sequence.takeIf { fresh.isNotEmpty() },
        )
    }

    fun clear(){trueDirection=null;trueSpeed=null;apparentSpeed=null;apparentAngle=null;trueAngle=null;sequence=0}
}
