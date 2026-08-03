package com.n5nbd.mempuck.atsmini.img.ui

import com.n5nbd.mempuck.atsmini.img.model.ImageSignalState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDecoderUiPolicyTest {
    @Test
    fun completedReceiverImageLocksModeButtons() {
        assertTrue(
            shouldLockDecoderSelection(
                signal = ImageSignalState.COMPLETE,
                receiverFrameRevision = 42L,
                openedImageActive = false,
                releasedFrameRevision = null,
            ),
        )
    }

    @Test
    fun saveOrNewListenReleasesTheCurrentFrame() {
        assertFalse(
            shouldLockDecoderSelection(
                signal = ImageSignalState.COMPLETE,
                receiverFrameRevision = 42L,
                openedImageActive = false,
                releasedFrameRevision = 42L,
            ),
        )
    }

    @Test
    fun galleryImageDoesNotLockDecoderSelection() {
        assertFalse(
            shouldLockDecoderSelection(
                signal = ImageSignalState.COMPLETE,
                receiverFrameRevision = 42L,
                openedImageActive = true,
                releasedFrameRevision = null,
            ),
        )
    }
}
