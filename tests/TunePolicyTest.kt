import com.calltune.app.CallTuneMode
import com.calltune.app.TunePolicy

fun main() {
    var checks = 0
    fun verify(condition: Boolean, message: String) {
        check(condition) { message }
        checks++
    }
    // Product acceptance examples: an old favourite assignment must not change
    // Free or Pro; only Pro Plus recognises the caller by that local tune.
    val incoming = listOf(
        Triple(CallTuneMode.FREE, false, TunePolicy.Incoming.OWN),
        Triple(CallTuneMode.FREE, true, TunePolicy.Incoming.OWN),
        Triple(CallTuneMode.PRO, false, TunePolicy.Incoming.OWN),
        Triple(CallTuneMode.PRO, true, TunePolicy.Incoming.OWN),
        Triple(CallTuneMode.PRO_PLUS, false, TunePolicy.Incoming.OWN),
        Triple(CallTuneMode.PRO_PLUS, true, TunePolicy.Incoming.ASSIGNED)
    )
    incoming.forEach { (mode, assignment, expected) ->
        verify(TunePolicy.incoming(mode, assignment) == expected,
            "$mode receiver with assignment=$assignment must hear $expected")
    }
    verify(TunePolicy.outgoing(CallTuneMode.FREE, true) == TunePolicy.Outgoing.OWN,
        "Free ON must select local own tune, never a receiver cloud lookup")
    verify(TunePolicy.outgoing(CallTuneMode.PRO, true) == TunePolicy.Outgoing.RECEIVER,
        "Pro ON must select the receiver's shared tune")
    verify(TunePolicy.outgoing(CallTuneMode.PRO_PLUS, true) == TunePolicy.Outgoing.RECEIVER,
        "Pro Plus caller must still hear the receiver's shared tune, not their assigned local tune")
    CallTuneMode.entries.forEach { mode ->
        verify(TunePolicy.outgoing(mode, false) == TunePolicy.Outgoing.OFF,
            "OFF must suppress outgoing custom audio in $mode")
        verify(CallTuneMode.fromSetting(mode.setting) == mode,
            "mode must survive reopening the app")
    }
    verify(CallTuneMode.fromSetting(null) == CallTuneMode.FREE, "fresh settings default to Free")
    verify(CallTuneMode.fromSetting("unknown") == CallTuneMode.FREE, "unknown mode uses Free safely")
    verify(CallTuneMode.fromSetting("pro") == CallTuneMode.PRO, "existing Pro selection is preserved")
    verify(CallTuneMode.FREE.next() == CallTuneMode.PRO &&
        CallTuneMode.PRO.next() == CallTuneMode.PRO_PLUS &&
        CallTuneMode.PRO_PLUS.next() == CallTuneMode.FREE, "all three modes must be reachable")
    println("PASS: $checks mode policy acceptance checks")
}
