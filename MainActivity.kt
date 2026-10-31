package com.vipulkarada.delayfx

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.View
import android.widget.*
import android.graphics.Color
import android.graphics.Typeface
import android.content.Context

class MainActivity : Activity() {
    private var engine: DelayEngine? = null
    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var delayLabel: TextView
    private lateinit var feedbackLabel: TextView
    private lateinit var wetLabel: TextView
    private var delayMs = 280
    private var feedback = 35
    private var wet = 45

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(20, 20, 20)
        window.navigationBarColor = Color.BLACK
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 22, 24, 20)
            setBackgroundColor(Color.rgb(25, 27, 30))
        }

        val title = TextView(this).apply {
            text = "VIPUL DELAY FX"
            textSize = 27f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(title, fullWidth())

        val subtitle = TextView(this).apply {
            text = "DIGITAL DELAY  •  LIVE AUDIO"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 5, 0, 22)
        }
        root.addView(subtitle, fullWidth())

        status = TextView(this).apply {
            text = "STOPPED  •  Connect headphones / audio interface"
            textSize = 13f
            setTextColor(Color.rgb(255, 190, 80))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 16)
        }
        root.addView(status, fullWidth())

        delayLabel = addSlider(root, "DELAY TIME", 12, 800, delayMs) {
            delayMs = it
            engine?.setDelay(delayMs)
        }
        feedbackLabel = addSlider(root, "FEEDBACK", 0, 90, feedback) {
            feedback = it
            engine?.setFeedback(feedback / 100f)
        }
        wetLabel = addSlider(root, "EFFECT LEVEL", 0, 100, wet) {
            wet = it
            engine?.setWet(wet / 100f)
        }

        val tap = Button(this).apply {
            text = "TAP TEMPO"
            setOnClickListener {
                val now = System.currentTimeMillis()
                if (lastTap > 0) {
                    val interval = now - lastTap
                    if (interval in 150..5000) {
                        delayMs = interval.toInt().coerceIn(12, 800)
                        delayLabel.text = "DELAY TIME  •  ${delayMs} ms"
                        engine?.setDelay(delayMs)
                    }
                }
                lastTap = now
            }
        }
        root.addView(tap, fullWidth())

        startButton = Button(this).apply {
            text = "START DELAY"
            setOnClickListener { toggleAudio() }
        }
        root.addView(startButton, fullWidth())

        val note = TextView(this).apply {
            text = "Use headphones to reduce feedback. USB audio routing depends on your Android device and interface. This is an independent delay app, not an official BOSS product."
            textSize = 11f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(4, 18, 4, 0)
        }
        root.addView(note, fullWidth())
        setContentView(root)
    }

    private var lastTap = 0L

    private fun addSlider(parent: LinearLayout, label: String, min: Int, max: Int, initial: Int, changed: (Int) -> Unit): TextView {
        val text = TextView(this).apply {
            text = "$label  •  $initial${if (label == "DELAY TIME") " ms" else "%"}"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 12, 0, 2)
        }
        parent.addView(text, fullWidth())
        val seek = SeekBar(this).apply {
            this.max = max - min
            progress = initial - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) {
                    val value = p + min
                    text.text = "$label  •  $value${if (label == "DELAY TIME") " ms" else "%"}"
                    if (user) changed(value)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        parent.addView(seek, fullWidth())
        return text
    }

    private fun toggleAudio() {
        if (engine != null) {
            engine?.stop()
            engine = null
            startButton.text = "START DELAY"
            status.text = "STOPPED"
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 42)
            return
        }
        try {
            val e = DelayEngine(this, delayMs, feedback / 100f, wet / 100f)
            e.start()
            engine = e
            startButton.text = "STOP DELAY"
            status.text = "RUNNING  •  Audio delay active"
            status.setTextColor(Color.rgb(110, 230, 150))
        } catch (ex: Exception) {
            status.text = "Audio could not start: ${ex.message ?: "check microphone permission"}"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            toggleAudio()
        }
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        super.onDestroy()
    }

    private fun fullWidth() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
}

private class DelayEngine(
    private val context: Context,
    delayMs: Int,
    feedback: Float,
    wet: Float
) {
    @Volatile private var delaySamples = delayMs * SAMPLE_RATE / 1000
    @Volatile private var feedbackAmount = feedback.coerceIn(0f, 0.90f)
    @Volatile private var wetAmount = wet.coerceIn(0f, 1f)
    @Volatile private var running = false
    private var worker: Thread? = null
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null

    fun setDelay(ms: Int) { delaySamples = (ms * SAMPLE_RATE / 1000).coerceIn(1, BUFFER_SIZE - 1) }
    fun setFeedback(value: Float) { feedbackAmount = value.coerceIn(0f, 0.90f) }
    fun setWet(value: Float) { wetAmount = value.coerceIn(0f, 1f) }

    fun start() {
        val minIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val minOut = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minIn <= 0 || minOut <= 0) throw IllegalStateException("unsupported audio configuration")
        recorder = AudioRecord(
            MediaRecorder.AudioSource.UNPROCESSED, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minIn * 2, 4096)
        )
        track = AudioTrack(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            maxOf(minOut * 2, 4096), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        running = true
        recorder?.startRecording()
        track?.play()
        worker = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val input = ShortArray(512)
            val output = ShortArray(512)
            val ring = FloatArray(BUFFER_SIZE)
            var write = 0
            while (running) {
                val count = recorder?.read(input, 0, input.size) ?: 0
                if (count <= 0) continue
                for (i in 0 until count) {
                    val dry = input[i] / 32768f
                    val read = (write - delaySamples + BUFFER_SIZE) % BUFFER_SIZE
                    val delayed = ring[read]
                    val mixed = (dry * (1f - wetAmount) + delayed * wetAmount).coerceIn(-1f, 1f)
                    ring[write] = (dry + delayed * feedbackAmount).coerceIn(-1f, 1f)
                    output[i] = (mixed * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    write = (write + 1) % BUFFER_SIZE
                }
                track?.write(output, 0, count)
            }
        }.apply { start() }
    }

    fun stop() {
        running = false
        try { worker?.join(500) } catch (_: InterruptedException) {}
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release()
        recorder = null
        try { track?.pause(); track?.flush(); track?.stop() } catch (_: Exception) {}
        track?.release()
        track = null
    }

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val BUFFER_SIZE = SAMPLE_RATE * 2
    }
}
