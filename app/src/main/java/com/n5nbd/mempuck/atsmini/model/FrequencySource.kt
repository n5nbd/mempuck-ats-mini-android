package com.n5nbd.mempuck.atsmini.model

data class FrequencySourceFile(
    val name: String,
    val memoryCount: Int,
    val duplicateCount: Int = 0,
    val isUser: Boolean = false,
    val isTemplate: Boolean = false,
    val error: String? = null,
)

data class FrequencySourceState(
    val directoryUri: String? = null,
    val directoryName: String? = null,
    val files: List<FrequencySourceFile> = emptyList(),
    val message: String? = null,
    val busy: Boolean = false,
) {
    val directorySelected: Boolean
        get() = !directoryUri.isNullOrBlank()
}

const val USER_FREQUENCY_FILE = "USER.json"
const val FREQUENCY_TEMPLATE_FILE = "mempuck-frequency-template.json"
