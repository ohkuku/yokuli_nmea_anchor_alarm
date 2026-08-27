package com.yokuli.anchorwatch.runtime.output

import com.yokuli.anchorwatch.domain.vessel.CandidateValidity
import com.yokuli.anchorwatch.domain.vessel.VesselDataFreshness
import com.yokuli.anchorwatch.domain.vessel.VesselObservation
import com.yokuli.anchorwatch.domain.vessel.VesselProvenance
import com.yokuli.anchorwatch.domain.vessel.VesselSourceClass
import com.yokuli.anchorwatch.domain.vessel.VesselSourceType
import com.yokuli.anchorwatch.location.vessel.PhoneVesselMountState
import com.yokuli.anchorwatch.location.vessel.VesselMountCalibration

data class HeadingPublicationEligibility(val allowed:Boolean,val reason:String)

/** Vessel HDT/HDG is a current mounted-vessel measurement, never a cached
 * handset compass value. Destination/transport cannot weaken this decision. */
object PhoneVesselHeadingPublicationPolicy {
    const val MAX_MEASUREMENT_AGE_MILLIS=2_000L

    fun evaluate(
        observation:VesselObservation<Double>,
        calibration:VesselMountCalibration,
        runtimeMountState:PhoneVesselMountState,
        nowElapsed:Long,
        explicitValidity:CandidateValidity=CandidateValidity.ELIGIBLE,
    ):HeadingPublicationEligibility{
        if(observation.sourceClass!=VesselSourceClass.PHONE_VESSEL_HEADING)return HeadingPublicationEligibility(false,"NOT_PHONE_VESSEL_HEADING")
        if(observation.sourceIdentity?.sourceType!=VesselSourceType.PHONE_SENSOR)return HeadingPublicationEligibility(false,"NOT_DIRECT_PHONE_SENSOR")
        if(!calibration.headingAligned)return HeadingPublicationEligibility(false,"HEADING_ALIGNMENT_REQUIRED")
        if(runtimeMountState!=PhoneVesselMountState.VESSEL_MOUNTED)return HeadingPublicationEligibility(false,if(runtimeMountState==PhoneVesselMountState.MOUNT_SUSPECT)"MOUNT_SUSPECT" else "PHONE_NOT_VESSEL_MOUNTED")
        if(explicitValidity in setOf(CandidateValidity.INVALID,CandidateValidity.DISABLED,CandidateValidity.STALE))return HeadingPublicationEligibility(false,"SOURCE_${explicitValidity.name}")
        if(observation.freshness!=VesselDataFreshness.FRESH)return HeadingPublicationEligibility(false,"HELD_OR_STALE_HEADING")
        val measured=observation.receivedElapsedRealtime?:return HeadingPublicationEligibility(false,"MISSING_MEASUREMENT_TIME")
        if(nowElapsed-measured !in 0L..MAX_MEASUREMENT_AGE_MILLIS)return HeadingPublicationEligibility(false,"PHONE_HEADING_STALE")
        val provenance=observation.provenanceDetail as? VesselProvenance.PhoneSensor?:return HeadingPublicationEligibility(false,"MISSING_PHONE_SENSOR_PROVENANCE")
        if(provenance.calibrationVersion!=calibration.headingAlignmentVersion)return HeadingPublicationEligibility(false,"HEADING_ALIGNMENT_EPOCH_MISMATCH")
        return HeadingPublicationEligibility(true,"CURRENT_VESSEL_MOUNTED_PHONE_HEADING")
    }
}
