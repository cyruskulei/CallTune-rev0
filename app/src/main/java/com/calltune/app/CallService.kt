// STABLE v1: simple call handling. No ringer changes, no background watcher.
package com.calltune.app

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.TelephonyManager

class CallService : Service() {
    private var player: MediaPlayer? = null
    private var prevMedia: Int? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ringId = 0 // changes with every ring or call end, so late downloads are ignored

    private val autoStop = Runnable { stop() }
    private var pendingDefault: Runnable? = null

    private fun isRinging(): Boolean = try {
        @Suppress("DEPRECATION")
        getSystemService(TelephonyManager::class.java).callState == TelephonyManager.CALL_STATE_RINGING
    } catch (e: Exception) { true }

    private fun note(key: String, msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        getSharedPreferences("settings", MODE_PRIVATE).edit().putString(key, "$t $msg").apply()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                @Suppress("DEPRECATION")
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                handleRing(context, number)
            } else if (!isRinging()) {
                // Call answered or ended (extra "idle" signals from a second SIM are ignored).
                note("stop_log", "stopped: state=$state")
                ringId++
                stop()
            } else {
                note("stop_log", "ignored stray state=$state while ringing")
            }
        }
    }

    private fun handleRing(ctx: Context, number: String?) {
        note("ring_log", "ring, number=" + (number ?: "NOT PROVIDED"))
        val id = ++ringId
        pendingDefault?.let { main.removeCallbacks(it) }
        val hasNumber = !number.isNullOrBlank()
        val specific = if (hasNumber) TuneStore.findSpecific(ctx, number!!) else null
        // Your own saved tune if you have one, otherwise the built-in default tune.
        val default: Uri? = TuneStore.findDefault(ctx)
            ?: Uri.parse("android.resource://" + ctx.packageName + "/raw/default_tune")
        val mode = getSharedPreferences("settings", MODE_PRIVATE).getString("mode", "free")

        if (mode == "free") {
            (specific ?: default)?.let { play(it) }
            return
        }
        // PRO mode: the caller's tune
        if (specific != null) { play(specific); return }
        if (!hasNumber) {
            // The number may arrive in the next broadcast. If not, use the default tune.
            val r = Runnable { if (id == ringId && isRinging() && default != null) play(default) }
            pendingDefault = r
            main.postDelayed(r, 2000)
            return
        }
        val cached = Cloud.cachedFile(ctx, number!!)
        if (cached != null) {
            play(Uri.fromFile(cached))
            Thread { Cloud.fetch(ctx, number) }.start() // refresh for next time
            return
        }
        Thread {
            val f = Cloud.fetch(ctx, number)
            main.post {
                if (id == ringId && isRinging()) {
                    val u = f?.let { Uri.fromFile(it) } ?: default
                    if (u != null) play(u) else note("play_log", "no tune found for $number")
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
            raiseVolume()
            main.postDelayed(autoStop, 60_000) // safety: never play longer than a minute
        } catch (e: Exception) {
            note("play_log", "FAILED: ${e.javaClass.simpleName} ${e.message}")
            stop()
        }
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

    private fun stop() {
        main.removeCallbacks(autoStop)
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
