package com.flark.stemwerk

import android.content.Context
import android.graphics.Color
import android.media.*
import android.net.Uri
import android.os.*
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import kotlin.math.abs

/** One player for originals and finished stems. Switching stems retains the playhead. */
class PlayerActivity : AppCompatActivity() {
    private lateinit var waveform: WaveformView
    private lateinit var play: Button
    private lateinit var time: TextView
    private lateinit var status: TextView
    private lateinit var seek: SeekBar
    private var player: MediaPlayer? = null
    private var prepared = false
    private var currentGroup: String? = null
    private var selectedIndex = 0
    private var restorePosition = 0
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var generation = 0
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focus by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change < 0) handler.post { pause() }
            }.build()
    }
    private val tick = object : Runnable {
        override fun run() {
            val active = player
            if (prepared && active != null) {
                val duration = active.duration.coerceAtLeast(1)
                val position = active.currentPosition.coerceAtLeast(0)
                seek.max = duration
                seek.progress = position
                waveform.position = position.toFloat() / duration
                time.text = clock(position) + " / " + clock(duration)
            }
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = intent.getStringArrayExtra("audioUris")?.map(Uri::parse).orEmpty()
        val labels = intent.getStringArrayExtra("labels")?.toList()
            ?: uris.map { AudioNames.display(this, it) }
        val groups = intent.getStringArrayExtra("groups")?.toList() ?: uris.map(Uri::toString)
        if (uris.isEmpty() || labels.size != uris.size || groups.size != uris.size) { finish(); return }
        selectedIndex = (savedInstanceState?.getInt("track") ?: 0).coerceIn(uris.indices)
        restorePosition = savedInstanceState?.getInt("position") ?: 0
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(26, 26, 31))
        }
        fun text(value: String, size: Float = 16f) = TextView(this).apply {
            this.text = value; textSize = size; setTextColor(Color.LTGRAY); setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(text("STEMwerk — Listen", 22f))
        val spinner = Spinner(this).apply { id = R.id.playerTracks }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        root.addView(spinner, LinearLayout.LayoutParams(-1, dp(56)))
        status = text("Opening audio…")
        status.id = R.id.playerStatus
        root.addView(status)
        waveform = WaveformView(this).apply { isEnabled = false }
        waveform.id = R.id.playerWaveform
        root.addView(waveform, LinearLayout.LayoutParams(-1, dp(200)))
        time = text("0:00 / 0:00")
        time.id = R.id.playerTime
        root.addView(time)
        seek = SeekBar(this).apply { isEnabled = false }
        seek.id = R.id.playerSeek
        root.addView(seek, LinearLayout.LayoutParams(-1, dp(48)))
        play = Button(this).apply { text = "Play"; isEnabled = false; contentDescription = "Play or pause audio" }
        play.id = R.id.playerPlay
        root.addView(play)
        root.addView(text("Switch between original and stems with the menu. The playhead is retained for the same file. Tap the waveform or move the slider to seek.", 14f))
        root.addView(Button(this).apply { text = "Close"; setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(root) })
        play.setOnClickListener { if (player?.isPlaying == true) pause() else start() }
        waveform.onSeek = { fraction -> if (prepared) seekTo((fraction * (player?.duration ?: 0)).toInt()) }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) { if (fromUser) seekTo(value) }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        spinner.setSelection(selectedIndex)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedIndex = position
                open(uris[position], labels[position], groups[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun open(uri: Uri, label: String, group: String) {
        val old = player
        val resume = prepared && old?.isPlaying == true
        val position = if (prepared && currentGroup == group) old?.currentPosition ?: 0 else restorePosition
        restorePosition = 0
        currentGroup = group
        old?.release()
        player = null
        prepared = false
        play.text = "Play"
        play.isEnabled = false
        seek.isEnabled = false
        waveform.isEnabled = false
        waveform.peaks = FloatArray(0)
        waveform.position = 0f
        waveform.message = "Building waveform…"
        waveform.color = when {
            label.endsWith("vocals", true) -> Color.rgb(255, 95, 100)
            label.endsWith("drums", true) -> Color.rgb(99, 194, 240)
            label.endsWith("bass", true) -> Color.rgb(151, 100, 255)
            label.endsWith("other", true) -> Color.rgb(96, 255, 157)
            else -> Color.LTGRAY
        }
        status.text = label
        val token = ++generation
        try {
            val active = MediaPlayer()
            player = active
            active.setAudioAttributes(attributes)
            active.setDataSource(this, uri)
            active.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                prepared = true
                play.isEnabled = true
                seek.isEnabled = true
                waveform.isEnabled = true
                seek.max = it.duration.coerceAtLeast(1)
                seekTo(position)
                if (resume) start()
            }
            active.setOnCompletionListener { play.text = "Play"; audioManager.abandonAudioFocusRequest(focus) }
            active.setOnErrorListener { _, what, extra ->
                prepared = false
                play.isEnabled = false
                seek.isEnabled = false
                waveform.isEnabled = false
                status.text = "Playback failed (" + what + "/" + extra + ")"
                true
            }
            active.prepareAsync()
        } catch (e: Exception) { status.text = "Cannot play audio: " + e.message }
        worker.execute {
            val work = File(cacheDir, "waveform-" + java.util.UUID.randomUUID())
            try {
                fun checkCancelled() { if (generation != token) throw CancellationException() }
                checkCancelled()
                check(work.mkdirs())
                val audio = AudioDecoder(applicationContext).decode(uri, work, ::checkCancelled, {}, {})
                val peaks = FloatArray(minOf(1000L, audio.frames).toInt())
                PcmReader(audio).use { reader ->
                    for (bin in peaks.indices) {
                        checkCancelled()
                        val start = bin * audio.frames / peaks.size
                        val end = ((bin + 1) * audio.frames / peaks.size).coerceAtLeast(start + 1)
                        var peak = 0f
                        for (frame in start until end)
                            for (channel in 0 until audio.channels)
                                peak = maxOf(peak, abs(reader.sample(frame, channel)))
                        peaks[bin] = peak
                    }
                }
                handler.post { if (generation == token && !isDestroyed) waveform.peaks = peaks }
            } catch (e: Exception) {
                handler.post {
                    if (generation == token && !isDestroyed) waveform.message = "Waveform unavailable"
                }
            } finally { work.deleteRecursively() }
        }
    }

    private fun start() {
        if (!prepared) return
        if (audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            player?.start(); play.text = "Pause"
        }
    }
    private fun pause() {
        if (prepared && player?.isPlaying == true) player?.pause()
        if (::play.isInitialized) play.text = "Play"
        audioManager.abandonAudioFocusRequest(focus)
    }
    private fun seekTo(value: Int) {
        if (prepared) player?.seekTo(value.coerceIn(0, player?.duration ?: 0).toLong(), MediaPlayer.SEEK_CLOSEST)
    }
    private fun clock(ms: Int): String = (ms / 60000).toString() + ":" + ((ms / 1000) % 60).toString().padStart(2, '0')
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onStart() { super.onStart(); handler.post(tick) }
    override fun onStop() { pause(); handler.removeCallbacks(tick); super.onStop() }
    override fun onSaveInstanceState(state: Bundle) {
        state.putInt("track", selectedIndex)
        state.putInt("position", if (prepared) player?.currentPosition ?: 0 else 0)
        super.onSaveInstanceState(state)
    }
    override fun onDestroy() {
        generation++
        worker.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }
}
