# CAPT USB Print — Android / Canon LBP2900

Independent offline Android CAPT prototype for the owner's HONOR X9c (Android API 36) and Canon LBP2900. No PrintHand dependency or app-side INTERNET permission. Other Android devices and printers are not claimed supported.

**Verified on the owner's hardware:** Stage A USB diagnostics; Stage B A4/600-dpi test sheets, repeated separate jobs and two distinct sheets in one CAPT job (signed build 71). On signed build 105 the owner printed actual PDF pages 1 and 2 as two separate jobs after a printer power cycle. Stage C remains in development: the new 2–3-sheet PDF job is not hardware-confirmed, and intermittent ReserveUnit `0x8C` is unresolved. JPEG/PNG intake and preview exist; image printing, the production queue and PrintService are pending.

## Hardware and bounded diagnostic
On HONOR BRP-NX1: API 36, measured 4096-byte page; Canon LBP2900 04A9:2676, interface 0 / class 7/1/2, BULK OUT 0x01 and IN 0x82, permission and guarded claim verified. Manual CAPT GetBasicStatus / GetExtendedStatus / GetInputStatus / GetPrinterInfo responses are tracked in private JSON. Device-list monitoring itself does not send CAPT commands; diagnostics exclude document content and printer serial numbers.

### Launcher icon
The launcher uses an adaptive white background and black printer line-art, with round and monochrome variants.

## Stage C: guarded document printing

Local PDF/JPEG/PNG intake, sampled image previews (including EXIF and white-paper transparency), page selection, collated copy plans, orientation and content rotation are implemented. The owner confirmed preview/insets and offline PDF preparation on signed build 93. Portrait PDF pages are prepared in 64-row bands using the existing HISCOA encoder; a full 600-dpi ARGB page is never allocated.

Single-sheet PDF printing requires exactly the visible page, one copy and portrait A4. The experimental batch requires exactly **2–3 total sheets**, including copies, and the first source page visible in the preview. Each distinct page is prepared before USB, at most 4 MiB compressed per page. Both actions require explicit confirmation and previously granted USB permission.

The batch uses one reservation, sequential page-counter confirmation, **one** continuation after sheet 1 and one end/release. Its separate three-sheet counter checks leave Stage B unchanged. GetBasicStatus `0x88` stops the batch before reservation; this detects the captured condition and does not repair it. No automatic retry, undocumented reset or speculative recovery is performed.

A private JSON marker is written before USB and after each confirmed batch sheet, followed by a terminal result. An unfinished marker remains uncertain even if some sheets were confirmed. Only the explicit report-share button exports it. No document name, URI, raster, content hash or serial number is included.

The 260 px (about 11 mm) FIT inset is provisional, not a measured mechanical margin. Landscape printing, JPEG/PNG print bands, a lifecycle-safe unified queue and Android PrintService are not ready.

## Signed releases and upgrade compatibility

Private signed prereleases: [Releases](https://github.com/alcovaibe/capt-usb-print/releases). Future installed **signed** versions upgrade in place via the ordinary Android APK installer, keeping app settings. Signing certificate and `applicationId=com.cryonum.captusbprint` are permanently pinned. `versionCode` increases for each GitHub Actions release build.

The previous A1 diagnostic prerelease is a **debug app** using `com.cryonum.captusbprint.debug`; it cannot upgrade into a signed release. Install the first permanently signed A2 APK as a separate app, then all newer signed A2+ builds can install over it. There is no automatic in-app updater (private GitHub release requires authorization and the diagnostic app deliberately has no INTERNET permission).

**Owner-controlled signing setup is complete.** The repository secret `ANDROID_SIGNING` is configured, and signed build 33 passed the signature, package/version, prior-release and Android 16 in-place-upgrade checks before publication. Keep *two separate encrypted backups* of `CAPT_USB_Print_SIGNING_BACKUP.zip` from the conversation; it contains the private key backup and secret JSON. Do **not** commit, send publicly, upload to Releases or share that backup. Losing the private key means losing the ability to install later APKs as compatible upgrades.

CI never generates or changes the private signing key and cannot publish a release without the configured secret. `config/release-certificate.sha256` pins the independently generated public signing certificate, not the secret. Only the release job for trusted `main` has access to signing material, reconstructed outside the checkout and removed on completion. A signed release must pass build/lint/unit tests, APK verification and emulator install or in-place update on API 36 before publishing.

## Local development and diagnostics

JDK 21; Android Gradle Plugin 9.2.1; Gradle Wrapper 9.4.1; compileSdk/targetSdk 36; minSdk 29; Android SDK Build Tools 36.0.0. Build debug with `./gradlew :app:assembleDebug`. Debug is NOT upgradeable release. Never use ephemeral debug signing for user-facing Releases.

Next hardware checkpoint after an owner-authorized signed release: known PDF, portrait A4, preview page 1, range `1-3`, copies `1`; submit once, check three physical sheets in order, then manually share the latest PDF JSON. Stop after an error or uncertain result. Two copies of one page are a separate later checkpoint; do not combine independent experimental prints without checking the first outcome.

See [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md) for audit, limitations and next steps. This is unofficial software not endorsed by Canon.

### CAPT framing and source attribution

Pure-Kotlin CAPT framing and bounded I/O foundations remain in the core, while owner-tested Stage B hardware engines use the pinned HISCOA encoder. See docs and GPL notices for the separate upstream path and test evidence.

### JSON diagnostics and Telegram sharing
Manual actions (USB rescan, permission request, and CAPT queries) append private diagnostic history. The explicit Share button exports a new immutable `.json` snapshot. The Show/Hide Details control and background USB list monitoring never open a share window. If installed, standard Telegram is opened directly as a document share target; the user still selects a chat and confirms sending. If unavailable, Android Sharesheet is used. The app has no INTERNET permission and does not transmit on its own. Files are offered through a nonexported scoped FileProvider with temporary URI read grants (cache reports/ only), not file:// paths.
The JSON contains app version/revision, a scan text record plus structured numeric VID/PID and permission values, bounded manual event history, full bounded CAPT response frame hex when available (header included), and separate decoded values. Raw data is retained even on field-parse failures after a full reply. The original on-screen diagnostic remains available. There is no printer serial query, document data, or automatic print retry. JSON schemaVersion=1; unknown/missing values are null rather than invented.


### Physically verified Stage B sheets

The owner confirmed the first A4/600-dpi sheet, two repeat separate jobs, and two distinct sheets in one reserved CAPT job on signed build 71. CAPT completed counters matched each printed test; this is not proof of real PDF printing. Reports are saved locally and sent only by an explicit Share action. No automatic retry. See Stage B docs and THIRD-PARTY-NOTICES.md.

