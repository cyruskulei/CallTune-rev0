package com.calltune.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.TelecomManager
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.*

class InCallActivity : Activity() {
    private val refresh: () -> Unit = { render() }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }
    override fun onStart() { super.onStart(); RinomInCallService.listeners.add(refresh); render() }
    override fun onStop() {
        RinomInCallService.listeners.remove(refresh)
        RinomInCallService.current?.calls?.forEach { it.stopDtmfTone() }
        super.onStop()
    }

    private fun render() {
        val service = RinomInCallService.current
        val calls = service?.calls.orEmpty().filter { it.state != Call.STATE_DISCONNECTED }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 24) }
        fun button(label: String, action: () -> Unit) {
            root.addView(Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } })
        }
        root.addView(TextView(this).apply { text = "Rinom Calltune"; textSize = 24f })
        if (calls.isEmpty()) {
            root.addView(TextView(this).apply { text = "No ongoing call"; textSize = 22f })
            button("Close") { finish() }
        }
        for (call in calls) {
            val state = call.state
            val label = when (state) {
                Call.STATE_RINGING -> "Incoming call"
                Call.STATE_DIALING, Call.STATE_CONNECTING -> "Calling"
                Call.STATE_ACTIVE -> "Connected"
                Call.STATE_HOLDING -> "On hold"
                Call.STATE_SELECT_PHONE_ACCOUNT -> "Choose SIM"
                Call.STATE_DISCONNECTING -> "Ending call"
                else -> "Call"
            }
            val number = if (call.details.hasProperty(Call.Details.PROPERTY_CONFERENCE)) "Conference call"
                else call.details?.handle?.schemeSpecificPart ?: "Private number"
            root.addView(TextView(this).apply { text = "$number\n$label"; textSize = 22f })
            if (state == Call.STATE_RINGING) {
                button(if ((call.details?.videoState ?: 0) == 0) "Answer" else "Answer with audio only") { call.answer(0) }
                button("Reject") { call.reject(false, null) }
            } else {
                button("End call") { call.disconnect() }
            }
            if (state == Call.STATE_SELECT_PHONE_ACCOUNT) {
                val telecom = getSystemService(TelecomManager::class.java)
                try {
                    telecom.callCapablePhoneAccounts.forEach { handle ->
                        button(telecom.getPhoneAccount(handle)?.label?.toString() ?: "SIM") {
                            call.phoneAccountSelected(handle, false)
                        }
                    }
                } catch (_: SecurityException) { }
            }
            if (state == Call.STATE_ACTIVE && call.details.can(Call.Details.CAPABILITY_HOLD))
                button("Hold") { call.hold() }
            if (state == Call.STATE_HOLDING) button("Resume") { call.unhold() }
            if (call.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE))
                button("Merge calls") { call.mergeConference() }
            else call.conferenceableCalls.firstOrNull()?.let { other -> button("Merge calls") { call.conference(other) } }
            if (state == Call.STATE_ACTIVE) {
                for (digits in listOf("123", "456", "789", "*0#")) root.addView(LinearLayout(this).apply {
                    digits.forEach { digit -> addView(Button(this@InCallActivity).apply {
                        text = digit.toString()
                        contentDescription = "Key $digit"
                        setOnTouchListener { view, event ->
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> call.playDtmfTone(digit)
                                MotionEvent.ACTION_UP -> { call.stopDtmfTone(); view.performClick() }
                                MotionEvent.ACTION_CANCEL -> call.stopDtmfTone()
                            }
                            true
                        }
                    }, LinearLayout.LayoutParams(0, -2, 1f)) }
                })
            }
        }
        if (service != null && calls.isNotEmpty()) {
            if (service.canAddCall()) button("Add call") {
                startActivity(android.content.Intent(this, DialerActivity::class.java))
            }
            val audio = service.callAudioState
            button(if (audio?.isMuted == true) "Unmute" else "Mute") { service.setMuted(audio?.isMuted != true) }
            val routes = listOf("Earpiece" to CallAudioState.ROUTE_EARPIECE,
                "Speaker" to CallAudioState.ROUTE_SPEAKER, "Bluetooth" to CallAudioState.ROUTE_BLUETOOTH,
                "Wired headset" to CallAudioState.ROUTE_WIRED_HEADSET)
            routes.filter { (_, route) -> (audio?.supportedRouteMask ?: 0) and route != 0 }.forEach { (name, route) ->
                button(name) { service.setAudioRoute(route) }
            }
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
