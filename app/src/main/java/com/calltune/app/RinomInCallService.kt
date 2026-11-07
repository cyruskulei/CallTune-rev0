package com.calltune.app

import android.app.*
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService

/** Bound only by Android Telecom. Calls and numbers are never sent to a server here. */
class RinomInCallService : InCallService() {
    private val callbacks = mutableMapOf<Call, Call.Callback>()
    private val main = Handler(Looper.getMainLooper())

    companion object {
        var current: RinomInCallService? = null
            private set
        val listeners = mutableSetOf<() -> Unit>()
    }

    override fun onCreate() {
        super.onCreate()
        current = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("calls", "Phone calls", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                android.util.Log.i("RinomTelecom", "Call state changed: $state")
                update()
            }
            override fun onDetailsChanged(call: Call, details: Call.Details) = update()
            override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: MutableList<Call>) = update()
        }
        callbacks[call] = callback
        call.registerCallback(callback, main)
        update()
        // Telecom-bound services may show call UI; the notification remains the fallback.
        try { startActivity(Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { }
    }

    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let { call.unregisterCallback(it) }
        super.onCallRemoved(call)
        update()
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        listeners.toList().forEach { it() }
    }

    private fun update() {
        // Synchronous on the main thread: stop audio before refreshing any UI.
        CallService.instance?.onTelecomCallsChanged(calls)
        listeners.toList().forEach { it() }
        val call = calls.firstOrNull { it.state == Call.STATE_RINGING }
            ?: calls.firstOrNull { it.state != Call.STATE_DISCONNECTED }
        val nm = getSystemService(NotificationManager::class.java)
        if (call == null) { nm.cancel(200); return }
        val open = PendingIntent.getActivity(this, 200,
            Intent(this, InCallActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "calls")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(if (call.state == Call.STATE_RINGING) "Incoming call" else "Call in progress")
            .setContentText("Tap for call controls")
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true).setContentIntent(open)
        if (call.state == Call.STATE_RINGING) notification.setFullScreenIntent(open, true)
        nm.notify(200, notification.build())
    }

    override fun onCanAddCallChanged(canAddCall: Boolean) {
        listeners.toList().forEach { it() }
    }

    override fun onBringToForeground(showDialpad: Boolean) {
        startActivity(Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onDestroy() {
        callbacks.forEach { (call, callback) -> call.unregisterCallback(callback) }
        callbacks.clear()
        CallService.instance?.onTelecomCallsChanged(emptyList())
        if (current === this) current = null
        getSystemService(NotificationManager::class.java).cancel(200)
        listeners.toList().forEach { it() }
        super.onDestroy()
    }
}
