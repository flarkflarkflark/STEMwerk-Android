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

        fun leInt(off: Int): Int = ByteBuffer.wrap(bytes, off, 4).order(ByteOrder.LITTLE_ENDIAN).int
        fun leShort(off: Int): Int = ByteBuffer.wrap(bytes, off, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

        while (pos + 8 <= bytes.size) {
            val chunkId = bytes.copyOfRange(pos, pos + 4).toString(Charsets.US_ASCII)
            val chunkSize = leInt(pos + 4)
            val chunkData = pos + 8

            if (chunkData + chunkSize > bytes.size) break

            when (chunkId) {
                "fmt " -> {
                    audioFormat = leShort(chunkData + 0)
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

            // chunks are word-aligned
            pos = chunkData + chunkSize + (chunkSize % 2)
            if (fmtFound && dataFound) break
        }

        if (!fmtFound) throw IllegalArgumentException("WAV missing fmt chunk")
        if (!dataFound) throw IllegalArgumentException("WAV missing data chunk")
        if (audioFormat != 1) throw IllegalArgumentException("Only PCM WAV supported (format=$audioFormat)")
        if (bitsPerSample != 16) throw IllegalArgumentException("Only 16-bit WAV supported (bits=$bitsPerSample)")

        val pcm = bytes.copyOfRange(dataOffset, dataOffset + dataSize)
        val info = WavInfo(sampleRate, channels, bitsPerSample, dataOffset, dataSize)
        return info to pcm
    }

    fun writePcm16Wav(out: OutputStream, info: WavInfo, pcm: ByteArray) {
        val byteRate = info.sampleRate * info.channels * (info.bitsPerSample / 8)
        val blockAlign = info.channels * (info.bitsPerSample / 8)

        val headerSize = 44
        val totalSize = headerSize + pcm.size
        val bb = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)

        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(totalSize - 8)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))

        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1) // PCM
        bb.putShort(info.channels.toShort())
        bb.putInt(info.sampleRate)
        bb.putInt(byteRate)
        bb.putShort(blockAlign.toShort())
        bb.putShort(info.bitsPerSample.toShort())

        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(pcm.size)

        out.write(bb.array())
        out.write(pcm)
    }

    fun writePcm16Wav(outFile: File, info: WavInfo, pcm: ByteArray) {
        outFile.outputStream().use { os ->
            writePcm16Wav(os, info, pcm)
        }
    }
}
