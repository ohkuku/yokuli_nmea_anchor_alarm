package com.yokuli.anchorwatch.runtime.output

import com.yokuli.anchorwatch.domain.vessel.VesselObservation
import com.yokuli.anchorwatch.domain.vessel.VesselProvenance
import com.yokuli.anchorwatch.domain.vessel.VesselSourceCandidate
import com.yokuli.anchorwatch.domain.vessel.VesselSourceIdentity
import com.yokuli.anchorwatch.domain.vessel.VesselSourceType
import com.yokuli.anchorwatch.domain.vessel.persistentKey

data class PublicationProvenanceDecision(
    val allowed:Boolean,
    val reason:String,
    val phoneLeafKeys:Set<String> = emptySet(),
)

/** Recursive allow-list. Unknown or incomplete ancestry fails closed. */
object PhoneOwnedPublicationProvenancePolicy {
    fun evaluate(observation:VesselObservation<*>,candidates:List<VesselSourceCandidate<*>>):PublicationProvenanceDecision{
        val identity=observation.sourceIdentity?:return deny("MISSING_SOURCE_IDENTITY")
        return when(identity.sourceType){
            VesselSourceType.PHONE_SENSOR->if(observation.provenanceDetail is VesselProvenance.PhoneSensor)allow(identity)else deny("MISSING_PHONE_SENSOR_PROVENANCE")
            VesselSourceType.APP_DERIVED->{
                val derived=observation.provenanceDetail as? VesselProvenance.Derived?:return deny("MISSING_DERIVED_PROVENANCE")
                evaluateDerived(identity,derived,candidates,mutableSetOf())
            }
            VesselSourceType.NMEA_INPUT->deny("NMEA_INPUT_ANCESTOR")
            VesselSourceType.PHONE_TX_ECHO->deny("PHONE_TX_ECHO_ANCESTOR")
            VesselSourceType.DEMO->deny("DEMO_ANCESTOR")
        }
    }

    private fun evaluateDerived(identity:VesselSourceIdentity,derived:VesselProvenance.Derived,candidates:List<VesselSourceCandidate<*>>,visiting:MutableSet<String>):PublicationProvenanceDecision{
        val key=identity.persistentKey
        if(!visiting.add(key))return deny("PROVENANCE_CYCLE")
        if(derived.inputs.isEmpty())return deny("DERIVED_INPUTS_MISSING")
        val leaves=linkedSetOf<String>()
        for(input in derived.inputs){
            when(input.sourceType){
                VesselSourceType.PHONE_SENSOR->{
                    val leaf=candidates.firstOrNull{it.source.persistentKey==input.persistentKey}
                    if(leaf?.provenance !is VesselProvenance.PhoneSensor){visiting.remove(key);return deny("UNPROVEN_PHONE_SENSOR_ANCESTOR")}
                    leaves+=input.persistentKey
                }
                VesselSourceType.NMEA_INPUT->{visiting.remove(key);return deny("NMEA_INPUT_ANCESTOR")}
                VesselSourceType.PHONE_TX_ECHO->{visiting.remove(key);return deny("PHONE_TX_ECHO_ANCESTOR")}
                VesselSourceType.DEMO->{visiting.remove(key);return deny("DEMO_ANCESTOR")}
                VesselSourceType.APP_DERIVED->{
                    val parent=candidates.firstOrNull{it.source.persistentKey==input.persistentKey}?:run{visiting.remove(key);return deny("UNKNOWN_DERIVED_ANCESTOR")}
                    val parentDerived=parent.provenance as? VesselProvenance.Derived?:run{visiting.remove(key);return deny("MISSING_NESTED_PROVENANCE")}
                    val nested=evaluateDerived(parent.source,parentDerived,candidates,visiting)
                    if(!nested.allowed){visiting.remove(key);return nested}
                    leaves+=nested.phoneLeafKeys
                }
            }
        }
        visiting.remove(key)
        return if(leaves.isEmpty())deny("NO_PHONE_SENSOR_ANCESTOR")else PublicationProvenanceDecision(true,"PHONE_ONLY_RECURSIVE_PROVENANCE",leaves)
    }

    private fun allow(identity:VesselSourceIdentity)=PublicationProvenanceDecision(true,"DIRECT_PHONE_SENSOR",setOf(identity.persistentKey))
    private fun deny(reason:String)=PublicationProvenanceDecision(false,reason)
}
