package com.yokuli.anchorwatch.domain.trip

import com.yokuli.anchorwatch.data.database.TripSessionEntity

object TripSessionTiming{
    fun pendingPausedMillis(pausedAt:Long?,nowUtcMillis:Long)=pausedAt?.let{(nowUtcMillis-it).coerceAtLeast(0)}?:0L
    fun end(session:TripSessionEntity,nowUtcMillis:Long)=session.copy(
        active=false,
        paused=false,
        pausedAt=null,
        endedAt=nowUtcMillis,
        accumulatedPausedMillis=session.accumulatedPausedMillis+pendingPausedMillis(session.pausedAt,nowUtcMillis),
        eventCount=session.eventCount+1,
    )
}
