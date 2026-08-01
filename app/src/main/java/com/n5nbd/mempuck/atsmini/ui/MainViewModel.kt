package com.n5nbd.mempuck.atsmini.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.n5nbd.mempuck.atsmini.data.MemoryRepository
import com.n5nbd.mempuck.atsmini.data.NowRepository
import com.n5nbd.mempuck.atsmini.data.RadioRepository
import com.n5nbd.mempuck.atsmini.model.ActiveMemorySource
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(
    private val repository: RadioRepository,
    private val memoryRepository: MemoryRepository,
    private val nowRepository: NowRepository,
) : ViewModel() {
    val state = repository.state
    val frequencySources = memoryRepository.sources
    val activeMemorySource = nowRepository.activeSource
    val nowSource = nowRepository.state
    val memories = combine(
        memoryRepository.entries,
        nowRepository.entries,
        nowRepository.activeSource,
    ) { curated, now, active ->
        if (active == ActiveMemorySource.NOW) now else curated
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = if (nowRepository.activeSource.value == ActiveMemorySource.NOW) {
            nowRepository.entries.value
        } else {
            memoryRepository.entries.value
        },
    )

    private var memoryScanJob: Job? = null
    private var memoryScanGeneration = 0L
    private var scanDwellMs = DEFAULT_SCAN_DWELL_MS
    private val _memoryScanDirection = MutableStateFlow(0)
    val memoryScanDirection = _memoryScanDirection.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runSourceOperation { memoryRepository.refreshSources() }
            if (nowRepository.activeSource.value == ActiveMemorySource.NOW) {
                runCatching { nowRepository.loadNow() }.onFailure { error ->
                    nowRepository.loadCurated()
                    nowRepository.reportError(error.message ?: "Unable to restore NOW source")
                }
            }
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
            memories = memories.value.filter { it.id in memoryIds },
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
        if (memories.value.none { it.id in allowedMemoryIds && it.scanEnabled }) return

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
                        val candidates = memories.value.filter {
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

                            val settled = repository.state.first { tuned ->
                                tuned.link !is LinkState.Ready ||
                                    tuned.capability !is CapabilityState.Supported ||
                                    tuned.tuneState !is TuneState.Sending
                            }
                            if (settled.tuneState !is TuneState.Confirmed) break
                        }
                    }
                    // Dwell is the listener's decision window. Start it only after
                    // the receiver confirms the requested band, mode, and frequency.
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
        val created = memoryRepository.create(
            frequencyHz = frequencyHz,
            mode = mode,
            name = name,
            tags = tags,
            notes = notes,
            favorite = favorite,
            skip = skip,
        )
        if (activeMemorySource.value == ActiveMemorySource.NOW &&
            nowRepository.entries.value.any { it.frequencyHz == created.frequencyHz }
        ) {
            nowRepository.replaceEntry(created)
        }
    }

    fun updateMemory(entry: MemoryEntry) {
        if (activeMemorySource.value == ActiveMemorySource.NOW) {
            runCatching {
                val saved = memoryRepository.saveOverride(entry)
                nowRepository.replaceEntry(saved)
            }.onFailure { error ->
                nowRepository.reportError(error.message ?: "Unable to save NOW memory")
            }
        } else {
            memoryRepository.update(entry)
        }
    }

    fun deleteMemory(id: Long) {
        if (activeMemorySource.value == ActiveMemorySource.NOW) {
            nowRepository.reportError("Temporary NOW memories cannot be deleted")
        } else {
            memoryRepository.delete(id)
        }
    }

    fun loadNowSource() = launchNowOperation {
        stopMemoryScan()
        nowRepository.loadNow()
    }

    fun loadCuratedSource() = launchNowOperation {
        stopMemoryScan()
        nowRepository.loadCurated()
        memoryRepository.refreshSources("CURATED SOURCES LOADED")
    }

    fun refreshNowSource() = launchNowOperation {
        nowRepository.refresh()
        if (nowRepository.activeSource.value == ActiveMemorySource.NOW) {
            nowRepository.loadNow()
        }
    }

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

    private fun launchNowOperation(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching(block).onFailure { error ->
                nowRepository.reportError(error.message ?: "NOW source error")
            }
        }
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
            nowRepository: NowRepository,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(MainViewModel::class.java))
                    return MainViewModel(repository, memoryRepository, nowRepository) as T
                }
            }
    }
}
