package com.yokuli.anchorwatch.data.diagnostics

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.google.gson.Gson
import com.yokuli.anchorwatch.BuildConfig
import com.yokuli.anchorwatch.data.database.AnchorDao
import com.yokuli.anchorwatch.data.database.AppDatabase
import com.yokuli.anchorwatch.data.database.IncidentLogDao
import com.yokuli.anchorwatch.data.database.IncidentLogEntity
import com.yokuli.anchorwatch.data.database.SonarDao
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.data.sonar.SonarIncrementalGridUpdater
import com.yokuli.anchorwatch.runtime.RuntimeDiagnosticsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class IncidentSeverity { INFO, WARNING, CRITICAL }

/**
 * Safety-event logger with a 72-hour/10,000-row ring limit. Values whose names
 * could contain precise positions, raw sentences or credentials are dropped at
 * this boundary so callers cannot accidentally put them in a support archive.
 */
@Singleton
class IncidentLogger @Inject constructor(private val dao: IncidentLogDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var insertsSincePrune = 0
    val recent = dao.recent()

    init { scope.launch { mutex.withLock { dao.deleteOlderThan(System.currentTimeMillis() - RETENTION_MILLIS);dao.trimToRows(MAX_ROWS) } } }

    fun record(
        category: String,
        event: String,
        severity: IncidentSeverity = IncidentSeverity.INFO,
        sessionId: Long? = null,
        details: Map<String, Any?> = emptyMap(),
    ) = scope.launch { recordNow(category, event, severity, sessionId, details) }

    suspend fun recordNow(
        category: String,
        event: String,
        severity: IncidentSeverity = IncidentSeverity.INFO,
        sessionId: Long? = null,
        details: Map<String, Any?> = emptyMap(),
    ) = mutex.withLock {
        val now = System.currentTimeMillis()
        dao.insert(
            IncidentLogEntity(
                timestamp = now,
                elapsedRealtime = SystemClock.elapsedRealtime(),
                severity = severity.name,
                category = token(category),
                event = token(event),
                sessionId = sessionId,
                details = Gson().toJson(sanitize(details)),
            )
        )
        insertsSincePrune++
        if (insertsSincePrune >= PRUNE_EVERY) {
            dao.deleteOlderThan(now - RETENTION_MILLIS)
            dao.trimToRows(MAX_ROWS)
            insertsSincePrune = 0
        }
    }

    fun exception(category: String, event: String, error: Throwable, sessionId: Long? = null) = record(
        category = category,
        event = event,
        severity = IncidentSeverity.CRITICAL,
        sessionId = sessionId,
        details = mapOf(
            "exception" to error.javaClass.name,
            "message" to error.message?.take(500),
            "stack" to error.stackTraceToString().take(8_000),
        ),
    )

    private fun sanitize(values: Map<String, Any?>): Map<String, Any?> = values.entries
        .filterNot { (key, _) -> FORBIDDEN_KEYS.any { key.contains(it, ignoreCase = true) } }
        .take(32)
        .associate { (key, value) -> token(key) to safeValue(value) }

    private fun safeValue(value: Any?): Any? = when (value) {
        null, is Boolean, is Number -> value
        else -> value.toString().take(1_000)
            .replace(Regex("\\$[A-Z]{2}[A-Z0-9]{3}[^\\r\\n]*"), "[REDACTED_NMEA]")
            .replace(Regex("(?i)(key|token|password)=([^&;\\s]+)"), "\$1=[REDACTED]")
            .replace(Regex("-?\\d{1,2}\\.\\d{5,}\\s*[,/]\\s*-?\\d{1,3}\\.\\d{5,}"), "[REDACTED_COORDINATE]")
    }

    private fun token(value: String) = value.replace(Regex("[^A-Za-z0-9_.:-]"), "_").take(80)

    companion object {
        const val RETENTION_MILLIS = 72L * 60L * 60L * 1_000L
        const val MAX_ROWS = 10_000
        private const val PRUNE_EVERY = 50
        private val FORBIDDEN_KEYS = listOf("latitude", "longitude", "coordinate", "raw", "sentence", "api_key", "apikey", "token", "password")
    }
}

data class StorageHealth(
    val databaseBytes: Long = 0,
    val offlineMapBytes: Long = 0,
    val cacheBytes: Long = 0,
    val freeBytes: Long = 0,
    val anchorSessions: Long = 0,
    val trackPoints: Long = 0,
    val sonarSamples: Long = 0,
    val sonarGridCells: Long = 0,
    val incidentRows: Long = 0,
)

data class SupportBundleState(
    val running: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

@Singleton
class StorageHealthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val anchorDao: AnchorDao,
    private val sonarDao: SonarDao,
    private val incidentDao: IncidentLogDao,
    private val sonarGridUpdater: SonarIncrementalGridUpdater,
) {
    suspend fun snapshot(): StorageHealth = withContext(Dispatchers.IO) {
        val db = context.getDatabasePath("anchor-watch.db")
        StorageHealth(
            databaseBytes = listOf(db, File(db.path + "-wal"), File(db.path + "-shm")).sumOf { it.takeIf(File::exists)?.length() ?: 0L },
            offlineMapBytes = directoryBytes(File(context.filesDir, "offline_maps")),
            cacheBytes = directoryBytes(context.cacheDir),
            freeBytes = context.filesDir.usableSpace,
            anchorSessions = anchorDao.sessionCount(),
            trackPoints = anchorDao.pointCount(),
            sonarSamples = sonarDao.rawSampleCount(),
            sonarGridCells = sonarDao.gridCellCount(),
            incidentRows = incidentDao.count(),
        )
    }

    suspend fun clearRebuildableCaches() = withContext(Dispatchers.IO) {
        database.runInTransaction {
            database.openHelper.writableDatabase.execSQL("DELETE FROM sonar_grid_cells")
            database.openHelper.writableDatabase.execSQL("DELETE FROM linz_depth_cache")
            database.openHelper.writableDatabase.execSQL("DELETE FROM tide_prediction_cache")
        }
        File(context.filesDir, "offline_maps/linz_recent").deleteRecursively()
        context.cacheDir.listFiles()?.forEach { file -> if (file.isDirectory) file.deleteRecursively() else file.delete() }
        sonarGridUpdater.rebuildMissing()
    }

    suspend fun clearIncidentLog() = withContext(Dispatchers.IO) { incidentDao.clear() }

    private fun directoryBytes(file: File): Long = when {
        !file.exists() -> 0L
        file.isFile -> file.length()
        else -> file.listFiles()?.sumOf(::directoryBytes) ?: 0L
    }
}

/** Exports only operational metadata. It deliberately excludes raw NMEA and exact positions. */
@Singleton
class SupportBundleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val incidents: IncidentLogDao,
    private val settings: SettingsRepository,
    private val runtime: RuntimeDiagnosticsRepository,
    private val storage: StorageHealthRepository,
) {
    private val gson = Gson()
    private val _state = MutableStateFlow(SupportBundleState())
    val state = _state.asStateFlow()

    suspend fun export(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) { runCatching {
        _state.value = SupportBundleState(running = true, message = "Building privacy-safe diagnostics…")
        val now = System.currentTimeMillis()
        val recent = incidents.since(now - IncidentLogger.RETENTION_MILLIS)
        val appSettings = settings.settings.first()
        val runtimeState = runtime.state.value
        val storageState = storage.snapshot()
        val output = context.contentResolver.openOutputStream(uri, "w") ?: error("Android could not open the selected diagnostics file")
        output.use { raw -> ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
            write(zip, "manifest.json", gson.toJson(mapOf(
                "format" to "YOKULI_SUPPORT_BUNDLE",
                "formatVersion" to 1,
                "createdAtUtc" to Instant.ofEpochMilli(now).toString(),
                "appVersionName" to BuildConfig.VERSION_NAME,
                "appVersionCode" to BuildConfig.VERSION_CODE,
                "roomSchemaVersion" to 11,
                "privacy" to "No raw NMEA, API credentials, or exact positions",
                "incidentCount" to recent.size,
            )))
            write(zip, "device.json", gson.toJson(mapOf(
                "manufacturer" to android.os.Build.MANUFACTURER,
                "model" to android.os.Build.MODEL,
                "sdk" to android.os.Build.VERSION.SDK_INT,
                "batteryPercent" to context.getSystemService(android.os.BatteryManager::class.java).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY),
            )))
            write(zip, "configuration.json", gson.toJson(mapOf(
                "gpsSource" to appSettings.gpsDataSource.name,
                "demoMode" to appSettings.demoMode,
                "nmeaSharingEnabled" to appSettings.nmeaSharingEnabled,
                "keepWifiAwake" to appSettings.keepWifiAwake,
                "alarmSound" to appSettings.alarmSound.name,
                "sonarLayerEnabled" to appSettings.sonarLayerEnabled,
                "linzHydroEnabled" to appSettings.linzHydroEnabled,
                "offlineMapEnabled" to appSettings.offlineMapEnabled,
            )))
            write(zip, "runtime.json", gson.toJson(runtimeState))
            write(zip, "storage.json", gson.toJson(storageState))
            write(zip, "incidents.ndjson", recent.joinToString("\n") { gson.toJson(it) })
            write(zip, "README.txt", "This bundle is designed for Yokuli support. It excludes raw NMEA, API keys and exact vessel positions. Review it before sharing.\n")
        } }
        _state.value = SupportBundleState(message = "Diagnostics exported successfully.")
    }.onFailure { _state.value = SupportBundleState(error = it.message ?: "Diagnostics export failed") }.map { Unit } }

    fun clearResult() { _state.value = SupportBundleState() }

    private fun write(zip: ZipOutputStream, name: String, value: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(value.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
