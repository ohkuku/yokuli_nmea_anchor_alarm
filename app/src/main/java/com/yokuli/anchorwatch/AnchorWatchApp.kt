package com.yokuli.anchorwatch
import android.app.Application
import com.yokuli.anchorwatch.data.diagnostics.IncidentLogger
import com.yokuli.anchorwatch.data.diagnostics.IncidentSeverity
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Google Maps is deliberately initialized by the map composable on demand.
 *
 * Initializing its Play Services dynamite module here blocks every cold start (including
 * background/service-only starts) on devices where Play Services is updating or slow.
 */
@HiltAndroidApp
class AnchorWatchApp : Application() {
    @Inject lateinit var incidentLogger: IncidentLogger

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(750) {
                    runCatching {
                        incidentLogger.recordNow(
                            category = "crash",
                            event = "UNCAUGHT_EXCEPTION",
                            severity = IncidentSeverity.CRITICAL,
                            details = mapOf(
                                "thread" to thread.name,
                                "exception" to error.javaClass.name,
                                "message" to error.message,
                                "stack" to error.stackTraceToString().take(8_000),
                            ),
                        )
                    }
                }
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
