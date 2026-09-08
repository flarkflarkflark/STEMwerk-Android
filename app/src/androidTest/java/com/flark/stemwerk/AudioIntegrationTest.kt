package com.flark.stemwerk

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Runs the actual Android codecs and the exact app inference implementation. */
@RunWith(AndroidJUnit4::class)
class AudioIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test fun testPlaybackWaveformSeekingAndTrackSwitch() {
        val ctx = instrumentation.targetContext
        val source = File(ctx.cacheDir, "player-tone.wav")
        instrumentation.context.assets.open("tone24.wav").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        val activity = instrumentation.startActivitySync(android.content.Intent(ctx, PlayerActivity::class.java).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val uri = Uri.fromFile(source).toString()
            putExtra("audioUris", arrayOf(uri, uri))
            putExtra("labels", arrayOf("Test audio — Original", "Test audio — vocals"))
            putExtra("groups", arrayOf("same", "same"))
        }) as PlayerActivity
        try {
            fun waitFor(condition: () -> Boolean) {
                val deadline = android.os.SystemClock.elapsedRealtime() + 20000
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    var ready = false
                    instrumentation.runOnMainSync { ready = condition() }
                    if (ready) return
                    Thread.sleep(100)
                }
                fail("Player timed out")
            }
            waitFor {
                activity.findViewById<android.widget.Button>(R.id.playerPlay).isEnabled &&
                    activity.findViewById<WaveformView>(R.id.playerWaveform).peaks.isNotEmpty()
            }
            instrumentation.runOnMainSync {
                assertTrue(activity.findViewById<WaveformView>(R.id.playerWaveform).peaks.any { it > 0.01f })
                activity.findViewById<android.widget.Button>(R.id.playerPlay).performClick()
            }
            waitFor { activity.findViewById<android.widget.SeekBar>(R.id.playerSeek).progress > 150 }
            instrumentation.runOnMainSync {
                activity.findViewById<android.widget.Button>(R.id.playerPlay).performClick()
                activity.findViewById<WaveformView>(R.id.playerWaveform).onSeek?.invoke(0.5f)
            }
            waitFor { activity.findViewById<android.widget.SeekBar>(R.id.playerSeek).progress in 400..650 }
            instrumentation.runOnMainSync {
                activity.findViewById<android.widget.Spinner>(R.id.playerTracks).setSelection(1)
            }
            waitFor {
                activity.findViewById<android.widget.TextView>(R.id.playerStatus).text.toString().endsWith("vocals") &&
                    activity.findViewById<WaveformView>(R.id.playerWaveform).peaks.isNotEmpty() &&
                    activity.findViewById<android.widget.SeekBar>(R.id.playerSeek).progress in 400..650
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                File(ctx.getExternalFilesDir(null), "player-preview.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            source.delete()
        }
    }
    private fun cacheVocalModel(): File {
        val ctx = instrumentation.targetContext
        val model = ModelManager.MODELS.first()
        val cache = File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "models").also { it.mkdirs() }
        return File(cache, model.file).also { file ->
            instrumentation.context.assets.open(model.file).use { input -> file.outputStream().use { input.copyTo(it) } }
        }
    }

    @Test fun testAccelerationReportHasCpuAndActualNnapiVerdict() {
        val cache = cacheVocalModel()
        try {
            val lines = mutableListOf<String>()
            AccelerationProbe(instrumentation.targetContext).run(ModelManager.TWO_STEMS, {}, lines::add)
            assertTrue(lines.any { it.startsWith("CPU mean:") })
            assertTrue(lines.any { it.contains("NNAPI ACCELERATION CONFIRMED") ||
                it.contains("NO ACCELERATION CONFIRMED") || it.contains("NNAPI UNAVAILABLE OR FAILED") ||
                it.contains("OUTPUT CHECK FAILED") })
            assertTrue(lines.last().contains("Test complete"))
        } finally { cache.delete() }
    }

    @Test fun testBatchContinuesAfterBadFileAndSeparatesDuplicateNames() {
        val ctx = instrumentation.targetContext
        val cache = cacheVocalModel()
        val dir = File(ctx.cacheDir, "batch-fixtures").also { it.mkdirs() }
        var activity: ProcessingActivity? = null
        try {
            val bad = File(dir, "broken.mp3").also { it.writeText("corrupt") }
            val a = File(File(dir, "a").also { it.mkdirs() }, "same.wav")
            val b = File(File(dir, "b").also { it.mkdirs() }, "same.wav")
            for (file in listOf(a, b)) {
                instrumentation.context.assets.open("tone24.wav").use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
            }
            activity = instrumentation.startActivitySync(android.content.Intent(ctx, ProcessingActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("audioUris", arrayOf(bad, a, b).map { Uri.fromFile(it).toString() }.toTypedArray())
                putExtra("modelId", ModelManager.TWO_STEMS)
                putExtra("selectedStems", arrayOf("vocals", "other"))
                putExtra("backend", "cpu")
            }) as ProcessingActivity
            val screen = activity
            var status = ""
            val deadline = android.os.SystemClock.elapsedRealtime() + 240000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync {
                    status = screen.findViewById<android.widget.TextView>(R.id.progressText).text.toString()
                }
                if (status.contains("completed;")) break
                Thread.sleep(200)
            }
            assertTrue(status, status.contains("2/3 completed; 1 failed"))
            instrumentation.runOnMainSync {
                assertTrue(screen.findViewById<android.widget.Button>(R.id.shareButton).isEnabled)
            }
            val zip = ctx.cacheDir.listFiles()!!.filter { it.name.startsWith("stemwerk_output_") }.maxBy { it.lastModified() }
            java.util.zip.ZipFile(zip).use { archive ->
                val names = archive.entries().asSequence().map { it.name }.toList()
                assertEquals(4, names.size)
                assertTrue(names.any { it == "002_same/vocals.wav" })
                assertTrue(names.any { it == "003_same/other.wav" })
            }
        } finally {
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            dir.deleteRecursively()
            cache.delete()
        }
    }

    @Test fun testDecodeAndResampleSupportedFormats() {
        val ctx = instrumentation.targetContext
        val assets = instrumentation.context.assets
        val dir = File(ctx.cacheDir, "codec-test")
        dir.mkdirs()
        try {
            for (name in listOf("tone24.wav", "tone48.mp3", "tone48.flac", "tone48.m4a", "tone48.ogg")) {
                val source = File(dir, name)
                assets.open(name).use { input -> source.outputStream().use { input.copyTo(it) } }
                val job = File(dir, name + "-work").also { it.mkdirs() }
                val decoded = AudioDecoder(ctx).decode(Uri.fromFile(source), job, {}, {}, {})
                assertEquals(2, decoded.channels)
                assertEquals(if (name.endsWith("wav")) 24000 else 48000, decoded.sampleRate)
                val audio = PcmResampler.convert(decoded, File(job, "ready.pcm"))
                assertTrue("Duration for " + name, abs(audio.frames - 44100L) < 5000)
                PcmReader(audio).use { reader ->
                    var energy = 0.0
                    for (i in 1000L until 20000L) energy += abs(reader.sample(i, 0))
                    assertTrue("Non-silent audio for " + name, energy > 50)
                }
            }
            val bad = File(dir, "broken.mp3").also { it.writeText("not audio") }
            val work = File(dir, "bad-work").also { it.mkdirs() }
            try {
                AudioDecoder(ctx).decode(Uri.fromFile(bad), work, {}, {}, {})
                fail("Corrupt input must fail clearly")
            } catch (_: Exception) { }
        } finally { dir.deleteRecursively() }
    }

    @Test fun testRealFourStemAndTwoStemCpuExtraction() {
        val ctx = instrumentation.targetContext
        val dir = File(ctx.cacheDir, "inference-test").also { it.mkdirs() }
        try {
            val pcm = File(dir, "input.pcm")
            val random = java.util.Random(42)
            pcm.outputStream().buffered().use { out ->
                repeat(4410 * 2) {
                    val value = random.nextInt(8000) - 4000
                    out.write(value and 255); out.write((value shr 8) and 255)
                }
            }
            val audio = PcmAudio(pcm, 44100, 2)
            for (model in ModelManager.MODELS) {
                val file = File(dir, model.file)
                instrumentation.context.assets.open(model.file).use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                val outputDir = File(dir, model.id).also { it.mkdirs() }
                val selected = if (model.secondaryStem.isEmpty()) listOf(model.primaryStem) else listOf("vocals", "other")
                OnnxMdxSeparator(ctx, {}, { _, _ -> }).separate(audio, model, file, selected,
                    InferenceBackend.CPU, FileOutputSink(outputDir))
                for (stem in selected) {
                    val output = File(outputDir, stem + ".wav")
                    assertTrue(output.isFile)
                    val (info, data) = WavUtil.parsePcm16(output.readBytes())
                    assertEquals(44100, info.sampleRate)
                    assertEquals(2, info.channels)
                    assertEquals(4410 * 4, data.size)
                    assertFalse("Inference must not copy input", data.contentEquals(pcm.readBytes()))
                }
                file.delete()
            }
        } finally { dir.deleteRecursively() }
    }
}
