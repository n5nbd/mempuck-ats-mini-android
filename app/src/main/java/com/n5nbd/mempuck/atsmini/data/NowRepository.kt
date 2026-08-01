package com.n5nbd.mempuck.atsmini.data

import android.content.Context
import com.n5nbd.mempuck.atsmini.model.ActiveMemorySource
import com.n5nbd.mempuck.atsmini.model.MemoryEntry
import com.n5nbd.mempuck.atsmini.model.NowSourceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.Charset
import java.time.Clock
import java.util.Locale

class NowRepository(
    context: Context,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val sourceDirectory = File(context.filesDir, SOURCE_DIRECTORY)
    private val cacheFile = File(sourceDirectory, CACHE_FILE)
    private val metadataFile = File(sourceDirectory, METADATA_FILE)

    private val _activeSource = MutableStateFlow(loadActiveSource())
    val activeSource: StateFlow<ActiveMemorySource> = _activeSource.asStateFlow()

    private val _entries = MutableStateFlow<List<MemoryEntry>>(emptyList())
    val entries: StateFlow<List<MemoryEntry>> = _entries.asStateFlow()

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<NowSourceState> = _state.asStateFlow()

    @Synchronized
    fun refresh() {
        require(sourceDirectory.isDirectory || sourceDirectory.mkdirs()) {
            "Unable to create the NOW cache directory"
        }
        _state.value = _state.value.copy(busy = true, message = "DOWNLOADING EIBI")
        val temporary = File(sourceDirectory, "$CACHE_FILE.tmp").apply { delete() }

        runCatching {
            val connection = (URI.create(EIBI_URL).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "MemPuck-for-ATS-Mini")
                instanceFollowRedirects = true
            }
            try {
                require(connection.responseCode in 200..299) {
                    "EiBi download failed: HTTP ${connection.responseCode}"
                }
                connection.inputStream.use { input ->
                    temporary.outputStream().use(input::copyTo)
                }
            } finally {
                connection.disconnect()
            }

            val document = EibiScheduleParser.parse(temporary.readText(EIBI_CHARSET))
            require(document.records.size >= MINIMUM_VALID_RECORDS) {
                "EiBi download contained too few broadcast records"
            }

            replaceCacheFile(temporary)

            val downloadedAt = clock.millis()
            runCatching {
                metadataFile.writeText(
                    JSONObject()
                        .put("downloadedAt", downloadedAt)
                        .put("recordCount", document.records.size)
                        .put("sourceLastUpdate", document.sourceLastUpdate.orEmpty())
                        .toString(2),
                )
            }
            _state.value = NowSourceState(
                cacheAvailable = true,
                lastDownloadedEpochMillis = downloadedAt,
                sourceLastUpdate = document.sourceLastUpdate,
                sourceRecordCount = document.records.size,
                activeMemoryCount = _entries.value.size,
                message = "EIBI CACHE UPDATED",
            )
        }.onFailure { error ->
            temporary.delete()
            _state.value = _state.value.copy(
                busy = false,
                message = (error.message ?: "EiBi download failed").uppercase(Locale.ROOT),
            )
            throw error
        }
    }

    @Synchronized
    fun loadNow(): List<MemoryEntry> {
        require(cacheFile.isFile) { "Download the EiBi source first" }
        _state.value = _state.value.copy(busy = true, message = "BUILDING NOW LIST")
        return runCatching {
            val document = EibiScheduleParser.parse(cacheFile.readText(EIBI_CHARSET))
            require(document.records.isNotEmpty()) { "EiBi cache contained no schedule records" }
            val active = EibiScheduleParser.activeMemories(document, clock.instant())
            _entries.value = active
            setActiveSource(ActiveMemorySource.NOW)
            _state.value = _state.value.copy(
                cacheAvailable = true,
                sourceLastUpdate = document.sourceLastUpdate ?: _state.value.sourceLastUpdate,
                sourceRecordCount = document.records.size,
                activeMemoryCount = active.size,
                busy = false,
                message = "${active.size} ACTIVE FREQUENCIES LOADED",
            )
            active
        }.onFailure { error ->
            _state.value = _state.value.copy(
                busy = false,
                message = (error.message ?: "Unable to build NOW list").uppercase(Locale.ROOT),
            )
        }.getOrThrow()
    }

    @Synchronized
    fun loadCurated() {
        setActiveSource(ActiveMemorySource.CURATED)
        _state.value = _state.value.copy(message = "CURATED SOURCES LOADED")
    }

    @Synchronized
    fun replaceEntry(entry: MemoryEntry) {
        _entries.value = _entries.value.map { existing ->
            if (existing.frequencyHz == entry.frequencyHz) entry else existing
        }
    }

    fun reportError(message: String) {
        _state.value = _state.value.copy(
            busy = false,
            message = message.uppercase(Locale.ROOT),
        )
    }

    private fun replaceCacheFile(temporary: File) {
        val backup = File(sourceDirectory, "$CACHE_FILE.bak")
        backup.delete()

        if (cacheFile.exists()) {
            require(cacheFile.renameTo(backup)) { "Unable to preserve the existing EiBi cache" }
        }

        runCatching {
            require(temporary.renameTo(cacheFile)) { "Unable to store EiBi cache" }
        }.onFailure { error ->
            cacheFile.delete()
            if (backup.exists()) backup.renameTo(cacheFile)
            throw error
        }
        backup.delete()
    }

    private fun setActiveSource(source: ActiveMemorySource) {
        _activeSource.value = source
        preferences.edit().putString(KEY_ACTIVE_SOURCE, source.name).apply()
    }

    private fun loadActiveSource(): ActiveMemorySource = runCatching {
        ActiveMemorySource.valueOf(
            preferences.getString(KEY_ACTIVE_SOURCE, ActiveMemorySource.CURATED.name)
                ?: ActiveMemorySource.CURATED.name,
        )
    }.getOrDefault(ActiveMemorySource.CURATED)

    private fun loadState(): NowSourceState {
        if (!cacheFile.isFile) return NowSourceState()
        val metadata = runCatching { JSONObject(metadataFile.readText()) }.getOrNull()
        return NowSourceState(
            cacheAvailable = true,
            lastDownloadedEpochMillis = metadata?.optLong("downloadedAt")?.takeIf { it > 0L }
                ?: cacheFile.lastModified().takeIf { it > 0L },
            sourceLastUpdate = metadata?.optString("sourceLastUpdate")?.takeIf(String::isNotBlank),
            sourceRecordCount = metadata?.optInt("recordCount") ?: 0,
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "mempuck-now-source"
        const val KEY_ACTIVE_SOURCE = "active-memory-source"
        const val SOURCE_DIRECTORY = "now"
        const val CACHE_FILE = "eibi.txt"
        const val METADATA_FILE = "eibi-metadata.json"
        const val EIBI_URL = "http://eibispace.de/dx/eibi.txt"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val MINIMUM_VALID_RECORDS = 100
        val EIBI_CHARSET: Charset = Charsets.ISO_8859_1
    }
}
