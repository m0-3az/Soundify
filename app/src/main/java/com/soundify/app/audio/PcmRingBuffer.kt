package com.soundify.app.audio

/**
 * A blocking stereo float PCM ring buffer. The decoder writes, the mixer reads.
 * Frames are interleaved: L, R, L, R...
 */
class PcmRingBuffer(private val capacityFrames: Int) {

    private val lock = Object()
    private val data = FloatArray(capacityFrames * 2)
    private var readFrame = 0
    private var writeFrame = 0
    private var sizeFrames = 0
    private var closed = false
    private var eof = false

    fun write(samples: FloatArray, frames: Int): Boolean {
        if (frames <= 0) return true
        var written = 0
        synchronized(lock) {
            while (written < frames) {
                while (sizeFrames == capacityFrames && !closed) {
                    lock.wait(50)
                }
                if (closed) return false
                val space = capacityFrames - sizeFrames
                val chunk = minOf(space, frames - written)
                var source = written * 2
                var dest = writeFrame * 2
                repeat(chunk * 2) {
                    data[dest] = samples[source]
                    dest++
                    if (dest == data.size) dest = 0
                    source++
                }
                writeFrame = (writeFrame + chunk) % capacityFrames
                sizeFrames += chunk
                written += chunk
                lock.notifyAll()
            }
        }
        return true
    }

    /**
     * Reads up to [frames]. Blocks until the requested number is available,
     * EOF is set, or the buffer is closed. Missing frames are zero-filled.
     * Returns the number of real frames copied, or -1 when closed.
     */
    fun read(destination: FloatArray, frames: Int, zeroFill: Boolean = true): Int {
        synchronized(lock) {
            while (sizeFrames < frames && !closed && !eof) {
                lock.wait(50)
            }
            if (closed) {
                if (zeroFill) java.util.Arrays.fill(destination, 0f)
                return -1
            }
            val available = minOf(frames, sizeFrames)
            var dest = 0
            var source = readFrame * 2
            repeat(available * 2) {
                destination[dest] = data[source]
                dest++
                source++
                if (source == data.size) source = 0
            }
            if (zeroFill && available < frames) {
                java.util.Arrays.fill(destination, available * 2, frames * 2, 0f)
            }
            readFrame = (readFrame + available) % capacityFrames
            sizeFrames -= available
            lock.notifyAll()
            return available
        }
    }

    fun clear() {
        synchronized(lock) {
            readFrame = 0
            writeFrame = 0
            sizeFrames = 0
            eof = false
            lock.notifyAll()
        }
    }

    fun setEof(value: Boolean) {
        synchronized(lock) {
            eof = value
            lock.notifyAll()
        }
    }

    fun isEof(): Boolean = synchronized(lock) { eof }

    fun availableFrames(): Int = synchronized(lock) { sizeFrames }

    fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }
}
