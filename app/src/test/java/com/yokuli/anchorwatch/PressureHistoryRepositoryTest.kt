package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.database.PressureHistoryDao
import com.yokuli.anchorwatch.data.database.PressureHistoryEntity
import com.yokuli.anchorwatch.data.vessel.PressureHistoryRepository
import com.yokuli.anchorwatch.runtime.WallClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PressureHistoryRepositoryTest{
    private class FakeDao(seed:List<PressureHistoryEntity> = emptyList()):PressureHistoryDao{
        val rows=linkedMapOf<Pair<String,Long>,PressureHistoryEntity>().apply{seed.forEach{put(it.sourceStableKey to it.bucketUtcMinute,it)}}
        override suspend fun upsert(value:PressureHistoryEntity){synchronized(rows){rows[value.sourceStableKey to value.bucketUtcMinute]=value}}
        override suspend fun since(sinceUtcMillis:Long)=synchronized(rows){rows.values.filter{it.sampledAtUtcMillis>=sinceUtcMillis}.sortedBy{it.sampledAtUtcMillis}}
        override suspend fun prune(oldestAllowedUtcMillis:Long)=synchronized(rows){val before=rows.size;rows.entries.removeAll{it.value.sampledAtUtcMillis<oldestAllowedUtcMillis};before-rows.size}
        override suspend fun count()=synchronized(rows){rows.size.toLong()}
    }

    @Test fun persistedMinuteSamplesRestoreTrendAfterRepositoryRestart()=runBlocking{
        val now=10_000_000L;val source="nmea:boat-primary:field:WIMDA:3:AIR_PRESSURE:"
        val seed=(0..60).map{minute->val at=now-60*60_000L+minute*60_000L;PressureHistoryEntity(source,at/60_000L,at,1_000.0+minute*.01,"WIMDA")}
        val dao=FakeDao(seed);val repository=PressureHistoryRepository(dao,object:WallClock{override fun currentTimeMillis()=now})
        withTimeout(2_000){repository.historyLoaded.first{it}}
        val trend=repository.trend(source,60*60_000L,now)
        assertNotNull(trend);assertTrue(trend!!.coverage>=.95);assertEquals(.6,trend.changeHpa,.05)
    }

    @Test fun repeatedMeasurementsInOneMinuteRemainOneDatabaseRow()=runBlocking{
        val now=20_000_000L;val dao=FakeDao();val repository=PressureHistoryRepository(dao,object:WallClock{override fun currentTimeMillis()=now})
        withTimeout(2_000){repository.historyLoaded.first{it}}
        repository.record("phone:barometer","Phone barometer",1_012.0,now)
        repository.record("phone:barometer","Phone barometer",1_012.4,now+20_000L)
        // Both writes are intentionally asynchronous. Waiting only for row
        // count can observe the first upsert before the replacement in the
        // same minute has completed, which made this gate scheduler-dependent.
        withTimeout(2_000){while(dao.count()!=1L||dao.rows.values.singleOrNull()?.pressureHpa!=1_012.4)delay(10)}
        assertEquals(1L,dao.count());assertEquals(1_012.4,dao.rows.values.single().pressureHpa,.001)
    }
}
