package com.calltune.app

/** One incoming call, including a number delivered after the initial ringing event. */
class IncomingTuneSession {
    private var selected: Any? = null
    private var knownNumber: String? = null
    private var generation = 0L
    private var silenced = false

    fun select(call: Any, number: String?): Boolean {
        val nextNumber = number?.takeIf { it.isNotBlank() }
        if (selected === call && (silenced || nextNumber == null || nextNumber == knownNumber)) return false
        silenced = false
        selected = call
        knownNumber = nextNumber
        generation++
        return true
    }

    fun silence() { silenced = true; generation++ }
    fun invalidate() { selected = null; knownNumber = null; silenced = false; generation++ }
    fun token(): Long = generation
    fun isCurrent(token: Long): Boolean = selected != null && !silenced && generation == token
}
