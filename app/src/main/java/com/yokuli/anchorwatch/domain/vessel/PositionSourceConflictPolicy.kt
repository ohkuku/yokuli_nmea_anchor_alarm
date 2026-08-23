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
    fun nmeaPositionAvailability(state:PositionSourceConflictState)=when{
        state.phonePositionOutputEnabled->PositionSourceAvailabilityReason.PHONE_POSITION_OUTPUT_ACTIVE
        else->PositionSourceAvailabilityReason.AVAILABLE
    }

    fun phonePositionOutputAvailability(state:PositionSourceConflictState)=when{
        state.activeAnchorGpsSource==GpsDataSource.NMEA->PositionSourceAvailabilityReason.ACTIVE_ANCHOR_SOURCE_LOCKED
        state.selectedGpsSource==GpsDataSource.NMEA->PositionSourceAvailabilityReason.NMEA_POSITION_SOURCE_ACTIVE
        else->PositionSourceAvailabilityReason.AVAILABLE
    }

    fun canSelectNmeaPosition(state:PositionSourceConflictState)=nmeaPositionAvailability(state)==PositionSourceAvailabilityReason.AVAILABLE
    fun canEnablePhonePositionOutput(state:PositionSourceConflictState)=phonePositionOutputAvailability(state)==PositionSourceAvailabilityReason.AVAILABLE

    /** The conflict applies only to POSITION. Boat depth/wind/heading remain usable. */
    fun canConsumeBoatNonPositionData(@Suppress("UNUSED_PARAMETER") state:PositionSourceConflictState)=true
}
