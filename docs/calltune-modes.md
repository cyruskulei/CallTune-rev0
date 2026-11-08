# Rinom CallTune modes

This implements the playback choices approved on 8 November 2026. These are development mode choices, not paid subscription enforcement.

| Selected mode on this phone | Incoming audio on this phone | Outgoing custom audio with the switch ON |
|---|---|---|
| Free | Own default tune | Own default tune |
| Pro | Own default tune | Dialled receiver's shared tune, if available |
| Pro Plus | Local assignment for this caller; own default if none | Dialled receiver's shared tune, if available |

Each phone makes its own playback choice. No audio is injected into a SIM call. A caller hears the receiver's shared tune only when the calling phone selects Pro/Pro Plus, enables outgoing playback, and can retrieve that tune. A receiver's mode alone does not force the caller's phone to play it. Both phones selecting Free with outgoing playback ON now each hear their own tune.

## Switch and saved settings

The existing `caller_hears` preference is retained. ON selects the outgoing source in the table; OFF disables custom outgoing playback in every mode. OFF does not disable incoming playback. The label now names the source selected by the current mode. Changing modes preserves ON/OFF. Changes apply to the next call; this does not replace audio in the middle of a call.

Existing `free` and `pro` preferences remain valid; `pro_plus` is new. Unknown settings default to Free. No saved caller assignments or default tune are deleted. Assignments are used only in Pro Plus and stay available when switching back. The new own-tune picker saves the default separately from the favourite-caller picker; a favourite number is required for an assignment. The preview tests the own default rather than an arbitrary favourite's tune.

If no own default is saved, the bundled tune is used. Unreadable own audio falls back to the bundled tune. Pro/Pro Plus outgoing playback retains the existing cached/cloud receiver lookup; unavailable receiver audio does not pretend to be the receiver by substituting the caller's own tune. Incoming playback no longer fetches the caller's cloud tune in any mode.

## Themes

- Free: “Enjoy your CallTune while you wait for your call to be answered.”
- Pro: “The receiver’s tune, a shared experience.” The caller enjoys the receiver’s CallTune while the receiver hears their own CallTune.
- Pro Plus: “A special tune for your favourite people.” Let callers enjoy your shared CallTune while you recognise your favourite callers by the personal tunes you assign to them.

## Validation before device testing

Run `bash tests/run-outgoing-tests.sh` with Kotlin CLI tools installed, then Gradle `:app:assembleDebug :app:lintDebug`. The JVM tests cover mode selection and call/session generation, not Android audio, notification delivery, or real calls. Build with the existing stable signing configuration for an update install; do not uninstall to bypass a signing mismatch.

Use two distinguishable, rights-cleared tunes on the phones. Check Free ON and OFF in both calling directions, then Pro and Pro Plus with and without a favourite assignment. Test answer, reject, caller cancellation, unavailable shared tune, and reboot. Record which source was actually heard, screen/tune delay and stop timing. Test old-build switch behaviour before updating if comparing the previously reported “both Free + ON” result.

## Still open

Secure cloud publishing remains disabled; selecting a local tune does not publish it. Existing remote data or cached audio may not be current. Secure ownership, full international number keys (existing keys truncate numbers), entitlement, cache freshness and cloud validation are separate outstanding work. No Firebase rules or billing changed.

The known first-call startup delay, Samsung notification action failures and ongoing CallStyle posting defect are not fixed by this mode patch. Telecom answer/decline/end handling and existing playback-stop guards are preserved. Legacy OFFHOOK still cannot reliably identify remote answer. Carrier ringback may coexist with local playback; this patch does not mute or replace network audio. Pro playback is not promised to be synchronised across phones.
