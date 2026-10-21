# CAPT USB Print — private diagnostics (stage A)

Standalone offline Android USB diagnostics for Canon LBP2900 / HONOR X9c. This stage detects attached USB devices, asks for permission and shares anonymized device reports. **It cannot yet print.** No PrintHand or network services are used.

### Owner's first report and the next diagnostic
The first actual HONOR report measured API 36, ARM64, **4096-byte** memory pages, USB Host=true, and `UsbManager.deviceList` count **zero**. No Canon VID/PID has yet been observed and no USB permission can be requested until the OS enumerates the printer. The cause remains unknown; success of a previous PrintHand test does not validate our USB discovery. New diagnostic code (A2 source) polls only the device *list* every 2.5 seconds while the app is visible, records up to sixteen count changes, refreshes on changes, and exports the count history without USB paths. Android's USB_DEVICE_ATTACHED is an activity intent rather than a normal broadcast; USB_DEVICE_DETACHED is a broadcast (official UsbManager API). The user-facing hint does not claim hardware failure.

### Stage B foundations, not a working driver
Pure Kotlin USB descriptor selection refuses ambiguous BULK pairs; a bounded USB writer handles partial returns and interrupted writes without resending complete CAPT commands; a job state model makes completion contingent on explicit result confirmation and never automatically resumes after interruption. Page ranges and copies are parsed and bounded. All are covered by unit tests. **These foundations are not wired to a real printer and send zero CAPT traffic**. Hardware-gated CAPT profile integration follows only after enumerating and checking the actual USB descriptors.


## Signed releases and upgrade compatibility

Private signed prereleases: [Releases](https://github.com/alcovaibe/capt-usb-print/releases). Future installed **signed** versions upgrade in place via the ordinary Android APK installer, keeping app settings. Signing certificate and `applicationId=com.cryonum.captusbprint` are permanently pinned. `versionCode` increases for each GitHub Actions release build.

The previous A1 diagnostic prerelease is a **debug app** using `com.cryonum.captusbprint.debug`; it cannot upgrade into a signed release. Install the first permanently signed A2 APK as a separate app, then all newer signed A2+ builds can install over it. There is no automatic in-app updater (private GitHub release requires authorization and the diagnostic app deliberately has no INTERNET permission).

**Owner setup (one time):** A long-lived key has been generated specifically for this project. Owner must securely download and back up `CAPT_USB_Print_SIGNING_BACKUP.zip` from the conversation; it contains a PKCS12 and the prepared GitHub secret JSON. Do **not** commit, send publicly, upload to Releases or share it.

1. Open `alcovaibe/capt-usb-print` → **Settings → Secrets and variables → Actions → New repository secret**.
2. Secret name: `ANDROID_SIGNING`. Value: the **entire** contents of `ANDROID_SIGNING.secret.json` inside the private backup ZIP (one JSON object).
3. Save and initiate **Actions → CAPT USB Print checks and signed prerelease → Run workflow** on `main`. On success, the private signed release appears in Releases.
4. Keep *two separate encrypted backups* of the ZIP. Losing the private key means losing the ability to install later APKs as compatible upgrades.

CI never generates or changes the private signing key and cannot publish a release without the configured secret. `config/release-certificate.sha256` pins the independently generated public signing certificate, not the secret. Only the release job for trusted `main` has access to signing material, reconstructed outside the checkout and removed on completion. A signed release must pass build/lint/unit tests, APK verification and emulator install or in-place update on API 36 before publishing.

## Local development and diagnostics

JDK 21; Android Gradle Plugin 9.2.1; Gradle Wrapper 9.4.1; compileSdk/targetSdk 36; minSdk 29; Android SDK Build Tools 36.0.0. Build debug with `./gradlew :app:assembleDebug`. Debug is NOT upgradeable release. Never use ephemeral debug signing for user-facing Releases.

For hardware testing: connect powered-on LBP2900 by USB-C/USB-B to the HONOR X9c; launch app, refresh the USB list, deny then allow USB permission, tap **Поделиться отчётом**. Technical details are collapsible to improve phone readability, but the shared report remains complete. If zero devices are shown with the printer powered on, reconnect the existing USB cable once and keep the diagnostics app in front; it refreshes on device-list changes. USB sends no print data at this stage.

See [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md) for audit, limitations and next steps. This is unofficial software not endorsed by Canon.
