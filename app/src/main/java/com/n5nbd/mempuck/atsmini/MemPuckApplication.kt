package com.n5nbd.mempuck.atsmini

import android.app.Application
import com.n5nbd.mempuck.atsmini.data.MemoryRepository
import com.n5nbd.mempuck.atsmini.data.NowRepository
import com.n5nbd.mempuck.atsmini.data.RadioRepository
import com.n5nbd.mempuck.atsmini.img.repository.ImageDecoderRepository

class MemPuckApplication : Application() {
    val radioRepository: RadioRepository by lazy { RadioRepository(this) }
    val memoryRepository: MemoryRepository by lazy { MemoryRepository(this) }
    val nowRepository: NowRepository by lazy { NowRepository(this) }
    val imageDecoderRepository: ImageDecoderRepository by lazy { ImageDecoderRepository(this) }
}
