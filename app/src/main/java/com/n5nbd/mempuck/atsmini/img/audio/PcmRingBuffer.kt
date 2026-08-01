package com.n5nbd.mempuck.atsmini.img.audio

/**
 * Bounded mono PCM cache used only for a user-started IMG listening session.
 *
 * The cache is memory-backed for the short SSTV hardware slice. It is preserved after STOP so
 * the same capture can later be replayed through another decoder, and is discarded by CLEAR or
 * the next LISTEN session. Nothing is written to storage.
 */
class PcmRingBuffer(
    capacitySamples: Int,
) {
    private val samples = ShortArray(capacitySamples.coerceAtLeast(1))
    private var writeIndex = 0

    var size: Int = 0
        private set

    val capacity: Int
        get() = samples.size

    fun append(source: ShortArray, count: Int) {
        val safeCount = count.coerceIn(0, source.size)
        if (safeCount == 0) return

        val skip = (safeCount - capacity).coerceAtLeast(0)
        for (index in skip until safeCount) {
            samples[writeIndex] = source[index]
            writeIndex = (writeIndex + 1) % capacity
            if (size < capacity) size += 1
        }
    }

    fun clear() {
        writeIndex = 0
        size = 0
    }

    /** Returns the retained samples in chronological order for a future replay decoder. */
    fun snapshot(): ShortArray {
        val result = ShortArray(size)
        if (size == 0) return result

        val first = if (size == capacity) writeIndex else 0
        val firstPart = minOf(size, capacity - first)
        samples.copyInto(
            destination = result,
            destinationOffset = 0,
            startIndex = first,
            endIndex = first + firstPart,
        )
        if (firstPart < size) {
            samples.copyInto(
                destination = result,
                destinationOffset = firstPart,
                startIndex = 0,
                endIndex = size - firstPart,
            )
        }
        return result
    }
}
