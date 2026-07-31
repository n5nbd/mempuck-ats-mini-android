package com.n5nbd.mempuck.atsmini.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.n5nbd.mempuck.atsmini.data.MemoryRepository
import com.n5nbd.mempuck.atsmini.data.RadioRepository
import com.n5nbd.mempuck.atsmini.model.AtsDevice
import com.n5nbd.mempuck.atsmini.model.CapabilityState
import com.n5nbd.mempuck.atsmini.model.LinkState
import com.n5nbd.mempuck.atsmini.model.MemoryEntry
import com.n5nbd.mempuck.atsmini.model.RadioMode
import com.n5nbd.mempuck.atsmini.model.TuneState
import com.n5nbd.mempuck.atsmini.model.memoryStepTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(
    private val repository: RadioRepository,
    private val memoryRepository: MemoryRepository,
) : ViewModel() {
    val state = repository.state
    val memories = memoryRepository.entries
    val frequencySources = memoryRepository.sources

    private var memoryScanJob: Job? = null
    private var memoryScanGeneration = 0L
    private var scanDwellMs = DEFAULT_SCAN_DWELL_MS
    private val _memoryScanDirection = MutableStateFlow(0)
    val memoryScanDirection = _memoryScanDirection.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runSourceOperation { memoryRepository.refreshSources() }
        }
    }

    fun startAutoConnect() = repository.startAutoConnect()
    fun startScan() = repository.startScan()
    fun stopScan() = repository.stopScan()
    fun connect(device: AtsDevice) = repository.connect(device)
    fun disconnect() {
        stopMemoryScan()
        repository.disconnect()
    }

    fun probeCapability() = repository.probeCapability()
    fun tuneFrequency(frequencyHz: Long) = repository.tuneFrequency(frequencyHz)
    fun recallMemory(entry: MemoryEntry) {
        stopTuneScan()
        repository.recallMemory(entry.frequencyHz, entry.mode)
    }

    fun selectLowBandMode(mode: RadioMode) = repository.selectLowBandMode(mode)

    fun setScanDwellMs(value: Long) {
        scanDwellMs = value
        repository.setScanDwellMs(value)
    }

    fun startVfoScan(stepHz: Long) {
        stopMemoryScan()
        repository.startVfoScan(stepHz)
    }

    fun stopVfoScan() = repository.stopVfoScan()

    fun stepMemory(direction: Int, memoryIds: Set<Long>) {
        if (direction == 0) return
        stopTuneScan()
        val target = memoryStepTarget(
            memories = memoryRepository.entries.value.filter { it.id in memoryIds },
            currentFrequencyHz = repository.state.value.targetFrequencyHz,
            direction = direction,
        ) ?: return
        repository.recallMemory(target.frequencyHz, target.mode)
    }

    fun startMemoryScan(direction: Int, memoryIds: Set<Long>) {
        val normalizedDirection = direction.compareTo(0)
        if (normalizedDirection == 0) return
        val allowedMemoryIds = memoryIds.toSet()

        val current = repository.state.value
        if (current.link !is LinkState.Ready || current.capability !is CapabilityState.Supported) return
        if (memoryRepository.entries.value.none { it.id in allowedMemoryIds && it.scanEnabled }) return

        repository.stopVfoScan()
        stopMemoryScan()
        val generation = ++memoryScanGeneration
        _memoryScanDirection.value = normalizedDirection
        memoryScanJob = viewModelScope.launch {
            try {
                var lastSingleEntryId: Long? = null
                while (isActive) {
                    val snapshot = repository.state.value
                    if (snapshot.link !is LinkState.Ready || snapshot.capability !is CapabilityState.Supported) break

                    if (snapshot.tuneState !is TuneState.Sending) {
                        val candidates = memoryRepository.entries.value.filter {
                            it.id in allowedMemoryIds && it.scanEnabled
                        }
                        if (candidates.isEmpty()) break
                        val target = memoryStepTarget(
                            memories = candidates,
                            currentFrequencyHz = snapshot.targetFrequencyHz,
                            direction = normalizedDirection,
                        ) ?: break

                        if (candidates.size > 1 || target.id != lastSingleEntryId) {
                            repository.recallMemory(target.frequencyHz, target.mode)
                            lastSingleEntryId = target.id
                        }
                    }
                    delay(scanDwellMs)
                }
            } finally {
                if (memoryScanGeneration == generation) {
                    _memoryScanDirection.value = 0
                    memoryScanJob = null
                }
            }
        }
    }

    fun stopMemoryScan() {
        memoryScanGeneration += 1L
        memoryScanJob?.cancel()
        memoryScanJob = null
        _memoryScanDirection.value = 0
    }

    fun stopTuneScan() {
        repository.stopVfoScan()
        stopMemoryScan()
    }

    fun setVolume(volume: Int) = repository.setVolume(volume)

    fun createMemory(
        frequencyHz: Long,
        mode: RadioMode,
        name: String,
        tags: String,
        notes: String,
        favorite: Boolean,
        skip: Boolean,
    ) {
        memoryRepository.create(
            frequencyHz = frequencyHz,
            mode = mode,
            name = name,
            tags = tags,
            notes = notes,
            favorite = favorite,
            skip = skip,
        )
    }

    fun updateMemory(entry: MemoryEntry) = memoryRepository.update(entry)
    fun deleteMemory(id: Long) = memoryRepository.delete(id)


    fun selectFrequencyDirectory(uri: Uri) = launchSourceOperation {
        memoryRepository.selectDirectory(uri)
    }

    fun refreshFrequencySources() = launchSourceOperation {
        memoryRepository.refreshSources()
    }

    fun downloadFrequencyTemplate() = launchSourceOperation {
        memoryRepository.downloadTemplate()
    }

    fun importFrequencyPack(uri: Uri) = launchSourceOperation {
        memoryRepository.importPack(uri)
    }

    fun exportFrequencyFile(fileName: String, destinationUri: Uri) = launchSourceOperation {
        memoryRepository.exportFile(fileName, destinationUri)
    }

    fun deleteFrequencyFile(fileName: String) = launchSourceOperation {
        memoryRepository.deleteSourceFile(fileName)
    }

    private fun launchSourceOperation(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { runSourceOperation(block) }
    }

    private fun runSourceOperation(block: () -> Unit) {
        runCatching(block).onFailure { error ->
            memoryRepository.reportSourceError(error.message ?: "Frequency source error")
        }
    }

    override fun onCleared() {
        stopMemoryScan()
        super.onCleared()
    }

    companion object {
        private const val DEFAULT_SCAN_DWELL_MS = 2_000L

        fun factory(
            repository: RadioRepository,
            memoryRepository: MemoryRepository,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(MainViewModel::class.java))
                    return MainViewModel(repository, memoryRepository) as T
                }
            }
    }
}
