package com.yokuli.anchorwatch.runtime

interface MonotonicClock { fun elapsedRealtime():Long }
interface WallClock { fun currentTimeMillis():Long }

object SystemMonotonicClock:MonotonicClock { override fun elapsedRealtime()=System.nanoTime()/1_000_000L }
object SystemWallClock:WallClock { override fun currentTimeMillis()=System.currentTimeMillis() }
