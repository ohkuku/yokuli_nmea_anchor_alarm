package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.runtime.output.LatestPerStreamQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatestPerStreamQueueTest {
    private data class Batch(val stream: String, val sequence: Long)

    @Test
    fun aFastStreamCannotEvictAnotherLogicalStream() {
        val queue = LatestPerStreamQueue<Batch> { it.stream }

        assertNull(queue.offer(Batch("POSITION", 1L)))
        assertNull(queue.offer(Batch("HEADING", 1L)))
        assertEquals(Batch("HEADING", 1L), queue.offer(Batch("HEADING", 2L)))

        assertEquals(2, queue.size())
        assertEquals(Batch("POSITION", 1L), queue.poll())
        assertEquals(Batch("HEADING", 2L), queue.poll())
        assertNull(queue.poll())
    }

    @Test
    fun clearDropsOnlyTheLatestUnsentSnapshotPerStream() {
        val queue = LatestPerStreamQueue<Batch> { it.stream }
        queue.offer(Batch("POSITION", 7L))
        queue.offer(Batch("POSITION", 8L))
        queue.offer(Batch("MOTION", 4L))

        assertEquals(listOf(
            Batch("POSITION", 8L),
            Batch("MOTION", 4L),
        ), queue.clear())
        assertEquals(0, queue.size())
    }
}
