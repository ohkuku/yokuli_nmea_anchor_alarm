package com.yokuli.anchorwatch.runtime

import android.os.SystemClock

interface MonotonicClock { fun elapsedRealtime():Long }
interface WallClock { fun currentTimeMillis():Long }

/**
 * The App's authoritative Android monotonic clock.
 *
 * GPS callbacks expose timestamps in the SystemClock.elapsedRealtime domain
 * (CLOCK_BOOTTIME, including deep sleep). System.nanoTime is not a compatible
 * substitute on Android because its suspend-time/origin semantics can differ.
 * Mixing them made every fresh System and NMEA fix look newer than Anchor
 * Runtime's decision clock after the device had slept.
 */
object SystemMonotonicClock:MonotonicClock { override fun elapsedRealtime()=SystemClock.elapsedRealtime() }
object SystemWallClock:WallClock { override fun currentTimeMillis()=System.currentTimeMillis() }
