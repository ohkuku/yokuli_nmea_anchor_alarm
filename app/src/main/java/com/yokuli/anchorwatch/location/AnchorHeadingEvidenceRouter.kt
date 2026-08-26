package com.yokuli.anchorwatch.location

import com.yokuli.anchorwatch.domain.model.HeadingQuality
import com.yokuli.anchorwatch.domain.model.HeadingSource
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.vessel.VesselSourcePreference
import com.yokuli.anchorwatch.domain.vessel.VesselObservation
import com.yokuli.anchorwatch.domain.vessel.VesselSourceClass
import com.yokuli.anchorwatch.domain.vessel.VesselDataQuality
import com.yokuli.anchorwatch.domain.vessel.VesselDataFreshness
import com.yokuli.anchorwatch.domain.vessel.VesselReference
import com.yokuli.anchorwatch.domain.vessel.VesselSourceConflict

data class PhoneVesselHeadingAlignment(
    val aligned:Boolean=false,
    val offsetDegrees:Double=0.0,
)

data class AnchorHeadingEvidence(
    val trueDegrees:Double?=null,
    val source:HeadingSource=HeadingSource.NONE,
    val quality:HeadingQuality=HeadingQuality.UNAVAILABLE,
    val epoch:Long?=null,
    val sequence:Long?=null,
    val reason:String,
    val sourceId:String?=null,
)

/** Safety/evidence channel only. It deliberately does not expose the responsive
 * phone presentation heading and never substitutes COG for physical heading. */
object AnchorHeadingEvidenceRouter{
    /** Uses the VesselDataHub-selected boat candidate, while retaining the
     * stricter integrity-gated phone channel for anchor evidence. */
    fun routeSelected(preference:VesselSourcePreference,selected:VesselObservation<Double>,selectionConflict:VesselSourceConflict?,preferenceWasExplicitlyPinned:Boolean,phone:PhoneHeadingSample,alignment:PhoneVesselHeadingAlignment=PhoneVesselHeadingAlignment()):AnchorHeadingEvidence{
        if(preference==VesselSourcePreference.AUTO&&selectionConflict?.active==true&&!preferenceWasExplicitlyPinned)return AnchorHeadingEvidence(reason="AUTO_SOURCE_CONFLICT")
        val boat=selected.value?.takeIf{
            selected.sourceClass==VesselSourceClass.BOAT_NMEA&&
                selected.reference==VesselReference.TrueNorth&&
                selected.freshness==VesselDataFreshness.FRESH&&
                selected.quality==VesselDataQuality.GOOD
        }?.let{value->
            AnchorHeadingEvidence(value,HeadingSource.NMEA_PHYSICAL,HeadingQuality.STABLE,null,selected.receivedElapsedRealtime,"VESSEL_ROUTING_BOAT_PHYSICAL_TRUE_HEADING",selected.sourceIdentity?.id)
        }
        val phoneEvidence=phone.trueHeadingDegrees?.takeIf{phone.quality==HeadingQuality.STABLE&&alignment.aligned}?.let{value->AnchorHeadingEvidence(normalize(value+alignment.offsetDegrees),HeadingSource.PHONE,phone.quality,phone.epoch,phone.sequence,"PHONE_ALIGNED_INTEGRITY_ACCEPTED","phone:vessel-heading")}
        return when(preference){
            VesselSourcePreference.BOAT->boat?:AnchorHeadingEvidence(reason="SELECTED_BOAT_HEADING_UNAVAILABLE")
            VesselSourcePreference.PHONE->phoneEvidence?:AnchorHeadingEvidence(reason=if(alignment.aligned)"SELECTED_PHONE_HEADING_NOT_STABLE" else "PHONE_HEADING_NOT_ALIGNED")
            VesselSourcePreference.AUTO->boat?:phoneEvidence?:AnchorHeadingEvidence(reason=if(alignment.aligned)"AUTO_PHONE_HEADING_NOT_STABLE" else "PHONE_HEADING_NOT_ALIGNED")
            VesselSourcePreference.DERIVED->AnchorHeadingEvidence(reason="DERIVED_HEADING_NOT_VALID_FOR_ANCHOR_EVIDENCE")
        }
    }

    fun route(preference:VesselSourcePreference,boatFix:NavigationFix,phone:PhoneHeadingSample,alignment:PhoneVesselHeadingAlignment=PhoneVesselHeadingAlignment()):AnchorHeadingEvidence{
        val boat=boatFix.headingTrueDegrees?.takeIf{boatFix.headingSource==HeadingSource.NMEA_PHYSICAL}?.let{AnchorHeadingEvidence(it,HeadingSource.NMEA_PHYSICAL,boatFix.headingQuality,boatFix.headingEpoch,boatFix.headingSampleSequence,"BOAT_PHYSICAL_HEADING",boatFix.sourceSentence)}
        val phoneEvidence=phone.trueHeadingDegrees?.takeIf{phone.quality==HeadingQuality.STABLE&&alignment.aligned}?.let{value->AnchorHeadingEvidence(normalize(value+alignment.offsetDegrees),HeadingSource.PHONE,phone.quality,phone.epoch,phone.sequence,"PHONE_ALIGNED_INTEGRITY_ACCEPTED","phone:vessel-heading")}
        return when(preference){
            VesselSourcePreference.BOAT->boat?:AnchorHeadingEvidence(reason="SELECTED_BOAT_HEADING_UNAVAILABLE")
            VesselSourcePreference.PHONE->phoneEvidence?:AnchorHeadingEvidence(reason=if(alignment.aligned)"SELECTED_PHONE_HEADING_NOT_STABLE" else "PHONE_HEADING_NOT_ALIGNED")
            VesselSourcePreference.AUTO->boat?:phoneEvidence?:AnchorHeadingEvidence(reason=if(alignment.aligned)"AUTO_PHONE_HEADING_NOT_STABLE" else "PHONE_HEADING_NOT_ALIGNED")
            VesselSourcePreference.DERIVED->AnchorHeadingEvidence(reason="DERIVED_HEADING_NOT_VALID_FOR_ANCHOR_EVIDENCE")
        }
    }
    private fun normalize(value:Double)=((value%360.0)+360.0)%360.0
}
