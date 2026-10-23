package com.calltune.app

import android.app.Activity
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import java.io.File

class TrimActivity : Activity() {
    private lateinit var src: Uri
    private var durationMs = 0
    private lateinit var startBar: SeekBar
    private lateinit var endBar: SeekBar
    private lateinit var label: TextView
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val maxClipMs = 30_000

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val u = intent.data
        if (u == null) { finish(); return }
        src = u
        durationMs = try {
            val r = MediaMetadataRetriever()
            r.setDataSource(this, src)
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toInt() ?: 0
            r.release()
            d
        } catch (e: Exception) { 0 }
        if (durationMs <= 0) {
            Toast.makeText(this, "Could not read this audio file", Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED); finish(); return
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply { text = "Trim your tune"; textSize = 24f })
        root.addView(TextView(this).apply { text = "\nStart" })
        startBar = SeekBar(this).apply { max = durationMs / 100; progress = 0 }
        root.addView(startBar)
        root.addView(TextView(this).apply { text = "End" })
        endBar = SeekBar(this).apply {
            max = durationMs / 100
            progress = minOf(durationMs, maxClipMs) / 100
        }
        root.addView(endBar)
        label = TextView(this)
        root.addView(label)
        root.addView(Button(this).apply { text = "Preview"; setOnClickListener { preview() } })
        root.addView(Button(this).apply { text = "Save trimmed tune"; setOnClickListener { save() } })
        root.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener { setResult(RESULT_CANCELED); finish() }
        })
        setContentView(ScrollView(this).apply { addView(root) })

        startBar.setOnSeekBarChangeListener(listener(true))
        endBar.setOnSeekBarChangeListener(listener(false))
        updateLabel()
    }

    private fun listener(isStart: Boolean) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
            if (fromUser) fix(isStart)
        }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    private fun fix(startChanged: Boolean) {
        var s = startBar.progress * 100
        var e = endBar.progress * 100
        if (startChanged) {
            if (s > durationMs - 1000) s = maxOf(0, durationMs - 1000)
            if (e < s + 1000) e = minOf(durationMs, s + 1000)
            if (e - s > maxClipMs) e = s + maxClipMs
        } else {
            if (e < 1000) e = minOf(durationMs, 1000)
            if (s > e - 1000) s = maxOf(0, e - 1000)
            if (e - s > maxClipMs) s = e - maxClipMs
        }
        startBar.progress = s / 100
        endBar.progress = e / 100
        updateLabel()
    }

    private fun fmt(ms: Int): String {
        val sec = ms / 1000
        return "%d:%02d.%d".format(sec / 60, sec % 60, (ms % 1000) / 100)
    }

    private fun updateLabel() {
        val s = startBar.progress * 100
        val e = endBar.progress * 100
        label.text = "\nStart ${fmt(s)}   End ${fmt(e)}   Length ${(e - s) / 1000}s (max 30s)"
    }

    private fun preview() {
        stopPreview()
        val s = startBar.progress * 100
        val e = endBar.progress * 100
        try {
            player = MediaPlayer().apply {
                setDataSource(this@TrimActivity, src)
                prepare()
                seekTo(s)
                start()
            }
            handler.post(object : Runnable {
                override fun run() {
                    val p = player ?: return
                    if (p.currentPosition >= e || !p.isPlaying) stopPreview()
                    else handler.postDelayed(this, 100)
                }
            })
        } catch (ex: Exception) {
            Toast.makeText(this, "Preview failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPreview() {
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        player = null
    }

    private fun save() {
        stopPreview()
        val s = startBar.progress * 100L
        val e = endBar.progress * 100L
        Toast.makeText(this, "Saving...", Toast.LENGTH_SHORT).show()
        Thread {
            val dir = File(filesDir, "tunes").apply { mkdirs() }
            val out = File(dir, "tune_${System.currentTimeMillis()}.m4a")
            val err = Trimmer.trim(this, src, s, e, out)
            runOnUiThread {
                if (err == null) {
                    setResult(RESULT_OK, Intent().setData(Uri.fromFile(out)))
                    finish()
                } else {
                    out.delete()
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        stopPreview()
        super.onDestroy()
    }
}
