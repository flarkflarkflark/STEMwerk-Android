package com.flark.stemwerk

import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal WAV (RIFF) utilities for PCM16LE. */
object WavUtil {

    data class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Int,
        val dataSize: Int,
    )

    fun sniffWav(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val riff = bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII)
        val wave = bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII)
        return riff == "RIFF" && wave == "WAVE"
    }

    fun parsePcm16(bytes: ByteArray): Pair<WavInfo, ByteArray> {
        if (!sniffWav(bytes)) throw IllegalArgumentException("Not a WAV file (missing RIFF/WAVE)")

        var pos = 12
        var fmtFound = false
        var dataFound = false

        var audioFormat = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0

        var dataOffset = 0
        var dataSize = 0

        fun leInt(off: Int): Int =
            ByteBuffer.wrap(bytes, off, 4).order(ByteOrder.LITTLE_ENDIAN).int

        fun leShort(off: Int): Int =
            ByteBuffer.wrap(bytes, off, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

        while (pos + 8 <= bytes.size) {
            val chunkId = bytes.copyOfRange(pos, pos + 4).toString(Charsets.US_ASCII)
            val chunkSize = leInt(pos + 4)
            val chunkData = pos + 8
            if (chunkSize < 0 || chunkData > bytes.size - chunkSize) break

            when (chunkId) {
                "fmt " -> {
                    if (chunkSize < 16) throw IllegalArgumentException("WAV fmt chunk is truncated")
                    audioFormat = leShort(chunkData)
                    channels = leShort(chunkData + 2)
                    sampleRate = leInt(chunkData + 4)
                    bitsPerSample = leShort(chunkData + 14)
                    fmtFound = true
                }
                "data" -> {
                    dataOffset = chunkData
                    dataSize = chunkSize
                    dataFound = true
                }
            }

            pos = chunkData + chunkSize + (chunkSize % 2)
            if (fmtFound && dataFound) break
        }

        if (!fmtFound) throw IllegalArgumentException("WAV missing fmt chunk")
        if (!dataFound) throw IllegalArgumentException("WAV missing data chunk")
        if (audioFormat != 1) throw IllegalArgumentException("Only PCM WAV supported (format=$audioFormat)")
        if (bitsPerSample != 16) throw IllegalArgumentException("Only 16-bit WAV supported (bits=$bitsPerSample)")
        if (channels !in 1..2) throw IllegalArgumentException("Only mono or stereo WAV supported (channels=$channels)")
        require(sampleRate in 8000..192000) { "Unsupported WAV sample rate" }
        require(dataSize % (2 * channels) == 0) { "Incomplete WAV frame" }

        val pcm = bytes.copyOfRange(dataOffset, dataOffset + dataSize)
        return WavInfo(sampleRate, channels, bitsPerSample, dataOffset, dataSize) to pcm
    }

    fun writePcm16WavHeader(out: OutputStream, info: WavInfo, pcmSize: Long) {
        require(pcmSize in 0..(0xFFFFFFFFL - 36)) { "PCM output is too large for a RIFF WAV" }

        val byteRate = info.sampleRate * info.channels * (info.bitsPerSample / 8)
        val blockAlign = info.channels * (info.bitsPerSample / 8)
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt((36L + pcmSize).toInt())
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1)
        bb.putShort(info.channels.toShort())
        bb.putInt(info.sampleRate)
        bb.putInt(byteRate)
        bb.putShort(blockAlign.toShort())
        bb.putShort(info.bitsPerSample.toShort())
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(pcmSize.toInt())
        out.write(bb.array())
    }

    fun writePcm16Wav(out: OutputStream, info: WavInfo, pcm: ByteArray) {
        writePcm16WavHeader(out, info, pcm.size.toLong())
        out.write(pcm)
    }

    fun writePcm16Wav(outFile: File, info: WavInfo, pcm: ByteArray) {
        outFile.outputStream().use { os ->
            writePcm16Wav(os, info, pcm)
        }
    }
}
