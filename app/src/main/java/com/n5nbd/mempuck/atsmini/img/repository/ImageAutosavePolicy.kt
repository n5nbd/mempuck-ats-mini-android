package com.n5nbd.mempuck.atsmini.img.repository

import com.n5nbd.mempuck.atsmini.img.model.DecodedImageFrame

internal const val MinimumWefaxAutosaveLines = 50

internal fun shouldAutosaveFrame(frame: DecodedImageFrame): Boolean =
    !frame.continuous || frame.completedLines >= MinimumWefaxAutosaveLines
