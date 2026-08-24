package com.yokuli.anchorwatch.runtime.output

import com.yokuli.anchorwatch.domain.vessel.PublicationPolicy
import com.yokuli.anchorwatch.domain.vessel.PublisherOwnershipState
import com.yokuli.anchorwatch.domain.vessel.NmeaSuppressionReason

data class PublicationDecision(val publish:Boolean,val ownership:PublisherOwnershipState,val suppression:NmeaSuppressionReason?=null)

/** Stateful BACKUP takeover/recovery gate. It never averages or rewrites data. */
class PublicationOwnershipGate(private val takeoverDelayMillis:Long,private val recoveryMillis:Long=2_000L){
    private var externalMissingSince:Long?=null;private var externalRecoveredSince:Long?=null;private var phoneActive=false
    fun evaluate(policy:PublicationPolicy,externalPresent:Boolean,sourceConflict:Boolean,now:Long):PublicationDecision=when(policy){
        PublicationPolicy.OFF->{reset();PublicationDecision(false,PublisherOwnershipState.SUPPRESSED,NmeaSuppressionReason.USER_DISABLED)}
        PublicationPolicy.ALWAYS->{phoneActive=true;PublicationDecision(true,if(sourceConflict)PublisherOwnershipState.SOURCE_CONFLICT else PublisherOwnershipState.PHONE_ACTIVE,if(sourceConflict)NmeaSuppressionReason.SOURCE_CONFLICT else null)}
        PublicationPolicy.BACKUP->when{
            phoneActive&&externalPresent&&sourceConflict->{externalRecoveredSince=null;PublicationDecision(true,PublisherOwnershipState.SOURCE_CONFLICT,NmeaSuppressionReason.SOURCE_CONFLICT)}
            phoneActive&&externalPresent->{externalMissingSince=null;if(externalRecoveredSince==null)externalRecoveredSince=now;if(now-(externalRecoveredSince?:now)>=recoveryMillis){phoneActive=false;externalRecoveredSince=null;PublicationDecision(false,PublisherOwnershipState.STANDBY_EXTERNAL_PRESENT,NmeaSuppressionReason.EXTERNAL_SOURCE_PRESENT)}else PublicationDecision(true,PublisherOwnershipState.PHONE_ACTIVE)}
            phoneActive->{externalRecoveredSince=null;PublicationDecision(true,PublisherOwnershipState.PHONE_ACTIVE)}
            externalPresent->{externalMissingSince=null;externalRecoveredSince=null;PublicationDecision(false,PublisherOwnershipState.STANDBY_EXTERNAL_PRESENT,NmeaSuppressionReason.EXTERNAL_SOURCE_PRESENT)}
            else->{externalRecoveredSince=null;if(externalMissingSince==null)externalMissingSince=now;if(now-(externalMissingSince?:now)>=takeoverDelayMillis){phoneActive=true;PublicationDecision(true,PublisherOwnershipState.PHONE_ACTIVE)}else PublicationDecision(false,PublisherOwnershipState.TAKEOVER_PENDING,NmeaSuppressionReason.TAKEOVER_DELAY)}
        }
    }
    fun reset(){externalMissingSince=null;externalRecoveredSince=null;phoneActive=false}
}
