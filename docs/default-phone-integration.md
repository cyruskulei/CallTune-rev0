# Rinom Calltune default Phone integration — test candidate

This change follows approval to add the Android default Phone role while retaining ordinary SIM calls. It is a candidate for validation, not a production release or a verified fix on the user's phones.

## Changes

- Includes the approved Option C branding commit, with the compact 96-dp header and corrected image assets.
- Adds a dial pad and a system role-request entry point, without requiring Firebase sign-in to place calls.
- Places calls through TelecomManager; does not implement VoIP or replace carrier transport.
- Adds a Telecom-bound InCallService and incoming/ongoing controls: answer, reject, disconnect, supported audio routes, mute, hold/resume, DTMF, SIM selection, add-call and supported merge controls.
- Video calls offer an explicit audio-only answer. Video rendering is not implemented.
- When the Phone role is granted and CallTune playback is running, Telecom's ACTIVE/HOLDING events immediately release caller-side playback. The old phone-state OFFHOOK signal cannot distinguish dialing from connected calls.
- Keeps the 20-second safety cap during outgoing playback. This cap is not used as proof that answer detection works.
- Stops playback on removal/end of calls and service destruction. Generation checks prevent a late cloud download from starting playback after answer/cancellation, or for a later call.
- Suppresses custom call-waiting music during an existing conversation.
- Legacy outgoing behavior remains for installations where CallTune is not selected as the default Phone app.
- Does not change Firebase sign-in or the cloud schema/rules. Secure cloud ownership remains outstanding.

## Validation

- Gradle assembleDebug: passed on the final source.
- Nine executable JVM lifecycle checks passed: initial dialing, duplicate callbacks, answer cancellation of pending downloads, connected/held call suppression, subsequent calls, reject/cancel, replacement calls, manual stop/destruction.
- git diff --check: passed.
- APK signature verification: passed with the local Android debug certificate.
- Android lint could not run: required lint-gradle:31.6.1 and an Android-test transitive dependency were not cached for offline execution. Do not interpret this as a lint pass.
- Android 14 emulator: APK installed successfully; Android accepted com.calltune.app as android.app.role.DIALER; DialerActivity was the resumed foreground activity.
- Simulated incoming/accept/end commands were accepted by the emulator console, but the phone service com.android.phone reported ANRs, no phone account was registered, and no RinomTelecom state callback was captured. This is inconclusive for actual call handling and stop-on-answer. No CallTune fatal exception or CallTune ANR was found in this limited captured log.
- Initial emulator attempts had boot/role-service timeouts. Final runtime evidence is retained in the validation folder. Do not infer call-handling success from role assignment or command acceptance.
- No real SIM-call test has been performed with this new code; the user's reported tests concern older installed APKs.

## Signing and deployment blockers

The candidate APK has the same package name, com.calltune.app, but uses a local debug certificate. Its certificate SHA-256 is 71901d63a993f694d4fa5e05ff39810ca4c1f54dbdbaac25811c8935ed3a827c. It cannot update the user's existing stable-signed installation. Do not uninstall either existing phone installation to install this candidate.

The stable signing keystore/passwords are not available in this workspace. GitHub's existing workflow is configured to restore its signing secrets, but this connection rejected repository object writes with HTTP 403 (Resource not accessible by integration). No remote branch or build containing this change is claimed.

## Required validation before release

Use a disposable emulator/device first. Grant the Phone role through Android's own chooser, grant call/notification permissions, and enable CallTune playback with Start CallTune. Confirm normal incoming/outgoing call UI, locked-screen presentation, audio-only handling, multi-SIM selection, call waiting, hold, DTMF, audio routes, emergency handoff to the preloaded dialer (in an appropriate controlled test facility; never make a live emergency test call), and role removal/fallback.

Build with the existing stable signing key and record the source commit, APK hash, and certificate. Then repeat the user's Samsung/Redmi answer, reject, and caller-cancel tests in both directions, including answer while a tune download is pending. Confirm caller music stops upon ACTIVE, rather than merely reaching the timer cap. Do not claim a production fix until these checks pass.

## Sources

https://developer.android.com/develop/connectivity/telecom/dialer-app
https://developer.android.com/reference/android/telecom/Call.Callback
https://developer.android.com/reference/android/telecom/InCallService
https://developer.android.com/reference/android/telephony/TelephonyManager#CALL_STATE_OFFHOOK

## Run lifecycle checks

With a Kotlin command-line installation: tests/run-outgoing-tests.sh
