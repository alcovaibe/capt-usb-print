# CAPT USB Print — Android / Canon LBP2900

Independent offline Android CAPT prototype for the owner's HONOR X9c (Android API 36) and Canon LBP2900. No PrintHand dependency or app-side INTERNET permission. Other Android devices and printers are not claimed supported.

**Verified:** Stage A USB diagnosis and read-only queries; Stage B owner-confirmed A4/600-dpi physical test sheets, repeat sessions and two distinct sheets in one CAPT job (signed build 71, 2026-10-22). **In progress:** Stage C PDF/JPEG/PNG local intake, bounded preview and offline raster preparation. Signed build 89 previewed an 11-page PDF; the owner confirmed seven UI/offline checks on signed build 93, including a PDF mono-raster preparation with no reported error. No real PDF or image page has been printed through this app. Android PrintService and production stabilization are not implemented.

## Hardware and bounded diagnostic
On HONOR BRP-NX1: API 36, measured 4096-byte page; Canon LBP2900 04A9:2676, interface 0 / class 7/1/2, BULK OUT 0x01 and IN 0x82, permission and guarded claim verified. Manual CAPT GetBasicStatus / GetExtendedStatus / GetInputStatus / GetPrinterInfo responses are tracked in private JSON. Device-list monitoring itself does not send CAPT commands; diagnostics exclude document content and printer serial numbers.

### Launcher icon
The launcher now uses an adaptive icon like the Bluetooth Disable project: a solid white background, the approved black printer line-art, a round variant, and a monochrome foreground for themed icons.

### Stage B accepted and Stage C work

The exact USB ID 04A9:2676 is mapped to Canon LBP2900. The guarded two-sheet CAPT path is physically owner-accepted; repeat and single-sheet tests also succeeded. New document features do not modify the accepted Stage B print engines. Stage C has user-triggered local preview and a portrait-only offline PDF 64-row print-mode band preparation. Actual PDF/image CAPT jobs, JPEG/PNG print bands, physical landscape mapping, queue/cancellation and PrintService are still pending.

## Signed releases and upgrade compatibility

Private signed prereleases: [Releases](https://github.com/alcovaibe/capt-usb-print/releases). Future installed **signed** versions upgrade in place via the ordinary Android APK installer, keeping app settings. Signing certificate and `applicationId=com.cryonum.captusbprint` are permanently pinned. `versionCode` increases for each GitHub Actions release build.

The previous A1 diagnostic prerelease is a **debug app** using `com.cryonum.captusbprint.debug`; it cannot upgrade into a signed release. Install the first permanently signed A2 APK as a separate app, then all newer signed A2+ builds can install over it. There is no automatic in-app updater (private GitHub release requires authorization and the diagnostic app deliberately has no INTERNET permission).

**Owner-controlled signing setup is complete.** The repository secret `ANDROID_SIGNING` is configured, and signed build 33 passed the signature, package/version, prior-release and Android 16 in-place-upgrade checks before publication. Keep *two separate encrypted backups* of `CAPT_USB_Print_SIGNING_BACKUP.zip` from the conversation; it contains the private key backup and secret JSON. Do **not** commit, send publicly, upload to Releases or share that backup. Losing the private key means losing the ability to install later APKs as compatible upgrades.

CI never generates or changes the private signing key and cannot publish a release without the configured secret. `config/release-certificate.sha256` pins the independently generated public signing certificate, not the secret. Only the release job for trusted `main` has access to signing material, reconstructed outside the checkout and removed on completion. A signed release must pass build/lint/unit tests, APK verification and emulator install or in-place update on API 36 before publishing.

## Local development and diagnostics

JDK 21; Android Gradle Plugin 9.2.1; Gradle Wrapper 9.4.1; compileSdk/targetSdk 36; minSdk 29; Android SDK Build Tools 36.0.0. Build debug with `./gradlew :app:assembleDebug`. Debug is NOT upgradeable release. Never use ephemeral debug signing for user-facing Releases.

Current next hardware check: confirm Android 16 scroll viewport insets, first-page Auto orientation, separate 90-degree content rotation and optional offline PDF current-page band verification. No new document USB commands. A new signed APK is published only after trusted main CI passes.

See [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md) for audit, limitations and next steps. This is unofficial software not endorsed by Canon.

### CAPT framing and source attribution

Pure-Kotlin CAPT framing and bounded I/O foundations remain in the core, while owner-tested Stage B hardware engines use the pinned HISCOA encoder. See docs and GPL notices for the separate upstream path and test evidence.

### JSON diagnostics and Telegram sharing
Manual actions (USB rescan, permission request, and CAPT queries) append private diagnostic history. The explicit Share button exports a new immutable `.json` snapshot. The Show/Hide Details control and background USB list monitoring never open a share window. If installed, standard Telegram is opened directly as a document share target; the user still selects a chat and confirms sending. If unavailable, Android Sharesheet is used. The app has no INTERNET permission and does not transmit on its own. Files are offered through a nonexported scoped FileProvider with temporary URI read grants (cache reports/ only), not file:// paths.
The JSON contains app version/revision, a scan text record plus structured numeric VID/PID and permission values, bounded manual event history, full bounded CAPT response frame hex when available (header included), and separate decoded values. Raw data is retained even on field-parse failures after a full reply. The original on-screen diagnostic remains available. There is no printer serial query, document data, or automatic print retry. JSON schemaVersion=1; unknown/missing values are null rather than invented.


### Physically verified Stage B sheets

The owner confirmed the first A4/600-dpi sheet, two repeat separate jobs, and two distinct sheets in one reserved CAPT job on signed build 71. CAPT completed counters matched each printed test; this is not proof of real PDF printing. Reports are saved locally and sent only by an explicit Share action. No automatic retry. See Stage B docs and THIRD-PARTY-NOTICES.md.

## Current Stage C scope

The owner accepted Stage B after repeat physical test sheets and two distinct
pages in one CAPT job. Stage C implements offline PDF/JPEG/PNG intake and preview.
Build 89 displayed an 11-page PDF on HONOR; it does not claim printed PDFs.
The follow-up branch fixes Android 16 scrolling insets, adds first-page-based
Auto orientation and explicit content rotation, and begins offline portrait PDF
64-line band rasterization. Do not infer PrintService or real-document print
support from this README's older Stage B notes.

### Next offline Stage C checkpoint

A private feature branch reuses the existing Stage B native HISCOA encoder to
compress each sequential PDF mono band, immediately discarding the result after
bounded size checks. The UI only reports band counts and byte totals, not
document data or hashes. There are no new USB/CAPT document commands, no new
Android permissions, and no automatic print retries. JNI/HISCOA integration on
the owner's PDF requires a separate signed-device check before real document
printing is developed.
