package com.n5nbd.mempuck.atsmini.model

enum class ActiveMemorySource {
    CURATED,
    NOW,
}

data class NowSourceState(
    val cacheAvailable: Boolean = false,
    val lastDownloadedEpochMillis: Long? = null,
    val sourceLastUpdate: String? = null,
    val sourceRecordCount: Int = 0,
    val activeMemoryCount: Int = 0,
    val busy: Boolean = false,
    val message: String? = null,
)

const val NOW_SOURCE_FILE = "NOW:EIBI"
