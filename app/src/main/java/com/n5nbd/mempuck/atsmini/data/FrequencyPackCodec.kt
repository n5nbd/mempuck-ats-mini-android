package com.n5nbd.mempuck.atsmini.data

import com.n5nbd.mempuck.atsmini.model.AtsFrequencyPlan
import com.n5nbd.mempuck.atsmini.model.AtsFrequencyRegion
import com.n5nbd.mempuck.atsmini.model.MemoryEntry
import com.n5nbd.mempuck.atsmini.model.RadioMode
import com.n5nbd.mempuck.atsmini.model.USER_FREQUENCY_FILE
import com.n5nbd.mempuck.atsmini.model.normalizeMemoryTags
import org.json.JSONArray
import org.json.JSONObject

data class FrequencyPackMemory(
    val frequencyHz: Long,
    val mode: RadioMode,
    val name: String,
    val tags: String,
    val notes: String,
    val favorite: Boolean,
    val skip: Boolean,
)

data class FrequencyPackDocument(
    val name: String,
    val description: String,
    val memories: List<FrequencyPackMemory>,
)

data class UserFrequencyDocument(
    val memories: List<FrequencyPackMemory>,
    val deletedFrequencies: Set<Long>,
)

object FrequencyPackCodec {
    const val SCHEMA_NAME = "mempuck-frequency-pack"
    const val SCHEMA_VERSION = 1

    fun decode(raw: String): FrequencyPackDocument {
        val document = JSONObject(raw)
        validateHeader(document)
        return FrequencyPackDocument(
            name = document.optString("name").trim(),
            description = document.optString("description").trim(),
            memories = decodeMemories(document.optJSONArray("memories") ?: JSONArray()),
        )
    }

    fun decodeUser(raw: String): UserFrequencyDocument {
        val document = JSONObject(raw)
        validateHeader(document)
        val deleted = document.optJSONArray("deletedFrequencies") ?: JSONArray()
        return UserFrequencyDocument(
            memories = decodeMemories(document.optJSONArray("memories") ?: JSONArray()),
            deletedFrequencies = buildSet {
                for (index in 0 until deleted.length()) {
                    val frequencyHz = deleted.optLong(index, 0L)
                    if (frequencyHz > 0L) add(AtsFrequencyPlan.normalizeReceiverFrequency(frequencyHz))
                }
            },
        )
    }

    fun encode(
        fileName: String,
        entries: List<MemoryEntry>,
        existing: FrequencyPackDocument? = null,
    ): String = baseDocument(
        name = existing?.name?.takeIf(String::isNotBlank) ?: fileName.removeSuffix(".json"),
        description = existing?.description.orEmpty(),
        entries = entries,
    ).toString(2)

    fun encodeUser(
        entries: List<MemoryEntry>,
        deletedFrequencies: Set<Long>,
    ): String {
        val document = baseDocument(
            name = "USER OVERRIDES",
            description = "MemPuck-managed user memories and overrides. Pack files remain unchanged.",
            entries = entries,
        )
        val deleted = JSONArray()
        deletedFrequencies.sorted().forEach(deleted::put)
        document.put("deletedFrequencies", deleted)
        return document.toString(2)
    }

    fun template(): String = JSONObject()
        .put("schema", SCHEMA_NAME)
        .put("version", SCHEMA_VERSION)
        .put("name", "PACK NAME")
        .put("description", "Describe this frequency pack")
        .put(
            "_instructions",
            JSONArray()
                .put("Save a copy under a new .json filename before filling it in.")
                .put("frequencyHz is an integer frequency in hertz.")
                .put("mode is LSB, USB, CW, AM, or FM.")
                .put("tags are space-separated and will be normalized to uppercase #TAGS.")
                .put("favorite and skip are true or false."),
        )
        .put(
            "memoryTemplate",
            JSONObject()
                .put("frequencyHz", 0)
                .put("mode", "AM")
                .put("name", "CHANNEL NAME")
                .put("tags", "#TAG")
                .put("notes", "")
                .put("favorite", false)
                .put("skip", false),
        )
        .put("memories", JSONArray())
        .toString(2)

    private fun validateHeader(document: JSONObject) {
        val schema = document.optString("schema")
        require(schema.isBlank() || schema == SCHEMA_NAME) { "Unsupported frequency-pack schema" }
        val version = document.optInt("version", SCHEMA_VERSION)
        require(version == SCHEMA_VERSION) { "Unsupported frequency-pack version $version" }
    }

    private fun decodeMemories(array: JSONArray): List<FrequencyPackMemory> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val frequencyHz = item.optLong("frequencyHz", 0L)
            if (frequencyHz <= 0L) continue
            val mode = runCatching {
                RadioMode.valueOf(item.optString("mode").trim().uppercase())
            }.getOrNull() ?: continue
            val normalizedFrequency = AtsFrequencyPlan.normalizeReceiverFrequency(frequencyHz)
            val region = AtsFrequencyPlan.regionFor(normalizedFrequency)
            if (region == AtsFrequencyRegion.Unsupported) continue
            if (region == AtsFrequencyRegion.BroadcastFm && mode != RadioMode.FM) continue
            if (region == AtsFrequencyRegion.LowBand && !mode.isLowBandMode) continue
            val name = item.optString("name").trim()
            if (name.isBlank()) continue
            add(
                FrequencyPackMemory(
                    frequencyHz = normalizedFrequency,
                    mode = mode,
                    name = name,
                    tags = normalizeMemoryTags(item.optString("tags")),
                    notes = item.optString("notes").trim(),
                    favorite = item.optBoolean("favorite", false),
                    skip = item.optBoolean("skip", false),
                ),
            )
        }
    }

    private fun baseDocument(
        name: String,
        description: String,
        entries: List<MemoryEntry>,
    ): JSONObject {
        val memories = JSONArray()
        entries.sortedWith(compareBy(MemoryEntry::frequencyHz, MemoryEntry::id)).forEach { entry ->
            memories.put(
                JSONObject()
                    .put("frequencyHz", entry.frequencyHz)
                    .put("mode", entry.mode.name)
                    .put("name", entry.name)
                    .put("tags", normalizeMemoryTags(entry.tags))
                    .put("notes", entry.notes)
                    .put("favorite", entry.favorite)
                    .put("skip", entry.skip),
            )
        }
        return JSONObject()
            .put("schema", SCHEMA_NAME)
            .put("version", SCHEMA_VERSION)
            .put("name", name)
            .put("description", description)
            .put("memories", memories)
    }
}

internal fun FrequencyPackMemory.toMemoryEntry(sourceFile: String): MemoryEntry = MemoryEntry(
    id = frequencyHz,
    frequencyHz = frequencyHz,
    mode = mode,
    name = name,
    tags = tags,
    notes = notes,
    favorite = favorite,
    skip = skip,
    sourceFile = sourceFile,
)
