package com.yokuli.anchorwatch.domain.vessel

import com.yokuli.anchorwatch.domain.model.GpsDataSource

data class PositionSourceConflictState(
    val phonePositionOutputEnabled:Boolean,
    val selectedGpsSource:GpsDataSource,
    val activeAnchorGpsSource:GpsDataSource?,
)

enum class PositionSourceAvailabilityReason {
    AVAILABLE,
    PHONE_POSITION_OUTPUT_ACTIVE,
    NMEA_POSITION_SOURCE_ACTIVE,
    ACTIVE_ANCHOR_SOURCE_LOCKED,
}

object PositionSourceConflictPolicy {
    /** NMEA can remain the App/Anchor input while an independently acquired,
     * non-mock Android fix is shared as Phone output. Output source provenance,
     * not the App's selected presentation source, prevents feedback. */
    fun nmeaPositionAvailability(@Suppress("UNUSED_PARAMETER") state:PositionSourceConflictState)=PositionSourceAvailabilityReason.AVAILABLE

    fun phonePositionOutputAvailability(@Suppress("UNUSED_PARAMETER") state:PositionSourceConflictState)=PositionSourceAvailabilityReason.AVAILABLE

    fun canSelectNmeaPosition(state:PositionSourceConflictState)=nmeaPositionAvailability(state)==PositionSourceAvailabilityReason.AVAILABLE
    fun canEnablePhonePositionOutput(state:PositionSourceConflictState)=phonePositionOutputAvailability(state)==PositionSourceAvailabilityReason.AVAILABLE

    /** The conflict applies only to POSITION. Boat depth/wind/heading remain usable. */
    fun canConsumeBoatNonPositionData(@Suppress("UNUSED_PARAMETER") state:PositionSourceConflictState)=true
}
