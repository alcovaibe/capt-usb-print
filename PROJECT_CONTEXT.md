# CAPT USB Print — project context

Updated: 2026-10-22. Stage: **A confirmed on physical HONOR; real CAPT stage B one-page candidate in PR, hardware printing unverified.**

## Scope and confirmed facts
- Source project was imported from private \`alcovaibe/PulseAI\` into separate private \`alcovaibe/capt-usb-print\`. Original must remain unchanged. The former PulseAI network/chat/service source is excluded from the new application.
- Owner reports HONOR X9c running Android 16 / MagicOS 10, Canon LBP2900, direct USB-C ↔ USB-B cable, and a successful physical PrintHand test page. Latest signed app report confirms VID/PID 04A9:2676, granted USB permission, interface 0/alt 0 class 7/subclass 1/protocol 2, OUT BULK 0x0001, IN BULK 0x0082, unique link selection, successful non-forced claim, and a valid GetInputStatus response.
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
- No local Android SDK/network is available in the execution container, so no local APK build is claimed. Historical setup/resource-linking failures were fixed. Latest main run 39728843497 passed assembleDebug, lintDebug, unit tests and the merged APK permission audit; its signed-release job also passed Android 16 emulator install/in-place-upgrade verification.
- CI success: run [39650348245](https://github.com/alcovaibe/capt-usb-print/actions/runs/39650348245), source commit `e624dc09b1ffefe25e2709f13359430bcb8f2de2`. `assembleDebug`, `lintDebug`, `testDebugUnitTest`, merged APK permission checks and private artifact upload passed. Artifact: `capt-usb-print-a1-debug`, ID `12370645206` (7-day retention). No Android device/emulator or printer was available to this run.
- Android platform APIs require physical phone verification for USB enumeration, OS/ABI/page size, request/deny/grant, detach, endpoint selection, raw descriptor read and claim probe.
- VID/PID, interface/endpoints and permission/claim are now confirmed. GetBasicStatus (6-byte reply) and GetInputStatus (22-byte reply) have been observed; GetInputStatus payload is opaque. GetPrinterInfo Blk/Buf values, a usable GetExtendedStatus counter response, a page transfer trace, physical print, and HONOR system PrintService remain unverified.
- Debug signature is ephemeral, not a stable update signature; no keystore committed. No automatic retry/printing after reconnect.

## Confirmed / unknown / how to check

| Item | State | Method |
| --- | --- | --- |
| USB enumeration / permission | Confirmed: one device 04A9:2676; permission granted | Owner's signed-build report, 2026-10-22 |
| Repository privacy | Confirmed private via GitHub metadata | Recheck on new commit |
| Device API/build/ABI/page size | API 36, build BRP-N21 10.0.0.172(C636E6R4P1), ABI includes arm64-v8a, page size 4096 bytes | Owner's report, 2026-10-22 |
| USB interface/endpoints/claim | Interface 0/alt 0 class 7/subclass 1/protocol 2; OUT 0x0001 and IN 0x0082 BULK; claim succeeds | Owner's report, 2026-10-22 |
| CAPT responses | GetBasicStatus valid (6 bytes); GetInputStatus valid (22 bytes total, payload opaque) | Owner's reports, 2026-10-22 |
| GetPrinterInfo transfer parameters | Not yet queried on hardware | New guarded read-only probe in this feature branch |
| Physical page print | Not implemented / not verified | Stage B, after status and transfer-parameter review |
| PDF / Android PrintService / HONOR Documents | Not implemented | Stages C/D |

## Private prereleases
- Owner authorized test builds in Releases of this private repository only; no public repository, Play Store, or other distribution channel.
- Current workflow: pull requests run build/lint/unit tests and the merged-manifest permission audit. Only trusted pushes to main or manual main workflow dispatch can access the ANDROID_SIGNING secret and run the signed-release job.
- Signed release build 33 was published on 2026-10-22: tag v0.1.0-a2-signed.build.33, package com.cryonum.captusbprint, version 0.1.0-a2.33, versionCode 1033. APK asset: CAPT-USB-Print-0.1.0-a2-build-33.apk.
- Run 39728843497 passed Android build/lint/unit tests, APK permission audit, signing-certificate/version checks, Android 16 emulator install/in-place-upgrade verification, and the release publish step.
- The feature PR must not be merged automatically: merging to main triggers a new private signed release. Review its PR CI first and merge only when the next signed test build is wanted.
## Next actions
1. Run PR CI for the read-only GetPrinterInfo probe and extended page-counter parser; do not merge if build, lint, tests or permission audit fail.
2. After the feature is merged and its signed private build is available, run GetPrinterInfo once and inspect the actual Blk/Buf values.
3. Continue the LBP2900-specific upstream audit. Do not enable job initialization, error-clearing recovery, page descriptors, HISCOA data, or StartPrint until the required command sequence and response handling have tests and explicit readiness gates.

## Owner-controlled signed updates (2026-10-21)
- Owner requested permanent upgradeable APKs as in private VK Music Export. A separate CAPT USB Print key was generated; its public certificate SHA-256 fingerprint is 6bb4ec99e2ce17383383a918c4d8b942b2a19ba06660e18ee1e1e606d84fdc5e. The private keystore/password remain outside Git.
- ANDROID_SIGNING is now configured. Main run 39728843497 successfully restored the owner-controlled key, verified the signed APK and prior release, passed the Android 16 emulator in-place upgrade check, and published the private signed build 33. Keep two separate encrypted backups of the signing ZIP; losing the key prevents compatible future upgrades.
- Release package remains `com.cryonum.captusbprint`. Debug remains `com.cryonum.captusbprint.debug` (different package, therefore no misleading update path). Signed versionCode = 1000 + GitHub run number, tagged `v0.1.0-a2-signed.build.<run>`.
- On every signed release, verify APK signature with the public pinned certificate, non-debuggable package, increasing version code, prior APK signature and Android 16 emulator in-place `adb install -r` (no uninstall). No PrintService or CAPT printing yet.
- Layout correction following owner screenshot: show USB device cards and concise permission states by default; full technical report collapsible. Share always includes the full anonymized report. First owner phone confirmation required.
- Existing old A1 debug releases kept for history; they are not part of the signed update chain. We did not modify VK Music Export.


## Continuation after first diagnostic screenshot (physical USB test still pending)
**The screenshot from diagnostic APK 0.1.0-a1.4 displays the following values; it was not a controlled printer-connection test:**
- Device model `HONOR BRP-NX1`; Android API 36, MagicOS build string `BRP-N21 10.0.0.172(C636E6R4P1)` (as shown in screenshot); ABIs `arm64-v8a, armeabi-v7a, armeabi`; memory page size exactly **4096 bytes**; USB Host feature `true`.
- Screenshot says **Connected USB device count: 0**; no printer descriptor, no VID:PID, no endpoints, no permission request. Owner clarified the USB connection was not deliberately checked during this step; it is unknown whether the printer was physically connected when the screenshot was captured. This is not evidence of a failed cable, printer, or USB discovery. Previously the owner reported a PrintHand test page worked, but that does not verify this app's current discovery.
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


## Verified integration checkpoint (build #10, 2026-10-21)
- Private `main` source `b8f99f81c4e23135a3c9f47e70bb768c830a4a3c` passed [GitHub Actions run #10](https://github.com/alcovaibe/capt-usb-print/actions/runs/39663011497): debug APK assembly, lint, JVM tests (both foundation suites), Python release script syntax and merged manifest permission audit. Signed-release job correctly **skipped** because owner has not added `ANDROID_SIGNING`. Old A1 debug prerelease is retained as history.
- Read-only USB probe now reuses tested `UsbLinkSelector` instead of a separate permissive candidate check. Ambiguous/truncated descriptor lists skip interface claim. No new CAPT transport or real print implemented.
- Zero-result USB writes immediately return uncertain, never silently repeat. Pure range parser accepts the typographic `1–3` example in the original technical specification.
- Parallel source-only CAPT frame, I/O and job model changes were merged separately and are covered by StageBCoreTest. The components have deliberately not been attached to the live device. Before wiring, consolidate any overlapping prototype models; do not claim real printing from passing mocked tests.


## Launcher icon (2026-10-22)
- Replaced the manifest launcher reference with `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`.
- Added adaptive and round adaptive launcher icons matching the structure in `alcovaibe/Bluetooth-disabler`: solid white background, printer line-art foreground, and monochrome foreground for themed Android icons. The foreground is a scalable VectorDrawable based on the approved printer graphic.
- Added layer-list fallback resources. The old `ic_app.xml` remains unused; the manifest now resolves the new adaptive icon.
- This is an icon/resource change only; it does not imply USB hardware validation or printing support.


## Owner signing secret validation checkpoint (2026-10-22)
- Owner reports that repository Actions secret `ANDROID_SIGNING` has now been added.
- The previous run #13 started before this confirmation; its signed-release job was skipped, so it does not validate the new secret.
- The next push to `main` will test the JSON schema, PKCS12 restoration, release signature, monotonically increasing versionCode and Android 16 install/upgrade. A release is published only if each release-gate step succeeds. Never echo or fetch secret contents.


## Hardware checkpoint: USB enumeration succeeded (2026-10-22)
- Owner provided a screenshot from the signed A2 build on HONOR X9c with one enumerated USB device: **VID:PID 04A9:2676**, and the system USB permission state says **granted**.
- Cross-check against public USB device records identifies 04A9:2676 as **Canon LBP2900**. The exact VID:PID match is good model evidence; it does not prove interface compatibility or that CAPT exchanges work.
- The screenshot does not show the detailed technical report. Interface classes, alternate settings, endpoint addresses/types, descriptor header, interface-claim result and any printer response are not yet recorded in the project evidence.
- Next code step: label the known device from the exact VID:PID table; keep unknown IDs unnamed. Then inspect the full anonymized report and implement an explicitly user-triggered, read-only CAPT status query only if the selected interface is unambiguous and claim succeeds. No print/page commands until status parsing is validated.


## Read-only CAPT status probe (source checkpoint, 2026-10-22)
- Added a button for the exact known profile Canon LBP2900 (VID:PID 04A9:2676) only after Android USB permission is granted.
- The button sends only CAPT GetBasicStatus (command 0xE0A0), and only after a unique BULK IN/OUT pair, alternate setting 0, successful non-forced interface claim, and exact VID:PID match. The automatic scanner itself still sends no CAPT commands.
- The response reader is bounded to 1024 bytes and finite timeouts. It validates command ID and either LE or BCD frame length against the actual received length. Partial request writes are not retried; interface is released and connection closed in finally.
- The UI/report records only the basic status bytes and whether a valid response arrived. It does not send a page, print payload, or any job/service command.
- This source-level probe is not yet validated on the physical printer. Next step after CI: install the latest signed private release, press the new status button once, and share the report. A valid response is required before developing the first page path.


## Hardware result and CAPT reply-length correction (2026-10-22)
- On signed build 19 (versionCode 1019, revision `abc703f9c404`), HONOR X9c enumerated Canon LBP2900 (VID:PID 04A9:2676), USB permission was granted, the unique BULK IN/OUT interface was claimed successfully, but the manually triggered GetBasicStatus query failed during response-length validation. No print/page data was sent.
- Source review identified an overly strict condition: the live probe discarded every candidate total frame length below 10 bytes, while the upstream CAPT framing implementation accepts a 6-byte total response (4-byte header plus two basic-status bytes). The basic-status parser also incorrectly required the optional buffer-level bytes.
- Corrective change: read the 6-byte CAPT framing/status prefix first, accept a bounded 6-byte minimal reply, and parse buffer-level bytes only when the reply contains them. For other invalid headers, the anonymized report now records only the first four framing bytes and derived LE/BCD lengths/candidates; it does not record status payload bytes or document data.
- Next hardware gate: install the new signed build, run the read-only status query once, and inspect the result. No print/page commands or print path have been enabled.


## Verified GetBasicStatus and extended diagnostic implementation (2026-10-22)
- The owner tested signed build 20 (`0.1.0-a2.20`, versionCode 1020, revision `bc301f8f8d3a`) on HONOR X9c with the physical Canon LBP2900. The report confirms VID:PID `04A9:2676`, USB permission, interface class 7/subclass 1/protocol 2, OUT BULK `0x0001`, IN BULK `0x0082`, successful interface claim, and a valid 6-byte `GetBasicStatus` reply.
- The actual status bytes were `0x88` and `0x00`. Upstream public CAPT status definitions identify the first byte as `ERROR_BIT` (`0x80`) plus `IM_DATA_BUSY` (`0x08`); this does not uniquely identify a physical fault. The second byte does not request extended/input status.
- Feature branch `feat/extended-capt-status` adds a manually invoked **Проверить расширенное состояние CAPT (без печати)** button alongside the basic-status check. It sends only read-only `GetExtendedStatus` (`0xA0A8`) after exact VID:PID, current Android USB permission, unique BULK IN/OUT selection, alternate setting 0, and non-forced interface claim checks.
- The bounded extended parser extracts the basic/secondary status bytes, buffer level, auxiliary engine state, engine communication flags, paper availability and engine flags. The report records raw hex values and known bit names; it does not send page/job data, clear errors, reset the printer or enable printing.
- Unit tests now cover the status flag decoder and the extended response parser. CI on the pull request must pass before merge. Physical `GetExtendedStatus` response and actual page printing remain unverified.
- The USB permission event line is explicitly session-local; the screen status now uses the current per-device permission state to avoid showing a stale “not requested” event when Android permission is already granted.


## Latest hardware report and Stage B handoff (2026-10-22)
- Owner tested signed build 0.1.0-a2.33 (versionCode 1033, revision 3c5b6ff8a9f5) on HONOR X9c with the physical Canon LBP2900.
- Reported phone facts: model HONOR BRP-NX1; API 36; build BRP-N21 10.0.0.172(C636E6R4P1); ABIs arm64-v8a,armeabi-v7a,armeabi; measured page size 4096 bytes; USB Host=true.
- USB facts: exactly one device VID:PID 04A9:2676, known profile Canon LBP2900; USB permission=true; interface 0/alternate setting 0 class 7/subclass 1/protocol 2; OUT BULK 0x0001, IN BULK 0x0082; unique link selection; open success; claim(interfaceId=0,force=false)=true; release and close completed.
- Observed response: GetInputStatus valid, total frame 22 bytes, payload 18 bytes: 00 10 C0 00 00 00 00 00 96 00 FD 00 00 00 00 00 00 00. The payload is retained as opaque diagnostic data; do not infer paper/readiness semantics from these bytes.
- The scan itself sent no CAPT command. The manual GetInputStatus probe sent no document/page data. No physical print has been attempted by CAPT USB Print.
- Upstream anchor remains ValdikSS/captdriver commit 892f320c019974a5b737e000fcc2c8582264a131. In that source, the LBP2900 registration uses lbp3000_job_prologue, while its page and job epilogues are shared with the LBP2900 profile. Initialization includes state-changing operations; do not port it as a blind command list.
- The new feature branch adds a manual read-only GetPrinterInfo probe and optional Start/Printing/Shipped/Printed fields from GetExtendedStatus. It does not send any page/job command. Next hardware gate is to review the actual Blk/Buf reply before wiring transfer sizing.


## Stage B physical report and implementation (2026-10-22)
- User's signed build 41 JSON: GetPrinterInfo exact 56-byte reply, Blk 65520, Buf 64, cap 65280; GetExtendedStatus exact 56-byte reply, Bas=0x31 (offline plus unverified 0x20), secondary=0x88 unknown, free slots=15, Pap=0x80, EngineFlags=0 and page counters=0. Android USB permission/unique endpoints confirmed. Original JSON is in the ChatGPT conversation, not committed to the repo.
- Feature PR adds vendor GPLv3-or-later HISCOA compression source and JNI through pinned NDK/CMake, with a 600dpi 4960x7016 A4 registration-pattern raster and 64-line bands; one user-triggered real print prototype now exists, but no physical printed sheet has been observed.
- New code uses bounded single-owner Android USB session, explicit ReserveUnit/job sequence (LBP2900 registered to LBP3000 job prologue upstream), page descriptor/video bands, StartPrint, completion-counter validation and ReleaseUnit only after confirmation. Failure/interrupt state is explicitly unknown and never auto-retried.
- Fixed an identified diagnostic framing risk: a 56-byte LE length can look like a 38-byte BCD length; new shared preferredReplyLength avoids prematurely accepting the shorter BCD candidate on partial USB reads.
- Upstream sources are preserved with GPL headers. A single compiler-only local change initializes HISCOA best_cmd to zero for -Wmaybe-uninitialized, documented in THIRD-PARTY-NOTICES.md. The entire combined APK is subject to applicable GPLv3 source and redistribution obligations.
- Do not claim B complete until owner confirms physical A4 print, orientation and two follow-up jobs. No PDF/PrintService.


## Stage C shared A4 geometry feature branch (2026-10-22; awaiting PR CI)

- Starting main SHA: `630492b337a9c1202c1d0bf981c85dfec2f64c42`.
  Private branch: `feat/stage-c-shared-a4-preview-geometry`; do not merge
  automatically because main publishes a permanent-key private test release.
- The Stage B owner-accepted CAPT page grid is 4960 × 7016 at 600 dpi.
  Added pure `document/A4PageGeometry` fit layout and a scaled preview
  mapping. Landscape is logical only; the physical CAPT adapter still sends its
  proven portrait descriptor, and no landscape document print is enabled.
- The 260 px (~11 mm) software FIT inset is provisional, based on the safe
  registration-mark region, NOT verified as the device's mechanical margin.
- PDF and EXIF-oriented PNG/JPEG display previews are composed onto white A4
  sheets with this shared geometry. Switching the document orientation
  re-renders the current page. No USB/CAPT document commands were added.
- New pure unit tests validate paper dimensions, fit/aspect, boundaries,
  logical orientations, scaled coordinates and memory/pixel limits.
- Required next checks: PR CI, phone preview confirmation, then a bounded
  sequential print rasterizer/landscape rotation/HISCOA adapter and a real
  document physical print. Stage C acceptance remains open.
