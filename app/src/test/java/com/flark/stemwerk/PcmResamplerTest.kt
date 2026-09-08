package com.flark.stemwerk

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import kotlin.math.*

class PcmResamplerTest {
    private fun withTone(rate: Int, frequency: Double, channels: Int = 2,
        test: (PcmAudio, java.io.File) -> Unit) {
        val dir = Files.createTempDirectory("resample-test").toFile()
        try {
            val file = java.io.File(dir, "input.pcm")
            file.outputStream().buffered().use { out ->
                repeat(rate / 5) { frame ->
                    val value = (sin(2 * PI * frequency * frame / rate) * 12000).roundToInt()
                    repeat(channels) { channel ->
                        val sample = if (channel == 0) value else -value
                        out.write(sample and 255); out.write((sample shr 8) and 255)
                    }
                }
            }
            test(PcmAudio(file, rate, channels), java.io.File(dir, "out.pcm"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun preservesPitchDurationAndStereoWhenUpsampling24k() = withTone(24000, 1000.0) { input, file ->
        val output = PcmResampler.convert(input, file)
        assertEquals(8820L, output.frames)
        assertEquals(44100, output.sampleRate)
        PcmReader(output).use { reader ->
            for (frame in 100L until output.frames - 100) {
                val expected = sin(2 * PI * 1000 * frame / 44100) * 12000 / 32768
                assertEquals(expected, reader.sample(frame, 0).toDouble(), 0.002)
                assertEquals(-expected, reader.sample(frame, 1).toDouble(), 0.002)
            }
        }
    }

    @Test fun accepts16kMonoAnd48kStereo() {
        for (rate in listOf(16000, 48000)) withTone(rate, 1000.0, if (rate == 16000) 1 else 2) { input, file ->
            val output = PcmResampler.convert(input, file)
            assertEquals(8820L, output.frames)
            assertEquals(input.channels, output.channels)
            PcmReader(output).use { reader ->
                val actual = reader.sample(1103, 0).toDouble()
                val expected = sin(2 * PI * 1000 * 1103 / 44100) * 12000 / 32768
                assertEquals(expected, actual, 0.003)
            }
        }
    }

    @Test fun removesAliasingWhenDownsampling96k() = withTone(96000, 30000.0, 1) { input, file ->
        val output = PcmResampler.convert(input, file)
        PcmReader(output).use { reader ->
            var power = 0.0
            for (frame in 200L until output.frames - 200) {
                val value = reader.sample(frame, 0)
                power += value * value
            }
            assertTrue("High-frequency alias must be attenuated", sqrt(power / (output.frames - 400)) < 0.002)
        }
    }

    @Test fun sameRateIsBitExact() = withTone(44100, 750.0) { input, file ->
        PcmResampler.convert(input, file)
        assertArrayEquals(input.file.readBytes(), file.readBytes())
    }

    @Test fun canCancelDuringResampling() = withTone(24000, 1000.0) { input, file ->
        var calls = 0
        try {
            PcmResampler.convert(input, file, checkCancelled = {
                if (++calls == 2) throw java.util.concurrent.CancellationException()
            })
            fail("Expected cancellation")
        } catch (_: java.util.concurrent.CancellationException) { assertEquals(2, calls) }
    }
}
