# Experimental Stage B — one physical A4 test page

Status: **one physical A4 page verified** by owner photo and matching CAPT completion counters on 2026-10-22. Independent repeat job and consecutive pages still unverified.

## Proven hardware facts (user's 2026-10-22 JSON)
- HONOR X9c / Android API 36; Canon LBP2900 VID:PID 04A9:2676.
- Unique interface 0 alt 0 class 7/1/2; BULK OUT 0x01, IN 0x82.
- GetPrinterInfo reply 56 bytes: Blk=0xFFF0 (65520), Buf=0x0040 (64), safe transfer chunk 0xFF00 (65280).
- GetExtendedStatus reply 56 bytes: Bas=0x31 (PRINTER_FREE/OFFLINE/unknown bit), secondary=0x88 (unverified), bufferSlots=15, paper=0x80, no known engine errors, all page counters zero. These are a snapshot, not a guarantee of printing.
- Device was **offline** in that snapshot. The printing path performs upstream's state-changing job initialization and attempts GoOnline. No physical GoOnline verification has occurred yet.

## Implementation scope
- Exact GPLv3-or-later HISCOA compression code from ValdikSS/captdriver at `892f320c019974a5b737e000fcc2c8582264a131`, with authors/licensing preserved in `app/src/main/cpp/upstream/`.
- Isolated JNI bridge, bounded at 128KiB raw per band. NDK pinned to 27.2.12479018 and CMake 3.22.1. x86_64 for emulator, arm64-v8a and armeabi-v7a for HONOR.
- A deterministic **single A4 sheet**, 4960x7016 at 600dpi, 1-bit raster, 64 scan lines per band. Prints asymmetric registration bars away from edges, no user document content.
- Page descriptor and anonymous SetJobInfo2 payload based on the LBP2900 registration's LBP3000 job prologue. No CUPS, PrintHand, network or privileged Android API.
- One exclusive USB claim for the entire job. Validates profile, VID/PID, permission, unique BULK endpoints, interface class, preflight parameters and page counters.
- ReserveUnit → job start → upstream clear/GoOnline sequence → MultiCommand page descriptor → streaming HISCOA video → IC_BLACK_END → StartPrint → counter observation → job end → ReleaseUnit on observed success.
- Conservative live BufLevel check before EVERY IC_VIDEO_DATA frame: if no buffer slots are available, StartPrint(1) is issued only after the expected decoding counter is observed, and the transfer waits for a free slot. This is deliberately slower than optimized batching, pending hardware verification.
- Bounded waiting, buffering, packet size, cancellation flag, controlled failure with explicit unknown outcome. Does not silently replay sent bytes or resume a job automatically.
- JSON reports print phase, progress trace, last raw CAPT reply, HISCOA SHA256, bytes sent and observed completion counters. No print-page raster content in diagnostics.
- All actions still export JSON with Telegram preference except Show Details.

## Hardware test procedure
1. Install the new signed build **over the previous signed APK**, with the printer powered on and A4 paper loaded. Close other apps using the printer.
2. First choose **«Подготовить тестовый растр (без печати)»**. Inspect/share the JSON. This only compresses a page locally; no USB commands.
3. If offline preparation succeeds, choose **«Напечатать один тестовый лист (эксперимент)»** **once**. This is a real print attempt and sends state-changing CAPT commands, including ClearError/GoOnline.
4. Observe if one actual paper sheet emerges, its print orientation, margins and registration marks. Share the generated JSON **even if no page emerges**.
5. If printing gets stuck or state is unknown, do **not** immediately press Print again. The app deliberately does not automatically resend or reset the printer after an uncertain command. Record the output and power-cycle only if necessary for recovery.

## Next Stage B checkpoint (prepared, NOT yet enabled for USB)
- After the 0.1.0-a2.62 repeat-one-page hardware test, check that a second independently started job prints without resetting the printer and that CAPT counters advance relative to the preceding job. Preserve and share its JSON.
- Two distinct offline registration rasters are now available: page 1 is bit-identical to the already physically confirmed pattern; page 2 adds two short lower-left horizontal bars while retaining the same top/left target so relative sheet alignment can be inspected.
- Offline counter tests require new page 1 and page 2 completion relative to the current job's captured baseline and reject stale, partial or rollover values.
- **No two-page USB button/job is enabled yet.** The next hardware protocol change must explicitly keep both pages in one reserved job, use page-relative StartPrint indices from the observed CAPT status, wait for both page completions, then end/release the job once. Do not simulate this by silently pressing the one-page print command twice.
- Only after owner confirms that second independent one-page job and shares the diagnostic, implement and expose an explicitly labelled two-page experimental action for another owner-controlled test.

## Current limitations
- The physical device has not been tested with job commands, HISCOA data, or paper output; **stage B is not complete**.
- Only one test page; multiple pages/restarts of jobs still need observation and additional implementation.
- Status mapping may be incomplete; unknown flag 0x20/0x88 are not interpreted as definitive errors.
- Partial commands and disconnects may leave printer memory in an unknown state; no automatic recovery.
- The first physical sheet was verified by owner photo and its JSON confirmed one newly completed page. A second press with build 60 was safely rejected at PREFLIGHT (zero print bytes): after completion, CAPT kept decoding/printing/out/completed/received counters all at 1. Nonzero equal counters are not automatically a pending page. Updated preflight recognizes a quiescent equal state; each new job now captures its own post-prologue baseline and requires genuinely increased counters before claiming success. Repeat physical test remains pending.
- `printer_counter_confirmed` alone means observed device counters, not a visually verified print; the owner's photo confirms the first sheet.
- USB job thread serializes with diagnostics via Activity worker. Shared PrintService queue comes after stage B.
- No PDF/image print, PrintService, or claims of general Android/printer compatibility yet.
