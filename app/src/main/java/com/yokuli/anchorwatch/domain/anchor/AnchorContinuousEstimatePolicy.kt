package com.yokuli.anchorwatch.domain.anchor

data class AnchorContinuousEstimateDecision(val canCompareAndAdopt:Boolean,val notify:Boolean)

/** A latest estimate is advisory. Alarm/drag states gate adoption, while shift
 * thresholds only throttle proactive notifications. */
object AnchorContinuousEstimatePolicy{
    fun evaluate(
        highAndObservable:Boolean,
        alarmBlocksRefinement:Boolean,
        dragTrendReported:Boolean,
        adoptedCentreResolved:Boolean,
        notificationAlreadyShown:Boolean,
        meaningfulShift:Boolean,
        meaningfulUncertaintyImprovement:Boolean,
    ):AnchorContinuousEstimateDecision{
        val ready=highAndObservable&&!alarmBlocksRefinement&&!dragTrendReported
        val notify=ready&&!notificationAlreadyShown&&(!adoptedCentreResolved||meaningfulShift||meaningfulUncertaintyImprovement)
        return AnchorContinuousEstimateDecision(ready,notify)
    }
}
