package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.PositionProvider
import com.yokuli.anchorwatch.domain.sonar.DepthSentenceType
import com.yokuli.anchorwatch.location.DemoSonarGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoSonarGeneratorTest {
    @Test fun demoSoundingsAreContinuousAndFollowPositionWithoutTeleportingDepth() {
        val generator=DemoSonarGenerator(73L)
        val samples=(0..40).map{index->
            val fix=NavigationFix(
                latitude=-36.8485+index*.000002,
                longitude=174.7633+index*.000002,
                receivedElapsedRealtime=index*1_000L,
                positionProvider=PositionProvider.DEMO,
                sourceSentence="DEMO",
                valid=true,
            )
            generator.observation(fix,index*1_000L)
        }
        assertTrue(samples.all{it.sentenceType==DepthSentenceType.DPT&&it.rawDepthMeters in 2.0..30.0})
        assertTrue(samples.zipWithNext().all{(a,b)->kotlin.math.abs(a.rawDepthMeters-b.rawDepthMeters)<.35})
        assertTrue(samples.map{it.rawDepthMeters}.distinct().size>10)
    }
}
