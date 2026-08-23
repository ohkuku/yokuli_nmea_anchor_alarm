package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.database.TripSampleEntity
import com.yokuli.anchorwatch.data.trip.TripSampleBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripSampleBufferTest{
    @Test fun boundedQueueDropsOldestAndReportsTheLoss(){
        val buffer=TripSampleBuffer(2)
        assertTrue(!buffer.enqueue(sample(1)))
        assertTrue(!buffer.enqueue(sample(2)))
        assertTrue(buffer.enqueue(sample(3)))
        val batch=buffer.take()
        assertEquals(listOf(2L,3L),batch.values.map{it.timestamp})
        assertEquals(1L,batch.dropped)
    }

    @Test fun failedBatchIsRestoredAheadOfSamplesThatArrivedDuringTheWrite(){
        val buffer=TripSampleBuffer(3)
        buffer.enqueue(sample(1));buffer.enqueue(sample(2))
        val failed=buffer.take()
        buffer.enqueue(sample(3))
        buffer.restore(failed)
        assertEquals(listOf(1L,2L,3L),buffer.take().values.map{it.timestamp})
    }

    @Test fun restoreStillKeepsMemoryBoundedAndRetainsNewestSamples(){
        val buffer=TripSampleBuffer(2)
        buffer.enqueue(sample(1));buffer.enqueue(sample(2))
        val failed=buffer.take()
        buffer.enqueue(sample(3));buffer.enqueue(sample(4))
        buffer.restore(failed)
        val restored=buffer.take()
        assertEquals(listOf(3L,4L),restored.values.map{it.timestamp})
        assertEquals(2L,restored.dropped)
    }

    private fun sample(timestamp:Long)=TripSampleEntity(
        tripId=1,timestamp=timestamp,latitude=null,longitude=null,
        positionSource="NONE",positionQuality="UNAVAILABLE",positionAgeMillis=null,
        sogKnots=null,cogTrueDegrees=null,headingTrueDegrees=null,headingSource="NONE",headingAgeMillis=null,
        depthMeters=null,depthSource="NONE",depthAgeMillis=null,speedThroughWaterKnots=null,stwSource=null,stwAgeMillis=null,
        trueWindSpeedKnots=null,trueWindDirectionDegrees=null,trueWindAngleDegrees=null,
        apparentWindSpeedKnots=null,apparentWindAngleDegrees=null,windSource=null,windAgeMillis=null,
        heelDegrees=null,pitchDegrees=null,rollRateDegPerSec=null,pitchRateDegPerSec=null,yawRateDegPerSec=null,
        motionScore=null,rollPeriodSeconds=null,rollPeriodConfidence=null,attitudeAgeMillis=null,
        pressureHpa=null,pressureAgeMillis=null,ukcMeters=null,
    )
}
