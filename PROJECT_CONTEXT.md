# CAPT USB Print — project context

Updated: 2026-10-21. Stage: **A / actual phone report received; OS USB enumeration (device count 0) blocking real printer protocol. Stage B foundations implemented but not activated.**

## Scope and confirmed facts
- Source project was imported from private \`alcovaibe/PulseAI\` into separate private \`alcovaibe/capt-usb-print\`. Original must remain unchanged. The former PulseAI network/chat/service source is excluded from the new application.
- Owner reports HONOR X9c running Android 16 / MagicOS 10, Canon LBP2900, direct USB-C ↔ USB-B cable, successful physical PrintHand test page. Android build/API/ABI/page size and actual USB descriptors are NOT yet independently measured.
- No subscription, PrintHand dependence, cloud, INTERNET permission, public release or printer firmware/service commands.
- Stage A is read-only USB diagnostics, not a printing driver. Physical printing unverified. Stage progression A→B→C→D→E requires owner hardware results.

## Upstream research (NOT imported yet)
- Candidate: \`ValdikSS/captdriver\` branch \`val\` at \`892f320c019974a5b737e000fcc2c8582264a131\`; README focuses LBP2900/3000/6000, claims multi-page, streaming and state-handling improvements; known aborts after paper jams/cartridge/door conditions. \`src/prn_lbp2900.c\` contains model-specific job/page control; \`capt-command.c\` framing; \`capt-status.c\` status decoding; \`generic-ops.c\` flow control; \`hiscoa-compress.c\` raster compression; \`rastertocapt.c\` includes CUPS raster dependency and Linux/POSIX calls. Do NOT assume other printer generations share command semantics.
- Alternatives: \`mounaiban/captdriver\` master \`62719249ac34633338be54bc74beddd0e7003d38\`, and passively maintained \`agalakhov/captdriver\` master \`175f8ff4464591feb67728c7752ac752c7b48d43\`.
- \`ValdikSS/COPYING\` states GNU GPL version 3; source headers say GPLv3 or later. If any source is ported, preserve author/license notices, include GPL-compatible project licensing and source distribution obligations before shipping APK.
- CUPS requirement in ValdikSS README concerns Linux USB backend; Android transport must instead handle its own coordination and USB timeouts. Upstream source was audited but **no upstream GPL code has been copied into stage A**.

## Minimal architecture / planned
Kotlin Android App. Stage A: Activity → single-thread executor → UsbScanner → Android UsbManager; explicit internal permission receiver; share-only-on-user-action report. Scanner enumerates all USB devices, checks a unique BULK IN/OUT interface before short claim probe, does not send data. Later: file URI/PdfRenderer → page specification and bounded raster bands → isolated CAPT/HISCOA adapter (possibly NDK after audit) → single-owner USB transport → shared serial job queue → Activity and PrintService clients. Fake transport strictly for tests only. PrintService NOT implemented yet.

## Toolchain
JDK 21 for build (AGP 9.2 requires minimum 17); Gradle 9.4.1; AGP 9.2.1 with built-in Kotlin (minimum 2.2.10); compileSdk 36, targetSdk 36, minSdk 29 (deliberate development floor, not claim of wider support). No Compose/NDK/CMake needed for read-only diagnostic proof. Inherited Gradle distribution SHA is retained. Runtime no INTERNET; CI requires network to install SDK and dependencies.

## Known limitations and verification status
- Source authored; no local Android SDK or network in execution container, thus **local APK build unavailable**. Initial Actions run 39649721266 failed at Android setup because default package `tools` was unavailable; workflow now overrides setup packages to `platform-tools`. Second run 39649883896 installed SDK and failed at resource linking because platform `@android:drawable/ic_menu_print` is absent; changed manifest to an app-owned vector icon. CI workflow on private \`main\` should build, lint, unit-test, scan permissions and retain 7-day private debug APK if green. Do not claim CI succeeded without checking actual run.
- CI success: run [39650348245](https://github.com/alcovaibe/capt-usb-print/actions/runs/39650348245), source commit `e624dc09b1ffefe25e2709f13359430bcb8f2de2`. `assembleDebug`, `lintDebug`, `testDebugUnitTest`, merged APK permission checks and private artifact upload passed. Artifact: `capt-usb-print-a1-debug`, ID `12370645206` (7-day retention). No Android device/emulator or printer was available to this run.
- Android platform APIs require physical phone verification for USB enumeration, OS/ABI/page size, request/deny/grant, detach, endpoint selection, raw descriptor read and claim probe.
- No verified VID/PID, active CAPT protocol trace, paper/RAM profile, printer status interpretations, physical print or HONOR system PrintService.
- Debug signature is ephemeral, not a stable update signature; no keystore committed. No automatic retry/printing after reconnect.

## Confirmed / unknown / how to check

| Item | State | Method |
| --- | --- | --- |
| Printer/cable/phone and PrintHand test | Owner reported | Stage A real phone test |
| Repository privacy | Confirmed private via GitHub metadata | Recheck on new commit |
| Device API/build/ABI/page size | Unknown | Stage A report |
| USB VID/PID/interface/endpoints | Unknown | Stage A report |
| USB permission/claim | Unknown | Grant/deny/reconnect with Stage A |
| CAPT status and actual print | Unknown | Stage B guarded implementation + physical paper confirmation |
| PDF/PrintService/HONOR Documents | Not implemented | Stages C/D |

## Private prereleases
- Owner authorized publishing test builds to **Releases of this private repository** only; not to a public repo, Play Store or another distribution channel.
- CI now has two jobs: `android` builds/tests/audits and uploads APK, then `publish-diagnostic-release` (only for private main push or main workflow_dispatch) downloads that exact artifact and creates a prerelease with APK, commit, test summary and inline SHA-256.
- A1 release tags: `v0.1.0-a1.build.<run_number>`; Gradle versionCode and versionName include the CI run number. GitHub Actions ephemeral debug keys remain non-upgradeable across machines; users must export diagnostic reports before reinstall. Signing owner-controlled key is a separate release task.
- Verify both CI jobs and actual release asset before saying publication completed. No CAPT code used yet and physical compatibility remains untested.

## Next actions
1. Confirm private CI \`assembleDebug\`, lint, tests and merged manifest permission audit; fix failures before sharing APK.
2. Owner installs A1 and shares anonymized report. Do not send test print or claim device ready from USB permission alone.
3. Model LBP2900 transport responses using observed device interface and verified upstream before Stage B.


## Owner-controlled signed updates (2026-10-21)
- Owner requested permanent upgradeable APKs as in private VK Music Export. **Different signing key** was generated specifically for CAPT USB Print, with a PKCS12 and corresponding public SHA-256 certificate fingerprint `6bb4ec99e2ce17383383a918c4d8b942b2a19ba06660e18ee1e1e606d84fdc5e`. Private keystore/password are NOT in Git; owner must download supplied backup ZIP and install repository Actions secret `ANDROID_SIGNING` with the full JSON.
- In absence of this secret, CI keeps testing but signed-release job is SKIPPED. Never publish an ephemeral/debug APK as a "release" again. First signed release is conditioned on adding secret and completing CI and emulator install verification.
- Release package remains `com.cryonum.captusbprint`. Debug remains `com.cryonum.captusbprint.debug` (different package, therefore no misleading update path). Signed versionCode = 1000 + GitHub run number, tagged `v0.1.0-a2-signed.build.<run>`.
- On every signed release, verify APK signature with the public pinned certificate, non-debuggable package, increasing version code, prior APK signature and Android 16 emulator in-place `adb install -r` (no uninstall). No PrintService or CAPT printing yet.
- Layout correction following owner screenshot: show USB device cards and concise permission states by default; full technical report collapsible. Share always includes the full anonymized report. First owner phone confirmation required.
- Existing old A1 debug releases kept for history; they are not part of the signed update chain. We did not modify VK Music Export.


## Continuation after first physical report (2026-10-21)
**Measured by owner's diagnostic APK 0.1.0-a1.4, not assumed from product specs:**
- Device model `HONOR BRP-NX1`; Android API 36, MagicOS build string `BRP-N21 10.0.0.172(C636E6R4P1)` (as shown in screenshot); ABIs `arm64-v8a, armeabi-v7a, armeabi`; memory page size exactly **4096 bytes**; USB Host feature `true`.
- **Connected USB device count: 0**; no printer descriptor, no VID:PID, no endpoints, no permission request. Do not blame printer or cable without new evidence. Physical PrintHand test previously succeeded on the same phone/printer/cable, but timing and active connections in this report are not independently established.
- UI issue: system status bar overlapped activity heading on Android 16; root window now accounts for system-bar/display-cutout insets; long report is collapsed by default (share still exports full report).
- Device attach is an Activity intent, **not** a broadcast (see https://developer.android.com/reference/android/hardware/usb/UsbManager). The prior dynamic ATTACHED receiver was unreliable; now only foreground list-key observation at 2.5-second intervals (no repeated probe when unchanged), plus a DETACHED broadcast.
- Redacted count-only `UsbObservation` ring holds 16 changes and exports with each snapshot; zero-device guidance expressly says OS enumeration is a prerequisite for USB permission.
- Isolated, **unwired** Stage B foundations: pure `UsbLinkSelector` (only unambiguous BULK IN/OUT pair), bounded `BoundedBulkWriter` (partial transfers, timeout and cancellation => uncertain; never blindly resends a whole packet), `JobState` (unknown after interrupted transfer, no automatic restarts, never labels write completion printed), `PagePlan` (bounded ranges, copies, overlapping range rejection). JVM unit tests test deterministic synthetic cases; synthetic outcomes are NOT real CAPT proof.
- Deliberately no print buttons, no new USB transfers or firmware/service commands while Stage A physical enumeration unverified. No new unsigned APK in private Releases; owner-controlled `ANDROID_SIGNING` secret still required before first release-signature build. Prior A1 remains historically available, not a compatible update.
- Next hardware step: after owner installs a **signed** build (once secret is configured), leave printer powered and direct cable attached; reopen diagnostics and refresh/reconnect once if zero devices; share full report. Actual VID/PID endpoints and permission/claim must precede CAPT adapter wiring.

## Testing addendum
- App unit tests: new Stage B pure-function tests covering unique/ambiguous endpoint matching, partial transfer and interrupted outcome, legal job state transitions and invalid page ranges/copies. Added USB event count-ring tests. These are compile/logic tests only; Android hardware printer printing remains **not tested**.
- Check latest GitHub Actions job and signed release independently; never infer release from source commit.

## Additional isolated protocol layer (parallel change merged)
- In parallel with the USB enumeration/engine work above, `core/CaptFrame`, `core/BulkIo`, `core/JobSpecification`, `core/JobLedger` and `StageBCoreTest` were added as bounded, pure Kotlin stage B prep. These are NOT wired to the UI, and their synthetic tests are not real printer proofs. The existing `engine/*` and `UsbObservation` work was retained in the merge; no concurrent work discarded.
- `docs/STAGE-B-FOUNDATION.md` describes the protocol upstream, hardware gate and limitations.

## Verified source checkpoint
- Merge commit `5f433818e560fe1a1918e0f59217273c23e92414` passed run [39662757271](https://github.com/alcovaibe/capt-usb-print/actions/runs/39662757271): assembleDebug, lintDebug, testDebugUnitTest, Python syntax and merged APK permissions check succeeded, internal diagnostic artifact uploaded.
- Signing availability job succeeded but the permanent-key signed release job skipped because owner's `ANDROID_SIGNING` secret is not yet installed. No new Releases published.
- Later code changes, if any, require their own CI check. The only first-phone report still shows USB Host=true but 0 enumerated devices; no actual LBP2900 traffic or printed output was claimed.
