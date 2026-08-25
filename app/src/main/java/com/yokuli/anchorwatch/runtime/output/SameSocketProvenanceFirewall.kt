package com.yokuli.anchorwatch.runtime.output

import com.yokuli.anchorwatch.domain.vessel.VesselObservation
import com.yokuli.anchorwatch.domain.vessel.VesselProvenance
import com.yokuli.anchorwatch.domain.vessel.VesselSourceClass
import com.yokuli.anchorwatch.domain.vessel.VesselSourceIdentity
import com.yokuli.anchorwatch.domain.vessel.VesselSourceType

/** Why a value was accepted or rejected for the current Boat input socket. */
enum class SameSocketProvenanceReason {
    LOCAL_PHONE_SOURCE,
    LOCAL_ONLY_DERIVED,
    BOAT_INPUT_SOURCE,
    SAME_TRANSPORT_BOAT_SOURCE,
    PHONE_TX_ECHO,
    DERIVED_FROM_BOAT_INPUT,
    DERIVED_ANCESTRY_UNKNOWN,
    NON_LOCAL_SOURCE,
}

data class SameSocketProvenanceDecision(
    val allowed:Boolean,
    val reason:SameSocketProvenanceReason,
)

/**
 * Destination-specific provenance firewall.
 *
 * SAME_AS_INPUT is a local sensor injection path, not a vessel-data fan-out.
 * A value is allowed only when its complete ancestry is demonstrably local.
 * Unknown derived ancestry is rejected deliberately: a missing proof must
 * never become a feedback loop on a bidirectional 0183/N2K gateway.
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
            return when(val detail=observation.provenanceDetail){
                is VesselProvenance.Nmea->denied(SameSocketProvenanceReason.BOAT_INPUT_SOURCE)
                is VesselProvenance.Derived->derivedDecision(detail)
                is VesselProvenance.PhoneSensor,null->allowed(SameSocketProvenanceReason.LOCAL_PHONE_SOURCE)
            }
        }
        if(observation.sourceClass in setOf(VesselSourceClass.DERIVED_WATER,VesselSourceClass.DERIVED_GROUND)){
            val derived=observation.provenanceDetail as? VesselProvenance.Derived
                ?:return denied(SameSocketProvenanceReason.DERIVED_ANCESTRY_UNKNOWN)
            return derivedDecision(derived)
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
    private fun derivedDecision(derived:VesselProvenance.Derived):SameSocketProvenanceDecision{
        if(derived.inputs.isEmpty())return denied(SameSocketProvenanceReason.DERIVED_ANCESTRY_UNKNOWN)
        if(derived.inputs.any{it.sourceType==VesselSourceType.PHONE_TX_ECHO})return denied(SameSocketProvenanceReason.PHONE_TX_ECHO)
        if(derived.inputs.any{it.sourceType==VesselSourceType.NMEA_INPUT})return denied(SameSocketProvenanceReason.DERIVED_FROM_BOAT_INPUT)
        // A nested App-derived identity does not carry its own dependency graph
        // in the current model. Reject it rather than assume safety.
        if(derived.inputs.any{it.sourceType!=VesselSourceType.PHONE_SENSOR})return denied(SameSocketProvenanceReason.DERIVED_ANCESTRY_UNKNOWN)
        return allowed(SameSocketProvenanceReason.LOCAL_ONLY_DERIVED)
    }
    private fun allowed(reason:SameSocketProvenanceReason)=SameSocketProvenanceDecision(true,reason)
    private fun denied(reason:SameSocketProvenanceReason)=SameSocketProvenanceDecision(false,reason)
}
