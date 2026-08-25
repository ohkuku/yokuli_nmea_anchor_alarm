package com.yokuli.anchorwatch.domain.vessel

enum class PublicationPolicy{OFF,BACKUP,ALWAYS}
enum class NmeaOutputPurpose{
    BOAT_BUS_INJECTION,
    /** Restore-only compatibility value. Live output always normalizes to the
     * Phone/App-owned BOAT_BUS_INJECTION contract. */
    CANONICAL_CLIENT_FEED,
}
enum class PublisherOwnershipState{STANDBY_EXTERNAL_PRESENT,TAKEOVER_PENDING,PHONE_ACTIVE,SUPPRESSED,SOURCE_CONFLICT,ERROR}
enum class NmeaStreamReadiness{READY,WAITING_CALIBRATION,WAITING_POSITION,STANDBY,PUBLISHING}
enum class NmeaSentenceFamily{POSITION,HEADING,MOTION,PRESSURE,DERIVED_WIND,PROPRIETARY_STATUS,CANONICAL_FEED}
enum class NmeaSuppressionReason{USER_DISABLED,EXTERNAL_SOURCE_PRESENT,TAKEOVER_DELAY,PHONE_NOT_MOUNTED,MOUNT_SUSPECT,NO_DECLINATION_REFERENCE,PHONE_HEADING_STALE,PHONE_GPS_STALE,NO_DERIVED_WIND,OUTPUT_DISCONNECTED,SOURCE_CONFLICT}

data class NmeaPublishedStreamStatus(
    val family:NmeaSentenceFamily,
    val policy:PublicationPolicy=PublicationPolicy.OFF,
    val ownership:PublisherOwnershipState=PublisherOwnershipState.SUPPRESSED,
    val dataReady:Boolean=false,
    val readiness:NmeaStreamReadiness=NmeaStreamReadiness.STANDBY,
    val suppressionReason:NmeaSuppressionReason?=null,
    val generatedRateHz:Double=0.0,
    val socketWriteRateHz:Double=0.0,
    val lastGeneratedElapsed:Long?=null,
    val lastWrittenElapsed:Long?=null,
    val generatedCount:Long=0,
    val writtenCount:Long=0,
    val droppedCount:Long=0,
    val lastGeneratedSequence:Long=0,
    val lastWrittenSequence:Long=0,
)

enum class NmeaDestinationTransport{DEDICATED_TCP,TCP_SERVER,SAME_AS_INPUT_TCP_SOCKET,UDP_UNICAST,UDP_BROADCAST}
data class NmeaRetryPolicy(val delaysMillis:List<Long> = listOf(1_000,2_000,5_000,10_000,15_000))
data class NmeaOutputDestination(
    val id:String="boat-gateway",
    val name:String="Boat Gateway",
    /** The existing full-duplex Boat connection is the normal Phone→Boat path.
     * Independent TCP/UDP destinations are explicit advanced choices. */
    val transport:NmeaDestinationTransport=NmeaDestinationTransport.SAME_AS_INPUT_TCP_SOCKET,
    val host:String="",
    val port:Int=10110,
    val enabled:Boolean=false,
    val sentenceFilter:Set<NmeaSentenceFamily> = NmeaSentenceFamily.entries.toSet(),
    val retryPolicy:NmeaRetryPolicy=NmeaRetryPolicy(),
)
