// DIAGNOSTIC BUILD (based on the uploaded CallService.kt): behaviour is unchanged unless IDLE_GRACE_MS is raised.
// The 'When I call' line now shows a trace of why the caller-side tune stopped.
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

    // --- Outgoing calls: the caller hears the receiver's tune on the caller's own phone ---
    private var outPlayer: MediaPlayer? = null
    @Volatile private var outgoing = false
    // DIAGNOSTIC: 0 = original behaviour. Set to 5000L to ignore "IDLE" for the first 5 s after dialling (suspected fix).
    private val IDLE_GRACE_MS = 0L
    private var dialAt = 0L
    private var polls = 0
    private val outStop = Runnable { stopOutgoing("20 second cap") }

    private fun callStateName(): String = try {
        @Suppress("DEPRECATION")
        when (getSystemService(TelephonyManager::class.java).callState) {
            TelephonyManager.CALL_STATE_IDLE -> "IDLE"
            TelephonyManager.CALL_STATE_RINGING -> "RINGING"
            TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
            else -> "?"
        }
    } catch (e: Exception) { "ERR" }

    // Appends to the 'When I call' line (keeps the last 900 characters).
    private fun trace(msg: String) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val ms = if (dialAt > 0) System.currentTimeMillis() - dialAt else 0
        val old = prefs.getString("out_log", "") ?: ""
        val line = "$t +${ms}ms $msg"
        val all = if (old.isEmpty()) line else "$old | $line"
        prefs.edit().putString("out_log", all.takeLast(900)).apply()
    }

    private val outWatch = object : Runnable {
        override fun run() {
            if (!outgoing) return
            val state = callStateName()
            polls++
            if (polls <= 8) trace("poll state=$state")
            val idle = state == "IDLE"
            if (idle && System.currentTimeMillis() - dialAt >= IDLE_GRACE_MS) stopOutgoing("watchdog saw IDLE")
            else main.postDelayed(this, 500)
        }
    }

    private val outReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!getSharedPreferences("settings", MODE_PRIVATE).getBoolean("caller_hears", true)) return
            @Suppress("DEPRECATION")
            val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER) ?: resultData
            if (number.isNullOrBlank()) { note("out_log", "outgoing call, number not provided"); return }
            note("out_log", "dialing $number")
            dialAt = System.currentTimeMillis()
            polls = 0
            startOutgoing(context, number)
        }
    }

    private fun startOutgoing(ctx: Context, number: String) {
        stopOutgoing("restart")
        outgoing = true
        val cached = Cloud.cachedFile(ctx, number)
        if (cached != null) {
            playOutgoing(Uri.fromFile(cached))
            Thread { Cloud.fetch(ctx, number) }.start() // refresh for next time
            return
        }
        Thread {
            val f = Cloud.fetch(ctx, number)
            main.post {
                if (outgoing && f != null) playOutgoing(Uri.fromFile(f))
                else if (outgoing) note("out_log", "no tune found for $number")
            }
        }.start()
    }

    private fun playOutgoing(uri: Uri) {
        try {
            outPlayer?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
            outPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(this@CallService, uri)
                isLooping = true
                prepare()
                start()
            }
            note("out_log", "playing receiver's tune to the caller: $uri")
            trace("started, state=${callStateName()}")
            main.removeCallbacks(outStop)
            main.postDelayed(outStop, 20_000) // we cannot detect the answer, so stop after 20 seconds
            main.removeCallbacks(outWatch)
            main.postDelayed(outWatch, 500)
        } catch (e: Exception) {
            note("out_log", "FAILED: ${e.javaClass.simpleName} ${e.message}")
            stopOutgoing("player failed")
        }
    }

    private fun stopOutgoing(why: String = "unspecified") {
        if (outgoing || outPlayer != null) trace("STOP: $why (state=${callStateName()})")
        outgoing = false
        main.removeCallbacks(outStop)
        main.removeCallbacks(outWatch)
        outPlayer?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        outPlayer = null
    }
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
            } else if (outgoing) {
                trace("broadcast state=$state")
                if (state == TelephonyManager.EXTRA_STATE_IDLE &&
                    System.currentTimeMillis() - dialAt >= IDLE_GRACE_MS) stopOutgoing("IDLE broadcast")
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
        val o = IntentFilter(Intent.ACTION_NEW_OUTGOING_CALL)
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(outReceiver, o, Context.RECEIVER_EXPORTED)
            else registerReceiver(outReceiver, o)
        } catch (e: Exception) { note("out_log", "could not listen for outgoing calls: ${e.javaClass.simpleName}") }
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
        stopOutgoing("service destroyed")
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        try { unregisterReceiver(outReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}
