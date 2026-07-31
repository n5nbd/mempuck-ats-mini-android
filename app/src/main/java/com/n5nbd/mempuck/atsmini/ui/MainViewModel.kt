package com.n5nbd.mempuck.atsmini.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.n5nbd.mempuck.atsmini.data.RadioRepository
import com.n5nbd.mempuck.atsmini.model.AtsDevice
import com.n5nbd.mempuck.atsmini.model.RadioMode

class MainViewModel(private val repository: RadioRepository) : ViewModel() {
    val state = repository.state

    fun startScan() = repository.startScan()
    fun stopScan() = repository.stopScan()
    fun connect(device: AtsDevice) = repository.connect(device)
    fun disconnect() = repository.disconnect()
    fun probeCapability() = repository.probeCapability()
    fun tune(frequencyHz: Long, mode: RadioMode) = repository.tune(frequencyHz, mode)

    companion object {
        fun factory(repository: RadioRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(MainViewModel::class.java))
                    return MainViewModel(repository) as T
                }
            }
    }
}
