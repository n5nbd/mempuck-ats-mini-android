package com.n5nbd.mempuck.atsmini

import android.app.Application
import com.n5nbd.mempuck.atsmini.data.RadioRepository

class MemPuckApplication : Application() {
    val radioRepository: RadioRepository by lazy { RadioRepository(this) }
}
