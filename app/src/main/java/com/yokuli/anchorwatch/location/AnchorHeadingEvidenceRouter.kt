package com.yokuli.anchorwatch.location

import com.yokuli.anchorwatch.domain.model.HeadingQuality
import com.yokuli.anchorwatch.domain.model.HeadingSource
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.vessel.VesselSourcePreference
import com.yokuli.anchorwatch.domain.vessel.VesselObservation
import com.yokuli.anchorwatch.domain.vessel.VesselSourceClass
import com.yokuli.anchorwatch.domain.vessel.VesselDataQuality
import com.yokuli.anchorwatch.location.vessel.PhoneVesselMountState

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
    fun routeSelected(enabled:Boolean,preference:VesselSourcePreference,selected:VesselObservation<Double>,phone:PhoneHeadingSample,mountState:PhoneVesselMountState=PhoneVesselMountState.HANDHELD):AnchorHeadingEvidence{
        if(!enabled)return AnchorHeadingEvidence(reason="HEADING_ASSIST_DISABLED")
        val boat=selected.value?.takeIf{selected.sourceClass==VesselSourceClass.BOAT_NMEA}?.let{value->
            AnchorHeadingEvidence(value,HeadingSource.NMEA_PHYSICAL,if(selected.quality==VesselDataQuality.GOOD)HeadingQuality.STABLE else HeadingQuality.DISTURBED,null,selected.receivedElapsedRealtime,"VESSEL_ROUTING_BOAT_HEADING",selected.sourceIdentity?.id)
        }
        val phoneEvidence=phone.trueHeadingDegrees?.takeIf{phone.quality==HeadingQuality.STABLE&&mountState==PhoneVesselMountState.VESSEL_MOUNTED}?.let{AnchorHeadingEvidence(it,HeadingSource.PHONE,phone.quality,phone.epoch,phone.sequence,"PHONE_MOUNTED_INTEGRITY_ACCEPTED","phone:vessel-heading")}
        return when(preference){
            VesselSourcePreference.BOAT->boat?:AnchorHeadingEvidence(reason="SELECTED_BOAT_HEADING_UNAVAILABLE")
            VesselSourcePreference.PHONE->phoneEvidence?:AnchorHeadingEvidence(reason=if(mountState==PhoneVesselMountState.VESSEL_MOUNTED)"SELECTED_PHONE_HEADING_NOT_STABLE" else "PHONE_NOT_VESSEL_MOUNTED")
            VesselSourcePreference.AUTO->boat?:phoneEvidence?:AnchorHeadingEvidence(reason=if(mountState==PhoneVesselMountState.VESSEL_MOUNTED)"AUTO_PHONE_HEADING_NOT_STABLE" else "PHONE_NOT_VESSEL_MOUNTED")
            VesselSourcePreference.DERIVED->AnchorHeadingEvidence(reason="DERIVED_HEADING_NOT_VALID_FOR_ANCHOR_EVIDENCE")
        }
    }

    fun route(enabled:Boolean,preference:VesselSourcePreference,boatFix:NavigationFix,phone:PhoneHeadingSample,mountState:PhoneVesselMountState=PhoneVesselMountState.HANDHELD):AnchorHeadingEvidence{
        if(!enabled)return AnchorHeadingEvidence(reason="HEADING_ASSIST_DISABLED")
        val boat=boatFix.headingTrueDegrees?.takeIf{boatFix.headingSource==HeadingSource.NMEA_PHYSICAL}?.let{AnchorHeadingEvidence(it,HeadingSource.NMEA_PHYSICAL,boatFix.headingQuality,boatFix.headingEpoch,boatFix.headingSampleSequence,"BOAT_PHYSICAL_HEADING",boatFix.sourceSentence)}
        val phoneEvidence=phone.trueHeadingDegrees?.takeIf{phone.quality==HeadingQuality.STABLE&&mountState==PhoneVesselMountState.VESSEL_MOUNTED}?.let{AnchorHeadingEvidence(it,HeadingSource.PHONE,phone.quality,phone.epoch,phone.sequence,"PHONE_MOUNTED_INTEGRITY_ACCEPTED","phone:vessel-heading")}
        return when(preference){
            VesselSourcePreference.BOAT->boat?:AnchorHeadingEvidence(reason="SELECTED_BOAT_HEADING_UNAVAILABLE")
            VesselSourcePreference.PHONE->phoneEvidence?:AnchorHeadingEvidence(reason=if(mountState==PhoneVesselMountState.VESSEL_MOUNTED)"SELECTED_PHONE_HEADING_NOT_STABLE" else "PHONE_NOT_VESSEL_MOUNTED")
            VesselSourcePreference.AUTO->boat?:phoneEvidence?:AnchorHeadingEvidence(reason=if(mountState==PhoneVesselMountState.VESSEL_MOUNTED)"AUTO_PHONE_HEADING_NOT_STABLE" else "PHONE_NOT_VESSEL_MOUNTED")
            VesselSourcePreference.DERIVED->AnchorHeadingEvidence(reason="DERIVED_HEADING_NOT_VALID_FOR_ANCHOR_EVIDENCE")
        }
    }
}
