# CAPT USB Print — private diagnostics (stage A)

Standalone offline Android USB diagnostics for Canon LBP2900 / HONOR X9c. This stage detects attached USB devices, asks for permission and shares anonymized device reports. **It cannot yet print.** No PrintHand or network services are used.

### Device metadata and the next USB diagnostic
The latest hardware report (signed build `0.1.0-a2.33`, 2026-10-22) confirms HONOR API 36, build `BRP-N21 10.0.0.172(C636E6R4P1)`, ABI `arm64-v8a`, a measured **4096-byte** memory page and USB Host=true. Android detects Canon LBP2900 VID:PID **04A9:2676**; USB permission is granted, interface 0/alternate setting 0 has class 7/subclass 1/protocol 2, endpoints OUT BULK `0x0001` and IN BULK `0x0082`, and a non-forced claim succeeds. `GetBasicStatus` previously returned a valid six-byte response with status bytes `0x88` and `0x00`. The latest report also confirms a valid 22-byte `GetInputStatus` response (18 payload bytes), but the payload is deliberately kept opaque because the public upstream driver does not document its fields. No page/document data was sent; physical printing remains unverified. The app polls only the device list every 2.5 seconds while visible, records up to sixteen count changes and exports them without USB paths. Android's USB_DEVICE_ATTACHED is an activity intent rather than a normal broadcast; USB_DEVICE_DETACHED is a broadcast (official UsbManager API).

### Launcher icon
The launcher now uses an adaptive icon like the Bluetooth Disable project: a solid white background, the approved black printer line-art, a round variant, and a monochrome foreground for themed icons.

### Known device identity
The exact USB ID `04A9:2676` is mapped to Canon LBP2900. A physical test verified a valid six-byte `GetBasicStatus` response (`statusFlags=0x88`, `secondaryFlags=0x00`). The app provides manual, read-only `GetBasicStatus`, `GetExtendedStatus`, and `GetInputStatus` checks, guarded by USB permission, an exact VID:PID match, a unique BULK IN/OUT pair, alternate setting 0, and successful non-forced interface claim. The extended parser also exposes page counters when the reply contains them. This feature branch adds a similarly guarded `GetPrinterInfo` query for the transfer block/buffer parameters needed before a first print attempt. These checks do not print or clear/reset printer errors.

### Stage B foundations, not a working driver
Pure Kotlin USB descriptor selection refuses ambiguous BULK pairs; a bounded USB writer handles partial returns and interrupted writes without resending complete CAPT commands; a job state model makes completion contingent on explicit result confirmation and never automatically resumes after interruption. Page ranges and copies are parsed and bounded. All are covered by unit tests. These pure-Kotlin foundations are not wired to a real print job. Only the separately gated, manually triggered status probes send CAPT traffic; no print/page commands are enabled.


## Signed releases and upgrade compatibility

Private signed prereleases: [Releases](https://github.com/alcovaibe/capt-usb-print/releases). Future installed **signed** versions upgrade in place via the ordinary Android APK installer, keeping app settings. Signing certificate and `applicationId=com.cryonum.captusbprint` are permanently pinned. `versionCode` increases for each GitHub Actions release build.

The previous A1 diagnostic prerelease is a **debug app** using `com.cryonum.captusbprint.debug`; it cannot upgrade into a signed release. Install the first permanently signed A2 APK as a separate app, then all newer signed A2+ builds can install over it. There is no automatic in-app updater (private GitHub release requires authorization and the diagnostic app deliberately has no INTERNET permission).

**Owner-controlled signing setup is complete.** The repository secret `ANDROID_SIGNING` is configured, and signed build 33 passed the signature, package/version, prior-release and Android 16 in-place-upgrade checks before publication. Keep *two separate encrypted backups* of `CAPT_USB_Print_SIGNING_BACKUP.zip` from the conversation; it contains the private key backup and secret JSON. Do **not** commit, send publicly, upload to Releases or share that backup. Losing the private key means losing the ability to install later APKs as compatible upgrades.

CI never generates or changes the private signing key and cannot publish a release without the configured secret. `config/release-certificate.sha256` pins the independently generated public signing certificate, not the secret. Only the release job for trusted `main` has access to signing material, reconstructed outside the checkout and removed on completion. A signed release must pass build/lint/unit tests, APK verification and emulator install or in-place update on API 36 before publishing.

## Local development and diagnostics

JDK 21; Android Gradle Plugin 9.2.1; Gradle Wrapper 9.4.1; compileSdk/targetSdk 36; minSdk 29; Android SDK Build Tools 36.0.0. Build debug with `./gradlew :app:assembleDebug`. Debug is NOT upgradeable release. Never use ephemeral debug signing for user-facing Releases.

After this feature branch is merged and its signed private build is available, the next controlled hardware check is **Получить параметры принтера (GetPrinterInfo, без печати)** once with the powered-on LBP2900 connected. The query is read-only and uses the same exact VID:PID, permission, unique BULK endpoint and interface-claim checks. Share **Поделиться отчётом** afterwards. The result should expose the printer's reported `Blk` and `Buf` values; these must be reviewed before any page/job command is enabled. Technical details are collapsible, but the shared report remains complete. If zero devices are shown, reconnect the existing cable once and refresh. This build still sends no document/page data.

See [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md) for audit, limitations and next steps. This is unofficial software not endorsed by Canon.

### Additional isolated CAPT framing validation
`core/CaptFrame`, `core/BulkIo`, `core/JobSpecification`, `core/JobLedger` add pure-Kotlin CAPT framing, strict reply and bounded USB operation validation plus a conservative one-job gate. These are intentionally NOT connected to a real device; new tests in `StageBCoreTest`. The earlier `engine` components and USB observation improvements remain preserved; consolidation is pending actual printer descriptors. See `docs/STAGE-B-FOUNDATION.md`.


### JSON diagnostics and Telegram sharing
Manual actions (USB rescan, permission request after the Android permission result, all four CAPT status/info queries, and the explicit Share button) export a new immutable `.json` snapshot. The Show/Hide Details control and background USB list monitoring never open a share window. If installed, standard Telegram is opened directly as a document share target; the user still selects a chat and confirms sending. If unavailable, Android Sharesheet is used. The app has no INTERNET permission and does not transmit on its own. Files are offered through a nonexported scoped FileProvider with temporary URI read grants (cache reports/ only), not file:// paths.
The JSON contains app version/revision, a scan text record plus structured numeric VID/PID and permission values, bounded manual event history, full bounded CAPT response frame hex when available (header included), and separate decoded values. Raw data is retained even on field-parse failures after a full reply. The original on-screen diagnostic remains available. There is no printer serial query, document data or automatic page printing. JSON schemaVersion=1; unknown/missing values are null rather than invented.
