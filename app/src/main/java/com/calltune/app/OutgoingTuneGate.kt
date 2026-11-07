package com.calltune.app

/** Main-thread call selection and generation guard, independent of Android. */
class OutgoingTuneGate<T> {
    enum class Action { START, STOP, KEEP }
    private var selected: T? = null
    private var generation = 0L

    fun update(dialingCall: T?, connectedOrHeld: Boolean): Action {
        if (connectedOrHeld || dialingCall == null) {
            selected = null
            invalidate()
            return Action.STOP
        }
        if (selected === dialingCall) return Action.KEEP
        selected = dialingCall
        invalidate()
        return Action.START
    }

    fun invalidate() { generation++ }
    fun token(): Long = generation
    fun isCurrent(token: Long): Boolean = token == generation
}
