package com.yokuli.anchorwatch.domain.vessel

import com.yokuli.anchorwatch.domain.sonar.DepthReference

/** Prevents a depth with an unknown/transducer/keel datum being mislabeled as UKC. */
object UkcCompatibilityPolicy{
    fun calculate(depthMeters:Double?,reference:DepthReference?,draftMeters:Double?):Double?{
        if(depthMeters==null||!depthMeters.isFinite()||depthMeters<=0)return null
        if(reference!=DepthReference.BELOW_SURFACE)return null
        val draft=draftMeters?.takeIf{it.isFinite()&&it>0}?:return null
        return depthMeters-draft
    }
}
