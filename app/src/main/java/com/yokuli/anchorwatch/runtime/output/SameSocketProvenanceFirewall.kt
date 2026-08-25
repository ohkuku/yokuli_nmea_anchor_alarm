package com.yokuli.anchorwatch.runtime.output

import com.yokuli.anchorwatch.domain.vessel.VesselObservation
import com.yokuli.anchorwatch.domain.vessel.VesselProvenance
import com.yokuli.anchorwatch.domain.vessel.VesselSourceClass
import com.yokuli.anchorwatch.domain.vessel.VesselSourceIdentity
import com.yokuli.anchorwatch.domain.vessel.VesselSourceType

/** Why a value was accepted or rejected for the current Boat input socket. */
enum class SameSocketProvenanceReason {
    LOCAL_PHONE_SOURCE,
    BOAT_INPUT_SOURCE,
    SAME_TRANSPORT_BOAT_SOURCE,
    PHONE_TX_ECHO,
    NON_LOCAL_SOURCE,
}

data class SameSocketProvenanceDecision(
    val allowed:Boolean,
    val reason:SameSocketProvenanceReason,
)

/**
 * Product output provenance firewall. The historical type name predates the
 * strict all-transport source boundary.
 *
 * Every transport is a Phone/App sensor injection path, never a vessel-data
 * fan-out. Direct Boat and TX-echo observations are rejected. Local Phone
 * measurements require demonstrably local ancestry. App calculations that
 * are intentionally publishable are admitted separately by the encoder only
 * with explicit APP_DERIVED identity and derivation provenance.
 */
object SameSocketProvenanceFirewall {
    private val localClasses=setOf(
        VesselSourceClass.PHONE_GNSS,
        VesselSourceClass.PHONE_DEVICE_COMPASS,
        VesselSourceClass.PHONE_VESSEL_HEADING,
        VesselSourceClass.PHONE_IMU,
        VesselSourceClass.PHONE_BAROMETER,
    )

    fun evaluate(
        observation:VesselObservation<*>,
        inputProfileId:String?=null,
        inputTransportGeneration:Long?=null,
    ):SameSocketProvenanceDecision {
        val identity=observation.sourceIdentity
        if(identity?.sourceType==VesselSourceType.PHONE_TX_ECHO){
            return denied(SameSocketProvenanceReason.PHONE_TX_ECHO)
        }
        if(observation.sourceClass==VesselSourceClass.BOAT_NMEA||identity?.sourceType==VesselSourceType.NMEA_INPUT){
            val sameTransport=identity.isCurrentInput(inputProfileId,inputTransportGeneration)
            return denied(if(sameTransport)SameSocketProvenanceReason.SAME_TRANSPORT_BOAT_SOURCE else SameSocketProvenanceReason.BOAT_INPUT_SOURCE)
        }
        if(observation.sourceClass in localClasses){
            return when(observation.provenanceDetail){
                is VesselProvenance.Nmea->denied(SameSocketProvenanceReason.BOAT_INPUT_SOURCE)
                is VesselProvenance.Derived->denied(SameSocketProvenanceReason.NON_LOCAL_SOURCE)
                is VesselProvenance.PhoneSensor,null->allowed(SameSocketProvenanceReason.LOCAL_PHONE_SOURCE)
            }
        }
        return denied(SameSocketProvenanceReason.NON_LOCAL_SOURCE)
    }

    fun evaluate(
        identity:VesselSourceIdentity,
        sourceClass:VesselSourceClass,
        provenance:VesselProvenance?,
        inputProfileId:String?=null,
        inputTransportGeneration:Long?=null,
    )=evaluate(
        VesselObservation(
            value=Unit,
            sourceIdentity=identity,
            sourceClass=sourceClass,
            provenanceDetail=provenance,
        ),
        inputProfileId,
        inputTransportGeneration,
    )

    private fun VesselSourceIdentity?.isCurrentInput(profileId:String?,generation:Long?):Boolean {
        if(this==null)return false
        val profileMatches=profileId==null||transportProfileId==null||transportProfileId==profileId
        val generationMatches=generation==null||connectionGeneration==null||connectionGeneration==generation
        return profileMatches&&generationMatches
    }
    private fun allowed(reason:SameSocketProvenanceReason)=SameSocketProvenanceDecision(true,reason)
    private fun denied(reason:SameSocketProvenanceReason)=SameSocketProvenanceDecision(false,reason)
}
