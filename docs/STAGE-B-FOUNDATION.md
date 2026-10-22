# Stage B preparation: no physical print path enabled

This is a deliberately limited software checkpoint, not a claim that stage A or B is complete.

## Hardware evidence received
The latest owner report from signed build 0.1.0-a2.33 (2026-10-22) confirms HONOR BRP-NX1, API 36, build BRP-N21 10.0.0.172(C636E6R4P1), ABI arm64-v8a, measured page size 4096 bytes, USB Host true, and one device: Canon LBP2900 VID:PID 04A9:2676. Android USB permission is granted. Interface 0/alternate setting 0 is class 7/subclass 1/protocol 2 with OUT BULK 0x0001 and IN BULK 0x0082; the unique link selection and non-forced claim succeed. A valid GetBasicStatus reply was previously confirmed, and the latest report contains a valid 22-byte GetInputStatus reply. The input-status payload remains opaque; no document/page data has been sent.

## Upstream anchor
Public ValdikSS/captdriver `val` at `892f320c019974a5b737e000fcc2c8582264a131`, examined `src/capt-command.c/.h`, `generic-ops.c`, `prn_lbp2900.c`, and `canon-lbp.drv`. Some routines and comments mix LBP2900 and LBP3000 protocol paths. Do not assume their command profiles are interchangeable. Protocol coding, flow-control details, replies and physical capabilities require separate hardware validation. Upstream source is GPLv3-or-later; **we have not copied its implementation** and any later native port must carry appropriate notices/license/source obligations.

## New source-only offline preparatory components
- `core/CaptFrame`: bounded LE header and multi-command encoding with structural, command and exact-length reply validation. No control, status, transport, printer output, or BCD variant implemented; invalid/partial input is rejected.
- `core/BulkIo`: bounded short read/write support, finite deadlines, explicit partial-write uncertainty, no hidden command retry. `BulkChannel` is a unit-test seam, not an Android transport attached to the app.
- `core/JobSpecification`: validates simple page ranges, duplicates, source bounds, copies and collated order. This is a logical job model only, NOT a verified device profile.
- `core/JobLedger`: conservative single-job state machine with unknown physical outcome if disconnected in transmission/confirmation; no automatic replay.
- JVM tests verify independent known frame bytes, malformed frames, edge cases, partial/failing USB operations, job exclusivity and ambiguous outcome.
- These components are intentionally **not connected to the Activity**. The application still does not print; it cannot claim real compatibility before the printer is enumerated and authenticated for the right interface.

## Subsequent hardware gate
1. The latest hardware report confirms the device, permission, unique BULK link, successful interface claim and valid GetInputStatus response.
2. This feature branch adds a manual read-only GetPrinterInfo query. After CI passes and the change is merged into the private main branch, install its signed build, run GetPrinterInfo once, and share the full anonymized report.
3. Review Blk/Buf values and extended-status page counters before enabling the first test page. No page/job command is sent by this diagnostic branch.


## Hardware checkpoint update — 2026-10-22
The owner now reports that the app enumerates exactly one USB device **04A9:2676** and that Android permission was granted. Public USB records map this ID to Canon LBP2900. The screenshot omits the expanded technical report, so interface class/subclass/protocol, endpoint addresses and types, and claim result are not yet confirmed. Next: use the exact-ID profile only for UI identification, then validate the full report and add a manually triggered read-only status request gated by a unique BULK IN/OUT pair and successful interface claim. No print commands are enabled.


## Read-only CAPT status probe added (2026-10-22; physical result pending)
The UI now offers a manual **Check CAPT status (no printing)** action only for exact VID:PID 04A9:2676 after USB permission is granted. It issues only GetBasicStatus (0xE0A0), after requiring a unique BULK IN/OUT pair, alternate setting 0, and successful non-forced claim. The bounded reply reader accepts LE or BCD length only when it matches the received frame. The scanner still sends no commands automatically. No page data, job setup, printer reset, or print command is implemented. This query still requires physical validation; synthetic unit tests do not prove the printer responds.


## Verified basic response and extended status check — 2026-10-22
The owner tested signed build 20 on the physical Canon LBP2900. The report confirms VID:PID `04A9:2676`, USB permission, class 7/subclass 1/protocol 2, OUT BULK `0x0001`, IN BULK `0x0082`, successful interface claim, and a valid six-byte `GetBasicStatus` response with `statusFlags=0x88`, `secondaryFlags=0x00`. The public CAPT status reference defines `0x88` as `ERROR_BIT|IM_DATA_BUSY`; this is not sufficient to infer a unique hardware fault.

The feature branch adds a separate user-triggered read-only `GetExtendedStatus` (`0xA0A8`) action. It requires the same exact-device, permission, unique-BULK-pair and claim gates. The parser reports buffer occupancy, auxiliary status, engine communication flags, paper availability and engine flags. No clear-error, reset, job setup or page command is sent. Unit tests cover the decoder and response parser; PR CI must pass before merge. A physical extended-status reply and physical printing remain unverified.


## Latest hardware evidence and read-only GetPrinterInfo step (2026-10-22)
- Observed GetInputStatus response: total frame 22 bytes, payload 18 bytes. Its fields are intentionally not decoded because the examined public captdriver path requests this command but does not define the returned payload structure.
- The feature branch adds GetPrinterInfo (0xA1A1) as a manual read-only probe. The parser requires ten payload bytes before reading Blk at payload offsets 6–7 and Buf at offsets 8–9; it does not substitute default sizes when the response is short or values are zero.
- GetExtendedStatus parsing now exposes Start/Printing/Shipped/Printed counters only when all four fields are present; otherwise they are reported as unavailable. PageReceived is optional.
- This remains diagnostic preparation. There is no job initialization, page setup, HISCOA raster transfer, StartPrint, or physical print path in this change.
