package com.n5nbd.mempuck.atsmini.img.repository

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.n5nbd.mempuck.atsmini.img.model.ImageAudioInput
import com.n5nbd.mempuck.atsmini.img.model.ImageDecoderSelection
import com.n5nbd.mempuck.atsmini.img.model.ImageListenResult

class ImageDecoderViewModel(
    private val repository: ImageDecoderRepository,
) : ViewModel() {
    val state = repository.state

    fun selectDecoder(decoder: ImageDecoderSelection) = repository.selectDecoder(decoder)

    fun selectInput(input: ImageAudioInput) = repository.selectInput(input)

    fun startListening(): ImageListenResult = repository.startListening()

    fun stopListening() = repository.stopListening()

    fun clearImage() = repository.clearImage()

    fun microphonePermissionDenied() = repository.microphonePermissionDenied()

    override fun onCleared() {
        repository.stopListening()
        super.onCleared()
    }

    companion object {
        fun factory(repository: ImageDecoderRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(ImageDecoderViewModel::class.java))
                    return ImageDecoderViewModel(repository) as T
                }
            }
    }
}
