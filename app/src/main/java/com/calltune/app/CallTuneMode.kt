package com.calltune.app

/** Playback choices only. Subscription/payment entitlement is not implemented here. */
enum class CallTuneMode(val setting: String) {
    FREE("free"), PRO("pro"), PRO_PLUS("pro_plus");

    fun next(): CallTuneMode = when (this) {
        FREE -> PRO
        PRO -> PRO_PLUS
        PRO_PLUS -> FREE
    }

    companion object {
        fun fromSetting(value: String?): CallTuneMode =
            entries.firstOrNull { it.setting == value } ?: FREE
    }
}

/** One policy shared by the screen and both SIM-call event paths. */
object TunePolicy {
    enum class Incoming { OWN, ASSIGNED }
    enum class Outgoing { OFF, OWN, RECEIVER }

    fun incoming(mode: CallTuneMode, hasAssignment: Boolean): Incoming =
        if (mode == CallTuneMode.PRO_PLUS && hasAssignment) Incoming.ASSIGNED else Incoming.OWN

    fun outgoing(mode: CallTuneMode, enabled: Boolean): Outgoing = when {
        !enabled -> Outgoing.OFF
        mode == CallTuneMode.FREE -> Outgoing.OWN
        else -> Outgoing.RECEIVER
    }
}
