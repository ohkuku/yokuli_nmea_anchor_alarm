package com.yokuli.anchorwatch.domain.anchor

import com.yokuli.anchorwatch.domain.model.AnchorEstimate
import com.yokuli.anchorwatch.domain.model.Confidence
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/** Robust partial-circle fit. Outliers are scored, not placed on a convex hull. */
class AnchorCenterEstimator(private val random: Random = Random.Default) {
    data class Point(val latitude: Double, val longitude: Double)
    private data class LocalPoint(val x: Double, val y: Double)
    private data class Circle(val x: Double, val y: Double, val radius: Double)

    fun estimate(points: List<Point>, expectedRadius: Double? = null): AnchorEstimate? {
        if (points.size < 20) return null
        val originLatitude = points.map { it.latitude }.average()
        val originLongitude = points.map { it.longitude }.average()
        val cosLatitude = cos(Math.toRadians(originLatitude)).coerceAtLeast(.01)
        val local = evenlySample(points, 800).map {
            LocalPoint(
                (it.longitude - originLongitude) * 111_320.0 * cosLatitude,
                (it.latitude - originLatitude) * 110_540.0,
            )
        }

        var best: Circle? = null
        var bestInliers: List<LocalPoint> = emptyList()
        var bestScore = Double.NEGATIVE_INFINITY
        repeat(400) {
            val firstIndex = random.nextInt(local.size)
            var secondIndex = random.nextInt(local.size - 1)
            if (secondIndex >= firstIndex) secondIndex++
            var thirdIndex = random.nextInt(local.size - 2)
            val low = minOf(firstIndex, secondIndex)
            val high = maxOf(firstIndex, secondIndex)
            if (thirdIndex >= low) thirdIndex++
            if (thirdIndex >= high) thirdIndex++
            val circle = circumcircle(local[firstIndex], local[secondIndex], local[thirdIndex]) ?: return@repeat
            if (!circle.radius.isFinite() || circle.radius < 2.0 || circle.radius > 2_000.0) return@repeat
            val tolerance = max(3.5, circle.radius * .12)
            val residuals = local.map { point -> abs(hypot(point.x - circle.x, point.y - circle.y) - circle.radius) }
            val inliers = local.filterIndexed { index, _ -> residuals[index] <= tolerance }
            val medianResidual = median(residuals)
            val priorPenalty = expectedRadius?.let { abs(circle.radius - it) / max(it, 1.0) } ?: 0.0
            val score = inliers.size - medianResidual * .15 - priorPenalty * local.size * .08
            if (score > bestScore) {
                bestScore = score
                best = circle
                bestInliers = inliers
            }
        }
        val minimumInliers = max(12, (local.size * .65).toInt())
        if (best == null || bestInliers.size < minimumInliers) return null
        var fit = leastSquares(bestInliers) ?: best!!
        var refinedInliers = bestInliers
        repeat(3) {
            val tolerance = max(3.0, fit.radius * .10)
            val next = local.filter { point -> abs(hypot(point.x - fit.x, point.y - fit.y) - fit.radius) <= tolerance }
            if (next.size < minimumInliers) return@repeat
            val refined = leastSquares(next) ?: return@repeat
            if (!refined.radius.isFinite() || refined.radius !in 2.0..2_000.0) return@repeat
            refinedInliers = next
            fit = refined
        }
        val residuals = local.map { abs(hypot(it.x - fit.x, it.y - fit.y) - fit.radius) }.sorted()
        val robustResiduals = residuals.take(max(1, (residuals.size * .9).toInt()))
        val rms = sqrt(robustResiduals.map { it.pow(2) }.average())
        val inlierAngles = refinedInliers.map {
            (Math.toDegrees(atan2(it.y - fit.y, it.x - fit.x)) + 360.0) % 360.0
        }.sorted()
        val maximumGap = (inlierAngles.zipWithNext { first, second -> second - first } +
            (inlierAngles.first() + 360.0 - inlierAngles.last())).maxOrNull() ?: 360.0
        val coverage = 360.0 - maximumGap
        // Evaluate a few bin rotations so a sample at 29.999999° does not lose
        // an entire sector because of floating-point boundary placement.
        val sectors = (0 until 30 step 5).maxOf { offset ->
            inlierAngles.map { (((it + offset) % 360.0) / 30.0).toInt() }.distinct().size
        }
        val priorPenalty = expectedRadius?.let { abs(fit.radius - it) / max(it, 1.0) } ?: 0.0
        val confidence = when {
            points.size >= 120 && coverage >= 200.0 && sectors >= 8 &&
                rms <= max(4.0, fit.radius * .12) && priorPenalty < .35 -> Confidence.HIGH
            coverage >= 90.0 && sectors >= 4 && rms <= max(8.0, fit.radius * .2) -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        return AnchorEstimate(
            latitude = originLatitude + fit.y / 110_540.0,
            longitude = originLongitude + fit.x / (111_320.0 * cosLatitude),
            radiusMeters = fit.radius,
            confidence = confidence,
            rmsErrorMeters = rms,
            angularCoverageDegrees = coverage,
            sampleCount = points.size,
        )
    }

    private fun circumcircle(first: LocalPoint, second: LocalPoint, third: LocalPoint): Circle? {
        val denominator = 2 * (first.x * (second.y - third.y) + second.x * (third.y - first.y) + third.x * (first.y - second.y))
        if (abs(denominator) < 1e-8) return null
        val aa = first.x * first.x + first.y * first.y
        val bb = second.x * second.x + second.y * second.y
        val cc = third.x * third.x + third.y * third.y
        val x = (aa * (second.y - third.y) + bb * (third.y - first.y) + cc * (first.y - second.y)) / denominator
        val y = (aa * (third.x - second.x) + bb * (first.x - third.x) + cc * (second.x - first.x)) / denominator
        return Circle(x, y, hypot(first.x - x, first.y - y))
    }

    private fun leastSquares(points: List<LocalPoint>): Circle? {
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        var sxz = 0.0; var syz = 0.0; var sz = 0.0
        points.forEach {
            val z = it.x * it.x + it.y * it.y
            sx += it.x; sy += it.y; sxx += it.x * it.x; syy += it.y * it.y; sxy += it.x * it.y
            sxz += it.x * z; syz += it.y * z; sz += z
        }
        val count = points.size.toDouble()
        val matrix = arrayOf(doubleArrayOf(sxx, sxy, sx), doubleArrayOf(sxy, syy, sy), doubleArrayOf(sx, sy, count))
        val values = doubleArrayOf(-sxz, -syz, -sz)
        for (row in 0..2) {
            val pivot = (row..2).maxBy { abs(matrix[it][row]) }
            val swap = matrix[row]; matrix[row] = matrix[pivot]; matrix[pivot] = swap
            val valueSwap = values[row]; values[row] = values[pivot]; values[pivot] = valueSwap
            if (abs(matrix[row][row]) < 1e-9) return null
            for (other in row + 1..2) {
                val factor = matrix[other][row] / matrix[row][row]
                for (column in row..2) matrix[other][column] -= factor * matrix[row][column]
                values[other] -= factor * values[row]
            }
        }
        val solved = DoubleArray(3)
        for (row in 2 downTo 0) solved[row] =
            (values[row] - (row + 1..2).sumOf { matrix[row][it] * solved[it] }) / matrix[row][row]
        val x = -solved[0] / 2
        val y = -solved[1] / 2
        return Circle(x, y, sqrt(max(0.0, x * x + y * y - solved[2])))
    }

    private fun <T> evenlySample(values: List<T>, maximum: Int): List<T> =
        if (values.size <= maximum) values else (0 until maximum).map {
            values[(it.toLong() * values.lastIndex / (maximum - 1)).toInt()]
        }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }
}
