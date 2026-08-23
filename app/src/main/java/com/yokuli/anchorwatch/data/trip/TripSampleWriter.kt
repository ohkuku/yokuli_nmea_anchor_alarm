package com.yokuli.anchorwatch.data.trip

import com.yokuli.anchorwatch.data.database.TripDao
import com.yokuli.anchorwatch.data.database.TripSampleEntity
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

data class TripWriterResult(val written:Int=0,val dropped:Long=0,val writeFailed:Boolean=false)

internal data class PendingTripBatch(val values:List<TripSampleEntity>,val dropped:Long)

/** Thread-safe, bounded queue kept separate so the failure/requeue policy is unit-testable. */
internal class TripSampleBuffer(private val capacity:Int){
    private val queue=ArrayDeque<TripSampleEntity>();private var dropped=0L

    @Synchronized fun enqueue(value:TripSampleEntity):Boolean{
        var overflow=false
        if(queue.size>=capacity){queue.removeFirst();dropped++;overflow=true}
        queue.addLast(value)
        return overflow
    }

    @Synchronized fun size()=queue.size

    @Synchronized fun take():PendingTripBatch{
        val batch=PendingTripBatch(queue.toList(),dropped)
        queue.clear();dropped=0
        return batch
    }

    @Synchronized fun restore(batch:PendingTripBatch){
        // Samples may have arrived while Room was writing. Restore the failed
        // batch in timestamp order, then keep the newest bounded window.
        val combined=ArrayDeque<TripSampleEntity>(batch.values.size+queue.size)
        batch.values.forEach(combined::addLast)
        queue.forEach(combined::addLast)
        var overflow=0L
        while(combined.size>capacity){combined.removeFirst();overflow++}
        queue.clear();combined.forEach(queue::addLast)
        dropped+=batch.dropped+overflow
    }
}

/** Bounded batch buffer; database latency can never grow memory without limit. */
@Singleton
class TripSampleWriter @Inject constructor(private val dao:TripDao){
    private val buffer=TripSampleBuffer(MAX_QUEUE)
    fun enqueue(value:TripSampleEntity)=buffer.enqueue(value)
    fun size()=buffer.size()
    suspend fun flush():TripWriterResult{
        val batch=buffer.take()
        if(batch.values.isEmpty())return TripWriterResult(dropped=batch.dropped)
        return try{
            dao.insertSamples(batch.values)
            TripWriterResult(batch.values.size,batch.dropped)
        }catch(failure:Exception){
            buffer.restore(batch)
            if(failure is CancellationException)throw failure
            TripWriterResult(writeFailed=true)
        }
    }
    companion object{const val MAX_QUEUE=120;const val FLUSH_SIZE=20;const val FLUSH_MILLIS=5_000L;const val MIN_FLUSH_RETRY_MILLIS=1_000L}
}
