package com.yokuli.anchorwatch.domain.sonar

import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/**
 * Quarantines an isolated depth spike, but accepts a real bank or drop-off once
 * three consecutive observations continue coherently in the same direction.
 */
class DepthIntegrityFilter {
    private var accepted: DepthCandidate? = null
    private val pendingSlope = ArrayDeque<DepthCandidate>()

    fun reset() { accepted = null; pendingSlope.clear() }

    fun evaluate(candidate: DepthCandidate): DepthIntegrityResult {
        if (!candidate.rawDepthMeters.isFinite() || !candidate.normalizedDepthMeters.isFinite() ||
            candidate.rawDepthMeters <= 0.0 || candidate.rawDepthMeters > 12_000.0
        ) return DepthIntegrityResult(DepthDisposition.REJECTED_INVALID, "Depth is outside the supported range")

        val previous = accepted ?: run {
            accepted = candidate
            return DepthIntegrityResult(DepthDisposition.ACCEPTED)
        }
        val travelled = AnchorGeometry.distanceMeters(previous.latitude, previous.longitude, candidate.latitude, candidate.longitude)
        val allowedChange = max(1.5, travelled * .65 + .5)
        if (abs(candidate.normalizedDepthMeters - previous.normalizedDepthMeters) <= allowedChange) {
            accepted = candidate
            pendingSlope.clear()
            return DepthIntegrityResult(DepthDisposition.ACCEPTED)
        }

        pendingSlope += candidate
        while (pendingSlope.size > 3) pendingSlope.removeFirst()
        if (pendingSlope.size == 3 && confirmsRealSlope(previous, pendingSlope.toList())) {
            val released = pendingSlope.map { it.timestamp }
            accepted = candidate
            pendingSlope.clear()
            return DepthIntegrityResult(DepthDisposition.ACCEPTED_STEEP_SLOPE, "Three-point slope confirmation", released)
        }
        return DepthIntegrityResult(DepthDisposition.QUARANTINED_SPIKE, "Isolated depth jump awaiting confirmation")
    }

    private fun confirmsRealSlope(base: DepthCandidate, points: List<DepthCandidate>): Boolean {
        if (points.size != 3) return false
        val initialJump = points.first().normalizedDepthMeters - base.normalizedDepthMeters
        val continuedSteps = points.zipWithNext { a, b -> b.normalizedDepthMeters - a.normalizedDepthMeters }
        val direction = initialJump.sign
        if (direction == 0.0 || continuedSteps.any { it.sign != direction }) return false

        // A real bank can begin with one large jump followed by smaller, coherent
        // changes. Judge the confirmation samples against one another instead of
        // rejecting them merely because the first jump was much larger.
        val magnitudes = continuedSteps.map(::abs)
        val medianStep = magnitudes.sorted()[magnitudes.size / 2]
        return medianStep > .15 && magnitudes.all { it <= max(12.0, medianStep * 3.0) }
    }
}
