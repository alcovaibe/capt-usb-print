# Stage B — two-page hardware experiment: accepted 2026-10-22

The owner has now physically confirmed two different A4 sheets in one job:
the second sheet contains the two additional lower-left bars. Build 71 JSON
confirmed `oneReservedCaptJob=true`, `printerConfirmedPages=2`,
`sentPrintDataBytes=46112`, `TWO_PAGES_CONFIRMED_BY_PRINTER` and one
job end/release after counters completed 1 then 2. Stage B is owner-accepted.
PDF/image document printing is not included in Stage B.

## Hardware facts and protocol source

- User-confirmed LBP2900 04A9:2676 on HONOR X9c (Android API 36).
- Prior signed build 62 printed two independent one-page jobs successfully with
  separate CAPT counters, but that is NOT yet proof of one two-page job.
- Upstream ValdikSS/captdriver pinned commit
  `892f320c019974a5b737e000fcc2c8582264a131`,
  LBP2900 `lbp2900_page_prologue/page_epilogue`: successive IC_BEGIN_PAGE
  descriptors, `StartPrint(N)`, one inter-page `SetJobInfo2(flag=2)`, and
  `SetJobInfo2(flag=6)` + `ReleaseUnit` once at job completion.

## Deliberately conservative implementation

- A *new, explicitly confirmed* UI action, not a second press of the one-page
  button. The original one-page engine is unchanged.
- Precompress both 4960x7016 600dpi A4 test rasters before opening USB, max
  4 MiB compressed per page. The first page retains its verified registration
  target; the second adds **two separate lower-left bars**.
- One `CaptUsbSession`, one `ReserveUnit`, one job-start/GoOnline sequence.
  Capture and check real counters after the prologue; only the physically
  observed all-zero starting state is accepted for this first two-sheet test.
- Send first descriptor and HISCOA bands, observing BufLevel before EVERY
  video frame. Issue `StartPrint(1)` once after `IC_BLACK_END`, or before it
  only to drain a verified zero-free-slot buffer.
- Wait for first page's **new** completed/shipped counters, equal settled stage
  counters and paper-delivery flag clear. Only then send continuation flag 2
  using the SAME reserved JobID. No speculative page pipelining yet.
- Send second descriptor and different bands; `StartPrint(2)` is permitted only
  once page decoding has advanced to the second page of this job. Wait for two
  newly completed pages and settled stage counters. Send job-end and release
  exactly once, only after printer counter confirmation.
- A failed/ambiguous step terminates the attempt without blind replay, automatic
  reset, or unsafe cleanup commands. Captured trace and last raw reply remain
  in the local bounded JSON history, exported **only** via the explicit
  «Поделиться JSON-ответом» button.

## Owner test procedure (only after a signed CI-successful build)

1. Have at least two plain A4 sheets loaded, turn on the Canon LBP2900, and
   close other apps that may own the USB interface.
2. Use the separate **«Напечатать 2 разных листа (одно задание, эксперимент)»**
   action. Read the confirmation dialog and explicitly confirm once. It starts
   actual printing; it is NOT a JSON-report button.
3. Watch whether *two* pages emerge. Page 2 has the same top/left target plus
   two extra horizontal bars lower on the sheet. Photograph them side-by-side
   to compare position, rotation, missing data and physical sheet order.
4. Tap **«Поделиться JSON-ответом»** once and supply the entire JSON.
5. If only one or no sheet emerges, a timeout occurs, or the displayed result
   is uncertain: **do not press Print again**. Preserve the state and share
   diagnostics first. No automatic retry is implemented.

Acceptance was based on the owner's physical observation and JSON evidence
from 0.1.0-a2.71 on 2026-10-22, not on green CI alone. Numerical positioning
tolerances were not measured. Subsequent non-test-document rendering remains
a separate Stage C verification.
