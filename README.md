# CAPT USB Print — Android / Canon LBP2900

Independent offline Android CAPT prototype for the owner's HONOR X9c (Android API 36) and Canon LBP2900. No PrintHand dependency or app-side INTERNET permission. Other Android devices and printers are not claimed supported.

**Verified on the owner's hardware:** Stage A USB diagnostics; Stage B A4/600-dpi test sheets, repeated separate jobs and two distinct sheets in one CAPT job (signed build 71). On signed build 105 the owner printed actual PDF pages 1 and 2 as two separate jobs after a printer power cycle. Stage C code completion is in PR #25 with owner-authorized conditional acceptance. General queue, image and landscape code are described below; intermittent ReserveUnit `0x8C` remains unresolved. Physical confirmation of the new paths and PrintService integration are separate.

## Hardware and bounded diagnostic
On HONOR BRP-NX1: API 36, measured 4096-byte page; Canon LBP2900 04A9:2676, interface 0 / class 7/1/2, BULK OUT 0x01 and IN 0x82, permission and guarded claim verified. Manual CAPT GetBasicStatus / GetExtendedStatus / GetInputStatus / GetPrinterInfo responses are tracked in private JSON. Device-list monitoring itself does not send CAPT commands; diagnostics exclude document content and printer serial numbers.

### Launcher icon
The launcher uses an adaptive white background and black printer line-art, with round and monochrome variants.

## Stage C: local document queue

PDF, JPEG and PNG share one bounded document queue and CAPT runner. Select page ranges, 1–20 collated copies, A4 portrait/landscape and optional content rotation, inspect the actual monochrome print preview, then explicitly confirm submission. Limits are 1000 source pages, 2000 output sheets and four live jobs; an unsupported plan is rejected without truncation.

Each distinct page is rasterized/compressed before USB into private CRC-checked spool files. Copies reuse the same files. Memory holds a bounded sampled image (at most 4M decoded pixels), a 64-row raster band and the current encoded strip. A compressed page is limited to 12 MiB, total private queue reservations to 256 MiB, with an additional 8 MiB free-space reserve. Disk exhaustion or preparation failure stops before printing. The provisional 260 px (~11 mm) FIT inset is not a measured mechanical margin; 100% scale is not offered.

A connected-device foreground service owns serial document execution beyond the Activity lifecycle. Queue and notification expose cancellation. After uncertain/failed USB execution, successor jobs pause until explicit inspection/continue. Unfinished work never resumes after process restart, reconnect or reboot; USB-started records become unknown, other unfinished records fail, sources/spools are removed. No automatic replay or speculative CAPT reset is implemented. Grant USB access on the main screen before adding jobs; notification permission is optional for printing but permits a visible foreground notification.

The general engine retains the accepted portrait CAPT descriptor: landscape is a raster transform. It sends one reservation, sequential StartPrint(N), one inter-page continuation for multi-sheet jobs and one end/release. Accepted Stage B engines/helpers and signing identity are unchanged. Read-only BasicStatus 0x88 detection does not repair ReserveUnit 0x8C.

Private history is bounded to 50 jobs and reports to 24; JSON records settings, app/device versions, CAPT state and confirmed counters, excluding document names/URIs/content/raster/hashes/serials. Sharing is explicit. Preview/settings survive screen recreation; queued jobs own independent private source copies.

**Acceptance distinction:** the owner authorized conditional release/print success to finish code. Actual hardware evidence remains separate PDF sheets on build 105 and accepted Stage A/B. New queue, image output, landscape and MagicOS screen-off behavior are not reported as physically observed. Stage D PrintService is under verification below; its hardware acceptance remains open.

## Stage D: Android system printing (under verification)

The Android PrintService advertises one stable Canon LBP2900 USB slot and the
existing A4 / 600 dpi / monochrome / simplex profile. Enable CAPT USB Print through
the app's system-print settings button, grant USB access on the main screen, then
select the Canon from an application's Android Print dialog. Missing/ambiguous USB
devices or permissions block an unstarted job with a setup message.

System PDFs enter the same bounded queue and serial worker as direct documents.
Their existing point scale, margins and layout are preserved; the app's FIT inset
is not applied again. Original-versus-reduced PDF page selections are resolved
once, with ambiguous mappings rejected. One layer expands collated copies.
PrintDocument pipes are read off the main thread with a 64 MiB / 30-second bound,
cancellation and shared disk quotas. PrintJob APIs stay on the main thread.

Disabling/disconnecting the service cancels its work without cancelling unrelated
direct jobs. Tagged interrupted jobs are reconciled without automatic replay.
Printer-confirmed completion, cancellation before printing and uncertain USB
outcomes remain distinct. Queue history exposes a separate explicit system JSON
share button, with no document labels, URIs, content or serials.

Automated build/raster/framework checks and final-HEAD CI are tracked in
[PR #26](https://github.com/alcovaibe/capt-usb-print/pull/26). Actual routing/printing from HONOR
Documents and another Android print source is not yet confirmed; Stage D hardware
acceptance remains open. No new CAPT command, reset, retry or signing change is
introduced.

## Signed releases and upgrade compatibility

Public signed prereleases: [Releases](https://github.com/alcovaibe/capt-usb-print/releases). The owner authorized public repository visibility on 2026-10-23 so standard GitHub-hosted CI can run without consuming the private-repository minute allowance. Public visibility is confirmed in GitHub metadata and Settings. Source, history, PRs, release APKs and Actions logs are readable by everyone; signing credentials and on-device document/diagnostic data remain private. Future installed **signed** versions upgrade in place via the ordinary Android APK installer, keeping app settings. Signing certificate and `applicationId=com.cryonum.captusbprint` are permanently pinned. `versionCode` increases for each GitHub Actions release build.

The previous A1 diagnostic prerelease is a **debug app** using `com.cryonum.captusbprint.debug`; it cannot upgrade into a signed release. Install the first permanently signed A2 APK as a separate app, then all newer signed A2+ builds can install over it. There is no automatic in-app updater; download a signed APK from Releases and install it with Android's ordinary installer. The app deliberately has no INTERNET permission.

**Owner-controlled signing setup is complete.** The repository secret `ANDROID_SIGNING` is configured, and signed build 33 passed the signature, package/version, prior-release and Android 16 in-place-upgrade checks before publication. Keep *two separate encrypted backups* of `CAPT_USB_Print_SIGNING_BACKUP.zip` from the conversation; it contains the private key backup and secret JSON. Do **not** commit, send publicly, upload to Releases or share that backup. Losing the private key means losing the ability to install later APKs as compatible upgrades.

CI never generates or changes the private signing key and cannot publish a release without the configured secret. `config/release-certificate.sha256` pins the independently generated public signing certificate, not the secret. Only the release job for trusted `main` has access to signing material, reconstructed outside the checkout and removed on completion. A signed release must pass build/lint/unit tests, APK verification and emulator install or in-place update on API 36 before publishing.

## Local development and diagnostics

JDK 21; Android Gradle Plugin 9.2.1; Gradle Wrapper 9.4.1; compileSdk/targetSdk 36; minSdk 29; Android SDK Build Tools 36.0.0. Build debug with `./gradlew :app:assembleDebug`. Debug is NOT upgradeable release. Never use ephemeral debug signing for user-facing Releases.

PR CI builds/lints/tests the APK, checks permissions/native packaging and installs/launches it on API 36. Debug-only offline instrumentation runs Android PDF, PNG alpha, JPEG EXIF, actual monochrome preview, spool and native HISCOA checks without printer access. The signed-main pipeline independently verifies permanent signature and in-place upgrade before publication.

See [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md) for audit, limitations and next steps. This is unofficial software not endorsed by Canon.

### CAPT framing and source attribution

Pure-Kotlin CAPT framing and bounded I/O foundations remain in the core, while owner-tested Stage B hardware engines use the pinned HISCOA encoder. See docs and GPL notices for the separate upstream path and test evidence.

### JSON diagnostics and Telegram sharing
Manual actions (USB rescan, permission request, and CAPT queries) append private diagnostic history. The explicit Share button exports a new immutable `.json` snapshot. The Show/Hide Details control and background USB list monitoring never open a share window. If installed, standard Telegram is opened directly as a document share target; the user still selects a chat and confirms sending. If unavailable, Android Sharesheet is used. The app has no INTERNET permission and does not transmit on its own. Files are offered through a nonexported scoped FileProvider with temporary URI read grants (cache reports/ only), not file:// paths.
The JSON contains app version/revision, a scan text record plus structured numeric VID/PID and permission values, bounded manual event history, full bounded CAPT response frame hex when available (header included), and separate decoded values. Raw data is retained even on field-parse failures after a full reply. The original on-screen diagnostic remains available. There is no printer serial query, document data, or automatic print retry. JSON schemaVersion=1; unknown/missing values are null rather than invented.


### Physically verified Stage B sheets

The owner confirmed the first A4/600-dpi sheet, two repeat separate jobs, and two distinct sheets in one reserved CAPT job on signed build 71. CAPT completed counters matched each printed test; this is not proof of real PDF printing. Reports are saved locally and sent only by an explicit Share action. No automatic retry. See Stage B docs and THIRD-PARTY-NOTICES.md.

