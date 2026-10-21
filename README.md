# CAPT USB Print (private development)

Independent Android USB Host print app for Canon LBP2900, currently **stage A diagnostics only**. Imported PulseAI code has been removed from the compiling application. No PrintHand code, payment workaround, network permission, CAPT commands, or printing functionality is included in this stage.

## Build

Android Studio/SDK platform 36, Build Tools 36.0.0, JDK 21, Gradle Wrapper 9.4.1, Android Gradle Plugin 9.2.1. Kotlin is provided by AGP 9.2 built-in Kotlin. minSdk 29; targetSdk 36. No NDK or CMake yet. Open repository and run \`./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest\`. Debug package: \`com.cryonum.captusbprint.debug\`; no release signing established.

For a local build with commit metadata: \`GITHUB_SHA="$(git rev-parse HEAD)" ./gradlew :app:assembleDebug\`. CI provides GITHUB_SHA automatically. Internal CI build artifact is not a release.

## Diagnostic test

1. Install only the A1 debug APK from this private repository's successful Actions run.
2. Launch with the printer connected; inspect the list and press Refresh if necessary.
3. Tap Request USB access for the printer; try a rejection, then request and allow.
4. Tap Share report and send the text report back for analysis. No page will print.

Report records USB VID/PID, interface classes/endpoints, raw device descriptor header (not string descriptors), OS info, permission and claim results. It never asks for INTERNET and does not include document contents, serial numbers or full USB device paths. Claim probe uses \`claimInterface(false)\` only for a uniquely selectable BULK IN/OUT interface, releases it immediately and never transmits commands or page data.

See PROJECT_CONTEXT.md for status, unknowns, and upstream audit. This is unofficial software, not endorsed by Canon.
