package com.n5nbd.mempuck.atsmini.img.ui

import com.n5nbd.mempuck.atsmini.img.model.ImageSignalState

internal fun shouldLockDecoderSelection(
    signal: ImageSignalState,
    receiverFrameRevision: Long?,
    openedImageActive: Boolean,
    releasedFrameRevision: Long?,
): Boolean =
    !openedImageActive &&
        signal == ImageSignalState.COMPLETE &&
        receiverFrameRevision != null &&
        releasedFrameRevision != receiverFrameRevision
