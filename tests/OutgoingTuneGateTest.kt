import com.calltune.app.OutgoingTuneGate
import com.calltune.app.OutgoingTuneGate.Action.*

fun main() {
    val gate = OutgoingTuneGate<Any>()
    val first = Any()
    val second = Any()
    check(gate.update(first, false) == START) { "dialing starts playback" }
    var download = gate.token()
    check(gate.update(first, false) == KEEP && gate.isCurrent(download)) { "duplicate callback restarted tune" }
    check(gate.update(null, true) == STOP && !gate.isCurrent(download)) { "answer did not invalidate pending download" }
    check(gate.update(second, true) == STOP) { "active/held call did not block second dialing tune" }
    check(gate.update(second, false) == START) { "new call could not start after previous call ended" }
    download = gate.token()
    check(gate.update(null, false) == STOP && !gate.isCurrent(download)) { "rejection/cancellation left pending download" }
    check(gate.update(first, false) == START) { "subsequent call could not start" }
    download = gate.token()
    check(gate.update(second, false) == START && !gate.isCurrent(download)) { "previous call's download could still play" }
    download = gate.token()
    gate.invalidate()
    check(!gate.isCurrent(download)) { "manual stop/service destruction left pending playback" }
    println("PASS: 9 outgoing playback lifecycle checks")
}
