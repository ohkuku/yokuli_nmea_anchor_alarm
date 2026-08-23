package com.yokuli.anchorwatch.location

/**
 * Bounds diagnostic noise without suppressing safety evaluation. Every raw
 * disposition may still update the alarm engine; only durable incident output
 * is collapsed to one row per continuous problem/reason episode.
 */
class PositionFaultEpisodeGate {
    private var activeProblem:Pair<String,String?>?=null

    fun shouldRecord(disposition:String,reason:String?):Boolean {
        val bad=disposition=="REJECTED"||disposition=="QUARANTINED"
        if(!bad){activeProblem=null;return false}
        val next=disposition to reason
        if(next==activeProblem)return false
        activeProblem=next
        return true
    }
}
