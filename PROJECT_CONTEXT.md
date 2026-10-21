# CAPT USB Print — project context

Updated: 2026-10-21. Stage: **A / diagnostic source prepared; hardware unverified.**

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
- Source authored; no local Android SDK or network in execution container, thus **local APK build unavailable**. Initial Actions run 39649721266 failed at Android setup because default package `tools` was unavailable; workflow now overrides setup packages to `platform-tools`. CI workflow on private \`main\` should build, lint, unit-test, scan permissions and retain 7-day private debug APK if green. Do not claim CI succeeded without checking actual run.
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

## Next actions
1. Confirm private CI \`assembleDebug\`, lint, tests and merged manifest permission audit; fix failures before sharing APK.
2. Owner installs A1 and shares anonymized report. Do not send test print or claim device ready from USB permission alone.
3. Model LBP2900 transport responses using observed device interface and verified upstream before Stage B.
