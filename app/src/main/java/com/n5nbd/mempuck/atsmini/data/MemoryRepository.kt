package com.n5nbd.mempuck.atsmini.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.n5nbd.mempuck.atsmini.model.FREQUENCY_TEMPLATE_FILE
import com.n5nbd.mempuck.atsmini.model.FrequencySourceFile
import com.n5nbd.mempuck.atsmini.model.FrequencySourceState
import com.n5nbd.mempuck.atsmini.model.MemoryEntry
import com.n5nbd.mempuck.atsmini.model.RadioMode
import com.n5nbd.mempuck.atsmini.model.USER_FREQUENCY_FILE
import com.n5nbd.mempuck.atsmini.model.normalizeMemoryTags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException

/**
 * Authoritative MemPuck memory storage.
 *
 * USER.json is loaded first and owns all user-created records and overrides. Pack files are
 * read-only inputs loaded alphabetically; duplicate frequencies are ignored. ATS numbered
 * memories are never used here.
 */
class MemoryRepository(private val context: Context) {
    private val resolver = context.contentResolver
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private var userEntries = linkedMapOf<Long, MemoryEntry>()
    private var deletedFrequencies = linkedSetOf<Long>()
    private var packFrequencies = emptySet<Long>()

    private val legacyEntries = loadLegacyEntries()
    private val _entries = MutableStateFlow(legacyEntries)
    val entries: StateFlow<List<MemoryEntry>> = _entries.asStateFlow()

    private val initialDirectoryUri = preferences.getString(KEY_DIRECTORY_URI, null)
    private val _sources = MutableStateFlow(
        FrequencySourceState(
            directoryUri = initialDirectoryUri,
            directoryName = initialDirectoryUri?.let { stored ->
                runCatching { directoryName(Uri.parse(stored)) }.getOrNull()
            },
        ),
    )
    val sources: StateFlow<FrequencySourceState> = _sources.asStateFlow()

    init {
        userEntries.putAll(legacyEntries.associateBy(MemoryEntry::frequencyHz))
    }

    @Synchronized
    fun create(
        frequencyHz: Long,
        mode: RadioMode,
        name: String,
        tags: String,
        notes: String,
        favorite: Boolean,
        skip: Boolean,
    ): MemoryEntry {
        require(_entries.value.none { it.frequencyHz == frequencyHz }) {
            "A memory already exists at this frequency"
        }
        val entry = MemoryEntry(
            id = frequencyHz,
            frequencyHz = frequencyHz,
            mode = mode,
            name = name.trim(),
            tags = normalizeMemoryTags(tags),
            notes = notes.trim(),
            favorite = favorite,
            skip = skip,
            sourceFile = USER_FREQUENCY_FILE,
        )
        userEntries[frequencyHz] = entry
        deletedFrequencies.remove(frequencyHz)
        persistUserChanges("CREATED ${entry.name}")
        return entry
    }

    @Synchronized
    fun update(entry: MemoryEntry) {
        val existing = _entries.value.firstOrNull { it.id == entry.id }
            ?: _entries.value.firstOrNull { it.frequencyHz == entry.frequencyHz }
            ?: error("Memory does not exist")
        require(_entries.value.none {
            it.frequencyHz == entry.frequencyHz && it.frequencyHz != existing.frequencyHz
        }) {
            "A memory already exists at this frequency"
        }

        if (existing.frequencyHz != entry.frequencyHz) {
            userEntries.remove(existing.frequencyHz)
            if (existing.frequencyHz in packFrequencies) {
                deletedFrequencies.add(existing.frequencyHz)
            }
        }

        val override = entry.copy(
            id = entry.frequencyHz,
            name = entry.name.trim(),
            tags = normalizeMemoryTags(entry.tags),
            notes = entry.notes.trim(),
            sourceFile = USER_FREQUENCY_FILE,
        )
        userEntries[override.frequencyHz] = override
        deletedFrequencies.remove(override.frequencyHz)
        persistUserChanges("UPDATED ${override.name}")
    }

    /**
     * Persist a deliberate override for a temporary source record. The selected
     * frequency directory is required so the result is written to USER.json.
     */
    @Synchronized
    fun saveOverride(entry: MemoryEntry): MemoryEntry {
        val treeUri = requireTreeUri()
        val override = entry.copy(
            id = entry.frequencyHz,
            name = entry.name.trim(),
            tags = normalizeMemoryTags(entry.tags),
            notes = entry.notes.trim(),
            sourceFile = USER_FREQUENCY_FILE,
        )
        userEntries[override.frequencyHz] = override
        deletedFrequencies.remove(override.frequencyHz)
        writeUserFile(treeUri)
        refreshSources("SAVED ${override.name}")
        return override
    }

    @Synchronized
    fun delete(id: Long) {
        val existing = _entries.value.firstOrNull { it.id == id } ?: return
        userEntries.remove(existing.frequencyHz)
        if (existing.frequencyHz in packFrequencies) {
            deletedFrequencies.add(existing.frequencyHz)
        } else {
            deletedFrequencies.remove(existing.frequencyHz)
        }
        persistUserChanges("DELETED ${existing.name}")
    }

    @Synchronized
    fun selectDirectory(uri: Uri) {
        resolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        preferences.edit().putString(KEY_DIRECTORY_URI, uri.toString()).apply()
        _sources.value = FrequencySourceState(
            directoryUri = uri.toString(),
            directoryName = directoryName(uri),
            busy = true,
        )

        val existingUser = findChild(uri, USER_FREQUENCY_FILE)?.let { document ->
            runCatching { FrequencyPackCodec.decodeUser(readText(document.uri)) }.getOrNull()
        }
        existingUser?.memories?.forEach { memory ->
            userEntries.putIfAbsent(memory.frequencyHz, memory.toMemoryEntry(USER_FREQUENCY_FILE))
        }
        deletedFrequencies.addAll(existingUser?.deletedFrequencies.orEmpty())
        writeUserFile(uri)
        refreshSources("FREQUENCY DIRECTORY READY")
    }

    @Synchronized
    fun refreshSources(message: String? = null) {
        val treeUri = selectedTreeUri() ?: run {
            _sources.value = FrequencySourceState(message = "SELECT A FREQUENCY DIRECTORY")
            return
        }
        _sources.value = _sources.value.copy(busy = true, message = message)

        runCatching {
            val documents = queryChildren(treeUri)
                .filter { it.name.endsWith(".json", ignoreCase = true) }
                .sortedWith(
                    compareBy<SourceDocument> { !it.name.equals(USER_FREQUENCY_FILE, true) }
                        .thenBy { it.name.lowercase() },
                )

            var userFile = documents.firstOrNull { it.name.equals(USER_FREQUENCY_FILE, true) }
            if (userFile == null) {
                writeUserFile(treeUri)
                userFile = findChild(treeUri, USER_FREQUENCY_FILE)
            }

            val fileStates = mutableListOf<FrequencySourceFile>()
            val seen = linkedSetOf<Long>()
            val merged = mutableListOf<MemoryEntry>()

            if (userFile != null) {
                runCatching {
                    val user = FrequencyPackCodec.decodeUser(readText(userFile.uri))
                    userEntries = user.memories
                        .associateByTo(linkedMapOf(), FrequencyPackMemory::frequencyHz) {
                            it.toMemoryEntry(USER_FREQUENCY_FILE)
                        }
                    deletedFrequencies = user.deletedFrequencies.toCollection(linkedSetOf())
                    userEntries.values.sortedBy(MemoryEntry::frequencyHz).forEach { entry ->
                        if (seen.add(entry.frequencyHz)) merged += entry
                    }
                    fileStates += FrequencySourceFile(
                        name = USER_FREQUENCY_FILE,
                        memoryCount = userEntries.size,
                        isUser = true,
                    )
                }.onFailure { error ->
                    fileStates += FrequencySourceFile(
                        name = USER_FREQUENCY_FILE,
                        memoryCount = 0,
                        isUser = true,
                        error = error.message ?: "INVALID USER FILE",
                    )
                }
            }

            val discoveredPackFrequencies = linkedSetOf<Long>()
            documents.filterNot { it.name.equals(USER_FREQUENCY_FILE, true) }.forEach { document ->
                if (document.name.equals(FREQUENCY_TEMPLATE_FILE, true)) {
                    fileStates += FrequencySourceFile(
                        name = document.name,
                        memoryCount = 0,
                        isTemplate = true,
                    )
                    return@forEach
                }

                runCatching {
                    val pack = FrequencyPackCodec.decode(readText(document.uri))
                    var duplicates = 0
                    pack.memories.forEach { memory ->
                        discoveredPackFrequencies += memory.frequencyHz
                        if (memory.frequencyHz in deletedFrequencies || !seen.add(memory.frequencyHz)) {
                            duplicates += 1
                        } else {
                            merged += memory.toMemoryEntry(document.name)
                        }
                    }
                    fileStates += FrequencySourceFile(
                        name = document.name,
                        memoryCount = pack.memories.size,
                        duplicateCount = duplicates,
                    )
                }.onFailure { error ->
                    fileStates += FrequencySourceFile(
                        name = document.name,
                        memoryCount = 0,
                        error = error.message ?: "INVALID FREQUENCY PACK",
                    )
                }
            }

            packFrequencies = discoveredPackFrequencies
            _entries.value = merged.sortedWith(compareBy(MemoryEntry::frequencyHz, MemoryEntry::id))
            persistLegacy(userEntries.values.toList())
            _sources.value = FrequencySourceState(
                directoryUri = treeUri.toString(),
                directoryName = directoryName(treeUri),
                files = fileStates,
                message = message ?: "${_entries.value.size} MEMORIES LOADED",
            )
        }.onFailure { error ->
            _sources.value = _sources.value.copy(
                busy = false,
                message = error.message ?: "FREQUENCY DIRECTORY ERROR",
            )
        }
    }

    fun reportSourceError(message: String) {
        _sources.value = _sources.value.copy(busy = false, message = message.uppercase())
    }

    @Synchronized
    fun downloadTemplate() {
        val treeUri = requireTreeUri()
        if (findChild(treeUri, FREQUENCY_TEMPLATE_FILE) != null) {
            refreshSources("TEMPLATE ALREADY EXISTS")
            return
        }
        val document = createChild(treeUri, FREQUENCY_TEMPLATE_FILE)
        writeText(document.uri, FrequencyPackCodec.template())
        refreshSources("TEMPLATE WRITTEN")
    }

    @Synchronized
    fun importPack(sourceUri: Uri) {
        val treeUri = requireTreeUri()
        val raw = readText(sourceUri)
        FrequencyPackCodec.decode(raw)
        val requestedName = displayName(sourceUri)
            ?.takeIf { it.endsWith(".json", ignoreCase = true) }
            ?: "frequency-pack.json"
        val safeName = when {
            requestedName.equals(USER_FREQUENCY_FILE, true) -> "imported-user-pack.json"
            requestedName.equals(FREQUENCY_TEMPLATE_FILE, true) -> "imported-template-pack.json"
            else -> requestedName
        }
        val targetName = uniqueChildName(treeUri, safeName)
        writeText(createChild(treeUri, targetName).uri, raw)
        refreshSources("IMPORTED $targetName")
    }

    @Synchronized
    fun exportFile(fileName: String, destinationUri: Uri) {
        val treeUri = requireTreeUri()
        val source = findChild(treeUri, fileName) ?: throw FileNotFoundException(fileName)
        resolver.openInputStream(source.uri).use { input ->
            requireNotNull(input) { "Unable to read $fileName" }
            resolver.openOutputStream(destinationUri, "wt").use { output ->
                requireNotNull(output) { "Unable to export $fileName" }
                input.copyTo(output)
            }
        }
        _sources.value = _sources.value.copy(message = "EXPORTED $fileName")
    }

    @Synchronized
    fun deleteSourceFile(fileName: String) {
        require(!fileName.equals(USER_FREQUENCY_FILE, true)) { "USER.json is managed by MemPuck" }
        val treeUri = requireTreeUri()
        val source = findChild(treeUri, fileName) ?: return
        require(DocumentsContract.deleteDocument(resolver, source.uri)) { "Unable to delete $fileName" }
        refreshSources("DELETED $fileName")
    }

    private fun persistUserChanges(message: String) {
        val treeUri = selectedTreeUri()
        if (treeUri == null) {
            val sorted = userEntries.values.sortedBy(MemoryEntry::frequencyHz)
            _entries.value = sorted
            persistLegacy(sorted)
            return
        }
        writeUserFile(treeUri)
        refreshSources(message)
    }

    private fun writeUserFile(treeUri: Uri) {
        val document = findChild(treeUri, USER_FREQUENCY_FILE)
            ?: createChild(treeUri, USER_FREQUENCY_FILE)
        writeText(
            document.uri,
            FrequencyPackCodec.encodeUser(
                entries = userEntries.values.toList(),
                deletedFrequencies = deletedFrequencies,
            ),
        )
    }

    private fun selectedTreeUri(): Uri? = preferences.getString(KEY_DIRECTORY_URI, null)?.let(Uri::parse)

    private fun requireTreeUri(): Uri = selectedTreeUri()
        ?: error("Select a frequency directory first")

    private fun queryChildren(treeUri: Uri): List<SourceDocument> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return resolver.query(childrenUri, projection, null, null, null).use { cursor ->
            if (cursor == null) return emptyList()
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            buildList {
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(mimeIndex)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val id = cursor.getString(idIndex)
                    add(
                        SourceDocument(
                            name = cursor.getString(nameIndex),
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                        ),
                    )
                }
            }
        }
    }

    private fun findChild(treeUri: Uri, fileName: String): SourceDocument? =
        queryChildren(treeUri).firstOrNull { it.name.equals(fileName, true) }

    private fun createChild(treeUri: Uri, fileName: String): SourceDocument {
        val uri = DocumentsContract.createDocument(
            resolver,
            rootDocumentUri(treeUri),
            JSON_MIME_TYPE,
            fileName,
        ) ?: error("Unable to create $fileName")
        return SourceDocument(fileName, uri)
    }

    private fun uniqueChildName(treeUri: Uri, requestedName: String): String {
        val existing = queryChildren(treeUri).map { it.name.lowercase() }.toSet()
        if (requestedName.lowercase() !in existing) return requestedName
        val stem = requestedName.substringBeforeLast('.', requestedName)
        val extension = requestedName.substringAfterLast('.', "json")
        var counter = 2
        while (true) {
            val candidate = "$stem-$counter.$extension"
            if (candidate.lowercase() !in existing) return candidate
            counter += 1
        }
    }

    private fun readText(uri: Uri): String = resolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "Unable to read file" }
        input.bufferedReader().readText()
    }

    private fun writeText(uri: Uri, text: String) {
        resolver.openOutputStream(uri, "wt").use { output ->
            requireNotNull(output) { "Unable to write file" }
            output.bufferedWriter().use { it.write(text) }
        }
    }

    private fun displayName(uri: Uri): String? = resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    ).use { cursor ->
        if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null
    }

    private fun directoryName(uri: Uri): String? = resolver.query(
        rootDocumentUri(uri),
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
    ).use { cursor ->
        if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null
    }

    private fun rootDocumentUri(treeUri: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )

    private fun loadLegacyEntries(): List<MemoryEntry> {
        val raw = preferences.getString(KEY_DOCUMENT, null) ?: return emptyList()
        return runCatching {
            val document = JSONObject(raw)
            val memories = document.optJSONArray("memories") ?: JSONArray()
            buildList {
                for (index in 0 until memories.length()) {
                    val item = memories.optJSONObject(index) ?: continue
                    val mode = runCatching {
                        RadioMode.valueOf(item.getString("mode"))
                    }.getOrNull() ?: continue
                    val frequencyHz = item.optLong("frequencyHz", 0L)
                    if (frequencyHz <= 0L) continue
                    add(
                        MemoryEntry(
                            id = frequencyHz,
                            frequencyHz = frequencyHz,
                            mode = mode,
                            name = item.optString("name"),
                            tags = normalizeMemoryTags(item.optString("tags")),
                            notes = item.optString("notes"),
                            favorite = item.optBoolean("favorite", false),
                            skip = item.optBoolean("skip", false),
                            sourceFile = USER_FREQUENCY_FILE,
                        ),
                    )
                }
            }.distinctBy(MemoryEntry::frequencyHz)
                .sortedBy(MemoryEntry::frequencyHz)
        }.getOrDefault(emptyList())
    }

    private fun persistLegacy(entries: List<MemoryEntry>) {
        val memories = JSONArray()
        entries.forEach { entry ->
            memories.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("frequencyHz", entry.frequencyHz)
                    .put("mode", entry.mode.name)
                    .put("name", entry.name)
                    .put("tags", entry.tags)
                    .put("notes", entry.notes)
                    .put("favorite", entry.favorite)
                    .put("skip", entry.skip),
            )
        }
        val document = JSONObject()
            .put("schema", LEGACY_SCHEMA_VERSION)
            .put("memories", memories)
        preferences.edit().putString(KEY_DOCUMENT, document.toString()).apply()
    }

    private data class SourceDocument(
        val name: String,
        val uri: Uri,
    )

    private companion object {
        const val PREFERENCES_NAME = "mempuck-memory-library"
        const val KEY_DOCUMENT = "library-json"
        const val KEY_DIRECTORY_URI = "frequency-directory-uri"
        const val LEGACY_SCHEMA_VERSION = 4
        const val JSON_MIME_TYPE = "application/json"
    }
}
