package com.calltune.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*

class MainActivity : Activity() {
    private lateinit var numberBox: EditText
    private lateinit var myNumber: EditText
    private lateinit var list: TextView
    private lateinit var modeBtn: Button

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply { text = "CallTune"; textSize = 26f })
        root.addView(TextView(this).apply {
            text = "Enter a caller's number (leave empty for all callers), then choose a tune."
        })
        numberBox = EditText(this).apply {
            hint = "Phone number"; inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        root.addView(numberBox)
        root.addView(Button(this).apply {
            text = "Tune for a caller (this phone)"
            setOnClickListener { pickAudio(1) }
        })
        root.addView(TextView(this).apply {
            text = "\nMy tune that others hear when I call:"
        })
        myNumber = EditText(this).apply {
            hint = "My own phone number"; inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(getSharedPreferences("me", MODE_PRIVATE).getString("number", ""))
        }
        root.addView(myNumber)
        root.addView(Button(this).apply {
            text = "My tune for others (cloud)"
            setOnClickListener {
                if (myNumber.text.isBlank())
                    Toast.makeText(this@MainActivity, "Enter your own number first", Toast.LENGTH_SHORT).show()
                else pickAudio(3)
            }
        })
        modeBtn = Button(this).apply {
            setOnClickListener {
                val sp = getSharedPreferences("settings", MODE_PRIVATE)
                val next = if (sp.getString("mode", "free") == "free") "pro" else "free"
                sp.edit().putString("mode", next).apply()
                updateModeText()
            }
        }
        root.addView(modeBtn)
        updateModeText()
        root.addView(Button(this).apply {
            text = "Test play my saved tune"
            setOnClickListener { testPlay() }
        })
        root.addView(Button(this).apply {
            text = "Start CallTune"
            setOnClickListener { askPermsThenStart() }
        })
        root.addView(Button(this).apply {
            text = "Stop CallTune"
            setOnClickListener {
                setAutostart(false)
                applyAudioProfile(false)
                stopService(Intent(this@MainActivity, CallService::class.java))
            }
        })
        root.addView(Button(this).apply {
            text = "Clear all tunes"
            setOnClickListener {
                TuneStore.all(this@MainActivity).keys.forEach { TuneStore.remove(this@MainActivity, it) }
                refresh()
            }
        })
        list = TextView(this)
        root.addView(list)
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    private fun pickAudio(code: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*"
        }, code)
    }

    private fun trim(uri: Uri, code: Int) {
        startActivityForResult(Intent(this, TrimActivity::class.java).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, code)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data
        if (res != RESULT_OK || uri == null) {
            if (req == 11 || req == 13)
                Toast.makeText(this, "No trimmed tune came back (code $res)", Toast.LENGTH_LONG).show()
            return
        }
        when (req) {
            1 -> trim(uri, 11)
            3 -> trim(uri, 13)
            11 -> {
                val who = numberBox.text.toString()
                TuneStore.save(this, who, uri)
                numberBox.setText("")
                refresh()
                Toast.makeText(this,
                    "Saved for " + (if (who.isBlank()) "all callers" else who),
                    Toast.LENGTH_LONG).show()
            }
            13 -> {
                val num = myNumber.text.toString()
                getSharedPreferences("me", MODE_PRIVATE).edit().putString("number", num).apply()
                Toast.makeText(this, "Uploading...", Toast.LENGTH_SHORT).show()
                Thread {
                    val err = Cloud.upload(this, num, uri)
                    runOnUiThread {
                        Toast.makeText(this, err ?: "Tune uploaded", Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
        }
    }

    private fun updateModeText() {
        val free = getSharedPreferences("settings", MODE_PRIVATE).getString("mode", "free") == "free"
        modeBtn.text = if (free) "Mode: FREE (plays my own tune). Tap to switch"
                       else "Mode: PRO (plays the caller's tune). Tap to switch"
    }

    private fun testPlay() {
        val uri = TuneStore.findDefault(this)
            ?: TuneStore.all(this).values.firstOrNull()?.let { Uri.parse(it) }
        if (uri == null) {
            Toast.makeText(this, "No saved tune. Save one first.", Toast.LENGTH_LONG).show(); return
        }
        try {
            val mp = android.media.MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
                prepare()
                start()
            }
            Toast.makeText(this, "Playing for 6 seconds...", Toast.LENGTH_SHORT).show()
            android.os.Handler(mainLooper).postDelayed({
                try { mp.stop() } catch (_: Exception) {}
                mp.release()
            }, 6000)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot play: ${e.javaClass.simpleName} ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun refresh() {
        val m = TuneStore.all(this)
        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        val boot = sp.getString("boot_log", "none yet (restart the phone to test)")
        list.text = (if (m.isEmpty()) "\nNo tunes saved yet." else
            "\nSaved tunes:\n" + m.entries.joinToString("\n") {
                "• ${if (it.key == "default") "All callers" else it.key}"
            }) + "\n\nLast boot check: $boot" +
            "\nLast ring: " + sp.getString("ring_log", "none yet") +
            "\nLast play: " + sp.getString("play_log", "none yet") +
            "\nService: " + sp.getString("svc_log", "none yet") +
            "\nRinger: " + sp.getString("ringer_log", "none yet") +
            "\nStop: " + sp.getString("stop_log", "none yet")
    }

    private fun askPermsThenStart() {
        val need = mutableListOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) start() else requestPermissions(missing.toTypedArray(), 2)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (r.isNotEmpty() && r.all { it == PackageManager.PERMISSION_GRANTED }) start()
        else Toast.makeText(this, "Permissions are required", Toast.LENGTH_LONG).show()
    }

    private fun setAutostart(on: Boolean) {
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("autostart", on).apply()
    }

    private fun applyAudioProfile(on: Boolean) {
        try {
            val am = getSystemService(android.media.AudioManager::class.java)
            val sp = getSharedPreferences("settings", MODE_PRIVATE)
            if (on) {
                // Save current state before changing anything.
                sp.edit()
                    .putInt("saved_ringer", am.ringerMode)
                    .putBoolean("saved_vibrate", am.getStreamVolume(android.media.AudioManager.STREAM_RING) == 0)
                    .apply()
                // 1. Silent mode (ringerMode = 0 is silent on MIUI/Redmi; 1 = vibrate; 2 = normal)
                am.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT
                // 2. Make sure media is NOT silenced (CallTune plays at media volume).
                // MIUI's silent mode may have lowered media; restore it to at least 50%.
                val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                if (am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) < max / 2)
                    am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (max * 0.7).toInt(), 0)
                Toast.makeText(this, "CallTune started. Ringer silenced, media on.", Toast.LENGTH_LONG).show()
            } else {
                // Restore previous ringer mode.
                val saved = sp.getInt("saved_ringer", android.media.AudioManager.RINGER_MODE_NORMAL)
                am.ringerMode = saved
                Toast.makeText(this, "CallTune stopped. Ringer restored.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, if (on) "CallTune started." else "CallTune stopped.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun start() {
        setAutostart(true)
        applyAudioProfile(true)
        startForegroundService(Intent(this, CallService::class.java))
    }
}
