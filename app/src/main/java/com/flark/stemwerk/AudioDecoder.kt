package com.flark.stemwerk

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Local decoding; callers own the work directory and remove it after each job. */
class AudioDecoder(private val context: Context) {
    fun decode(
        uri: Uri,
        work: File,
        checkCancelled: () -> Unit,
        onLog: (String) -> Unit,
        progress: (Int) -> Unit,
    ): PcmAudio {
        // Copy once so cloud document providers and non-seekable streams work too.
        val source = File(work, "source.audio")
        context.contentResolver.openInputStream(uri)?.use { input ->
            source.outputStream().buffered().use { out ->
                val buffer = ByteArray(262144)
                while (true) {
                    checkCancelled()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
            }
        } ?: error("Cannot open audio")
        val raw = File(work, "decoded.pcm")
        val decoded = decodeWav16(source, raw, checkCancelled)
            ?: decodeMedia(source, raw, checkCancelled, progress)
        require(decoded.frames > 0) { "Audio contains no samples" }
        onLog("Decoded " + decoded.sampleRate + " Hz / " + decoded.channels + " channel(s)")
        source.delete()
        return decoded
    }

    /** PCM16 WAV bypasses device codec quirks, including 24 kHz recordings. */
    private fun decodeWav16(source: File, raw: File, checkCancelled: () -> Unit): PcmAudio? {
        RandomAccessFile(source, "r").use { input ->
            if (input.length() < 12) return null
            fun tag(): String = ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII)
            fun u32(): Long = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
            fun u16(): Int = java.lang.Short.reverseBytes(input.readShort()).toInt() and 65535
            if (tag() != "RIFF") return null
            u32()
            if (tag() != "WAVE") return null
            var rate = 0
            var channels = 0
            var bits = 0
            var format = 0
            var dataAt = -1L
            var dataSize = 0L
            while (input.filePointer + 8 <= input.length()) {
                val name = tag()
                val size = u32()
                val start = input.filePointer
                require(size <= input.length() - start) { "Truncated WAV chunk" }
                when (name) {
                    "fmt " -> {
                        require(size >= 16) { "Truncated WAV format" }
                        format = u16()
                        channels = u16()
                        rate = u32().toInt()
                        u32(); u16()
                        bits = u16()
                    }
                    "data" -> if (dataAt < 0) { dataAt = start; dataSize = size }
                }
                input.seek(start + size + size % 2)
            }
            if (format != 1 || bits != 16) return null
            require(rate in 8000..192000 && channels in 1..2) { "Use mono or stereo audio at 8–192 kHz" }
            require(dataAt >= 0 && dataSize > 0 && dataSize % (2 * channels) == 0L) {
                "Missing or incomplete WAV samples"
            }
            input.seek(dataAt)
            raw.outputStream().buffered().use { out ->
                val bytes = ByteArray(262144)
                var remaining = dataSize
                while (remaining > 0) {
                    checkCancelled()
                    val n = minOf(bytes.size.toLong(), remaining).toInt()
                    input.readFully(bytes, 0, n)
                    out.write(bytes, 0, n)
                    remaining -= n
                }
            }
            return PcmAudio(raw, rate, channels)
        }
    }

    private fun decodeMedia(
        source: File, raw: File, checkCancelled: () -> Unit, progress: (Int) -> Unit,
    ): PcmAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No supported audio track found")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Missing audio codec")
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            decoder.configure(format, null, null, 0)
            decoder.start()
            started = true
            var rate = 0
            var channels = 0
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var inputEnded = false
            var outputEnded = false
            var lastActivity = SystemClock.elapsedRealtime()
            var lastPct = -1
            val info = MediaCodec.BufferInfo()
            raw.outputStream().buffered().use { out ->
                while (!outputEnded) {
                    checkCancelled()
                    if (!inputEnded) {
                        val index = decoder.dequeueInputBuffer(10000)
                        if (index >= 0) {
                            val buffer = decoder.getInputBuffer(index) ?: error("Missing codec input buffer")
                            buffer.clear()
                            val n = extractor.readSampleData(buffer, 0)
                            if (n < 0) {
                                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                decoder.queueInputBuffer(index, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                            lastActivity = SystemClock.elapsedRealtime()
                        }
                    }
                    val index = decoder.dequeueOutputBuffer(info, 10000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val output = decoder.outputFormat
                        val nextRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val nextChannels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(nextRate in 8000..192000 && nextChannels in 1..2) {
                            "Use mono or stereo audio at 8–192 kHz"
                        }
                        require(rate == 0 || (rate == nextRate && channels == nextChannels)) {
                            "Changing audio format within a file is unsupported"
                        }
                        rate = nextRate
                        channels = nextChannels
                        encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    } else if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                require(rate > 0 && channels > 0) { "Decoder omitted audio format" }
                                val buffer = decoder.getOutputBuffer(index) ?: error("Missing codec output")
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val pcm = toPcm16(buffer.slice().order(ByteOrder.LITTLE_ENDIAN), encoding)
                                require(pcm.size % (channels * 2) == 0) { "Incomplete decoded frame" }
                                out.write(pcm)
                                val pct = if (duration > 0) (info.presentationTimeUs * 100 / duration).toInt().coerceIn(0, 100) else 0
                                if (pct != lastPct) { progress(pct); lastPct = pct }
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            lastActivity = SystemClock.elapsedRealtime()
                        } finally {
                            decoder.releaseOutputBuffer(index, false)
                        }
                    }
                    check(SystemClock.elapsedRealtime() - lastActivity < 30000) { "Audio decoder timed out" }
                }
            }
            require(rate > 0 && channels > 0) { "Decoder returned no audio format" }
            return PcmAudio(raw, rate, channels)
        } finally {
            if (started) runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private fun toPcm16(buffer: ByteBuffer, encoding: Int): ByteArray {
        if (encoding == AudioFormat.ENCODING_PCM_16BIT)
            return ByteArray(buffer.remaining()).also { buffer.get(it) }
        val size = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> error("Unsupported decoded PCM encoding: " + encoding)
        }
        require(buffer.remaining() % size == 0) { "Truncated decoded sample" }
        val bytes = ByteArray(buffer.remaining() / size * 2)
        var at = 0
        while (buffer.hasRemaining()) {
            val value = when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    val value = buffer.float
                    require(value.isFinite()) { "Invalid decoded audio" }
                    (value * 32768).toInt().coerceIn(-32768, 32767)
                }
                AudioFormat.ENCODING_PCM_32BIT -> buffer.int shr 16
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                    buffer.get()
                    (buffer.get().toInt() and 255) or (buffer.get().toInt() shl 8)
                }
                else -> ((buffer.get().toInt() and 255) - 128) shl 8
            }
            bytes[at++] = value.toByte()
            bytes[at++] = (value shr 8).toByte()
        }
        return bytes
    }
}
