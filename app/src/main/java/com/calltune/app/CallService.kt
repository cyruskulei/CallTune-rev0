package com.calltune.app

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.os.IBinder
import android.telephony.TelephonyManager

class CallService : Service() {
    private var player: MediaPlayer? = null
    private var prevRinger: Int? = null
    private var prevMedia: Int? = null
    @Volatile private var ringing = false
    private val main = Handler(Looper.getMainLooper())

    private fun isRinging(): Boolean = try {
        @Suppress("DEPRECATION")
        getSystemService(TelephonyManager::class.java).callState == TelephonyManager.CALL_STATE_RINGING
    } catch (e: Exception) { true }

    /** Safety net: stops the tune as soon as the phone is no longer ringing. */
    private val watch = object : Runnable {
        override fun run() {
            if (player == null) return
            if (!isRinging()) { ringing = false; stop() } else main.postDelayed(this, 300)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    ringing = true
                    @Suppress("DEPRECATION")
                    val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                    handleRing(context, number)
                }
                else -> { ringing = false; handledFor = null; fetching = null; stop() } // answered or ended
            }
        }
    }

    private fun note(key: String, msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        getSharedPreferences("settings", MODE_PRIVATE).edit().putString(key, "$t $msg").apply()
    }

    private var pendingDefault: Runnable? = null
    private var handledFor: String? = null
    private var fetching: String? = null

    private fun handleRing(ctx: Context, number: String?) {
        note("ring_log", "ring, number=" + (number ?: "NOT PROVIDED"))
        val specific = number?.let { if (it.isBlank()) null else TuneStore.findSpecific(ctx, it) }
        if (specific != null) {
            if (handledFor != number) { handledFor = number; play(specific) }
            return
        }
        val default = TuneStore.findDefault(ctx)
        if (number.isNullOrBlank()) {
            // The number may arrive in a second broadcast. Wait briefly, then fall back to the default tune.
            pendingDefault?.let { main.removeCallbacks(it) }
            val r = Runnable {
                if (ringing && isRinging() && player == null && default != null) {
                    note("play_log", "no number received, using default tune")
                    play(default)
                }
            }
            pendingDefault = r
            main.postDelayed(r, 1500)
            return
        }
        pendingDefault?.let { main.removeCallbacks(it) }
        if (handledFor == number || fetching == number) return
        val cached = Cloud.cachedFile(ctx, number)
        if (cached != null) {
            handledFor = number
            play(Uri.fromFile(cached))
            Thread { Cloud.fetch(ctx, number) }.start() // refresh for next time
            return
        }
        fetching = number
        Thread {
            val f = Cloud.fetch(ctx, number)
            main.post {
                fetching = null
                if (ringing && isRinging()) {
                    val u = f?.let { Uri.fromFile(it) } ?: default
                    if (u != null) { handledFor = number; play(u) }
                    else note("play_log", "no tune found for $number")
                }
            }
        }.start()
    }

    override fun onCreate() {
        super.onCreate()
        note("svc_log", "service started")
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("ct", "CallTune", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "ct")
            .setContentTitle("CallTune is active")
            .setContentText("Playing your chosen tunes for incoming calls")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .build()
        try {
            startForeground(1, n)
        } catch (e: Exception) {
            stopSelf()
            return
        }
        val f = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(receiver, f)
    }

    private fun play(uri: Uri) {
        stop()
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(this@CallService, uri)
                isLooping = true
                prepare()
                start()
            }
            note("play_log", "playing OK: $uri")
            main.removeCallbacks(watch)
            main.postDelayed(watch, 300)
            silenceRinger()
            raiseVolume()
        } catch (e: Exception) {
            note("play_log", "FAILED: ${e.javaClass.simpleName} ${e.message}")
            stop()
        }
    }

    private fun silenceRinger() {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return
        val am = getSystemService(AudioManager::class.java)
        if (prevRinger == null) prevRinger = am.ringerMode
        am.ringerMode = AudioManager.RINGER_MODE_SILENT
    }

    private fun raiseVolume() {
        try {
            val am = getSystemService(AudioManager::class.java)
            if (prevMedia == null) prevMedia = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val target = (am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.8).toInt()
            if (target > (prevMedia ?: 0))
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (_: Exception) {}
    }

    private fun restoreVolume() {
        prevMedia?.let {
            try { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
            catch (_: Exception) {}
        }
        prevMedia = null
    }

    private fun restoreRinger() {
        prevRinger?.let {
            try { getSystemService(AudioManager::class.java).ringerMode = it } catch (_: Exception) {}
        }
        prevRinger = null
    }

    private fun stop() {
        main.removeCallbacks(watch)
        restoreRinger()
        restoreVolume()
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        player = null
    }

    override fun onDestroy() {
        note("svc_log", "service stopped")
        stop()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}
