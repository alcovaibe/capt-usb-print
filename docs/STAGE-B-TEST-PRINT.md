# Stage B: real CAPT test printing, current evidence

The owner's Canon LBP2900 (VID:PID 04A9:2676) on HONOR X9c / Android API 36
has printed the original A4/600dpi test sheet and then **two further sheets in
two independent jobs** using signed build 62. Photos and CAPT counters confirmed
the first; the owner explicitly confirmed the following two physical sheets
without rebooting the printer. This does NOT yet prove two sheets in one job.

## Verified implementation and limits

- Upstream GPLv3-or-later HISCOA from ValdikSS/captdriver, pinned commit
  `892f320c019974a5b737e000fcc2c8582264a131`. JNI handles band compression;
  CAPT and USB transport are Android native (no CUPS, PrintHand or Internet).
- Single-sheet A4 4960x7016, 600dpi, 110 bounded bands, native HISCOA encoding,
  exclusive USB session, device and paper state validation, conservative buffer
  backpressure before every video frame, bounded waits and no automatic retry.
- The two independent build-62 PrintTestPage commands both produced a real
  sheet and `printer_counter_confirmed`. The correction to retained counters
  uses the current job's baseline, not an absolute `>=1`.
- All actions append to a private, bounded JSON history. **Only**
  «Поделиться JSON-ответом» opens Telegram/Android Sharesheet. Physical print
  buttons send real USB commands, not report requests.
- A separate explicit **two-page experiment** has now been implemented using
  one ReserveUnit/JobID and one job-start/end. It prepares different rasters,
  verifies the first sheet's completed/settled counters, sends SetJobInfo2
  continuation flag 2 once, then starts the second page with StartPrint(2).
  A confirmation dialog guards its button, and the existing one-page engine
  is untouched. See `docs/STAGE-B-TWO-PAGE-TEST.md`.

## Next hardware acceptance

After a signed build passes Android 16 installation/upgrade CI, perform the
single explicitly confirmed two-page experiment once. Observe two actual A4
sheets, their order, matching top/left registration target, and two additional
lower-left bars on sheet two. Export its JSON manually. Do NOT retry a failed
or ambiguous outcome before diagnostic analysis.

Stage B remains incomplete until the owner confirms the two real pages in one
CAPT job with correct orientation and no positional drift. PDF, image print
and system PrintService are later stages C and D, not included here.

Unknown CAPT status flags and device-recovery commands remain unvalidated.
Unexpected errors, unplugging or cancellation are not silently replayed.
