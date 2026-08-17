package com.yokuli.anchorwatch.domain.sonar

enum class DepthReference { BELOW_TRANSDUCER, BELOW_SURFACE, BELOW_KEEL, UNKNOWN }
enum class DepthSentenceType { DPT, DBT }
enum class TideMode { OFF, MANUAL, AUTO_PREDICTED }
enum class DepthDisposition { ACCEPTED, ACCEPTED_STEEP_SLOPE, QUARANTINED_SPIKE, REJECTED_INVALID, REJECTED_STALE_POSITION }

/** A decoded depth event. Raw values are retained so a survey can be rebuilt. */
data class DepthObservation(
    val rawDepthMeters: Double,
    val offsetMeters: Double? = null,
    val reference: DepthReference = DepthReference.BELOW_TRANSDUCER,
    val sentenceType: DepthSentenceType,
    val receivedElapsedRealtime: Long,
    val sourceSentence: String,
) {
    fun belowSurfaceMeters(transducerDraftMeters: Double): Double {
        val draft = transducerDraftMeters.coerceAtLeast(0.0)
        return when {
            sentenceType == DepthSentenceType.DPT && offsetMeters != null && offsetMeters >= 0.0 -> rawDepthMeters + offsetMeters
            else -> rawDepthMeters + draft
        }
    }
}

data class DepthCandidate(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val rawDepthMeters: Double,
    val normalizedDepthMeters: Double,
    val horizontalAccuracyMeters: Double?,
)

data class DepthIntegrityResult(
    val disposition: DepthDisposition,
    val reason: String? = null,
    /** Previously quarantined points released by a coherent real slope. */
    val releasedElapsedTimestamps: List<Long> = emptyList(),
) {
    val usable: Boolean get() = disposition == DepthDisposition.ACCEPTED || disposition == DepthDisposition.ACCEPTED_STEEP_SLOPE
}

data class NormalizedDepth(
    val measuredDepthMeters: Double,
    val measuredReference: DepthReference,
    /** Null means no chart-datum correction was applied. */
    val chartDatumDepthMeters: Double?,
)

object DepthNormalizer {
    fun normalize(
        observation: DepthObservation,
        configuredReference: DepthReference,
        transducerDraftMeters: Double,
        transducerToKeelMeters: Double,
        tideMode: TideMode,
        manualTideHeightMeters: Double,
    ): NormalizedDepth {
        val hasDptOffset = observation.sentenceType == DepthSentenceType.DPT && observation.offsetMeters != null
        val reference = if (hasDptOffset) observation.reference else
            configuredReference.takeUnless { it == DepthReference.UNKNOWN } ?: observation.reference
        val measured = if (hasDptOffset) observation.rawDepthMeters + observation.offsetMeters!! else observation.rawDepthMeters
        val surface = surfaceDepth(measured, reference, transducerDraftMeters, transducerToKeelMeters)
        return NormalizedDepth(
            measuredDepthMeters = measured,
            measuredReference = reference,
            chartDatumDepthMeters = if (tideMode == TideMode.MANUAL) surface?.minus(manualTideHeightMeters) else null,
        )
    }

    fun surfaceDepth(measured:Double,reference:DepthReference,transducerDraftMeters:Double,transducerToKeelMeters:Double):Double?=when(reference){
        DepthReference.BELOW_SURFACE->measured
        DepthReference.BELOW_TRANSDUCER->measured+transducerDraftMeters.coerceAtLeast(0.0)
        DepthReference.BELOW_KEEL->measured+transducerDraftMeters.coerceAtLeast(0.0)+transducerToKeelMeters.coerceAtLeast(0.0)
        DepthReference.UNKNOWN->null
    }
}
