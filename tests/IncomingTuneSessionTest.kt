import com.calltune.app.IncomingTuneSession

fun main() {
    val session = IncomingTuneSession()
    val first = Any()
    val second = Any()
    check(session.select(first, null)) { "cold service must accept an already ringing snapshot" }
    val initial = session.token()
    check(session.isCurrent(initial)) { "initial preparation must be eligible" }
    check(!session.select(first, null) && session.isCurrent(initial)) { "reconciliation must not restart preparation" }
    check(session.select(first, "123")) { "late number must allow caller-specific selection" }
    val identified = session.token()
    check(!session.isCurrent(initial)) { "anonymous download must not replace identified tune" }
    check(!session.select(first, "123") && session.isCurrent(identified)) { "duplicate phone/Telecom event must not restart tune" }
    check(!session.select(first, "") && session.isCurrent(identified)) { "missing details must not erase known caller" }
    session.invalidate() // answer, caller cancel, Stop and service destruction use this boundary
    check(!session.isCurrent(identified)) { "ended/disabled call must reject pending preparation and downloads" }
    check(session.select(second, "123")) { "next call from same number must play" }
    val replacement = session.token()
    check(!session.isCurrent(identified) && session.isCurrent(replacement)) { "previous call must not replace next call" }
    check(session.select(first, "456") && !session.isCurrent(replacement)) { "replacement ringing call must invalidate old work" }
    session.invalidate()
    check(!session.isCurrent(session.token())) { "no selection may play even with latest generation" }
    check(session.select(first, null)) { "ringing session starts before silencing" }
    val preparing = session.token()
    session.silence()
    check(!session.isCurrent(preparing)) { "silencing cancels pending preparation" }
    check(!session.isCurrent(session.token())) { "silenced session cannot play using a new token" }
    check(!session.select(first, "late-number")) { "late caller identity must not undo silence" }
    check(!session.select(first, null)) { "duplicate Telecom snapshot must not undo silence" }
    session.silence()
    check(!session.isCurrent(session.token())) { "repeated silence remains silent" }
    check(session.select(second, "next")) { "a different ringing call can play" }
    check(session.isCurrent(session.token())) { "silencing must not leak into next call" }
    session.silence()
    session.invalidate()
    check(session.select(second, "next") && session.isCurrent(session.token())) {
        "end-of-call cleanup resets silence even if a legacy key is reused"
    }
    println("PASS: 21 incoming session lifecycle and silence checks")
}
