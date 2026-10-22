# Stage B preparation: no physical print path enabled

This is a deliberately limited software checkpoint, not a claim that stage A or B is complete.

## Hardware evidence received
The owner's first A1.4 screen report: HONOR BRP-NX1, API 36, MagicOS display BRP-N21 10.0.0.172(C636E6R4P1), supported ABI includes arm64-v8a, measured runtime page size 4096 bytes, USB Host true. **Connected USB device count 0** at the time of the report. Therefore printer VID:PID, endpoints, interface and USB permission/claim remain unknown; the powered-on printer needs to be attached during a later scan.

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
1. With powered-on Canon LBP2900 connected using existing cable, open the newer signed app (when signing secret is installed) or the existing A1 debug app.
2. Refresh and grant access to its USB entry, share the full anonymized report via Share.
3. Only then bind a guarded Android USB channel and select a confirmed LBP2900 CAPT profile; validate known read-only response before any page command.


## Hardware checkpoint update — 2026-10-22
The owner now reports that the app enumerates exactly one USB device **04A9:2676** and that Android permission was granted. Public USB records map this ID to Canon LBP2900. The screenshot omits the expanded technical report, so interface class/subclass/protocol, endpoint addresses and types, and claim result are not yet confirmed. Next: use the exact-ID profile only for UI identification, then validate the full report and add a manually triggered read-only status request gated by a unique BULK IN/OUT pair and successful interface claim. No print commands are enabled.


## Read-only CAPT status probe added (2026-10-22; physical result pending)
The UI now offers a manual **Check CAPT status (no printing)** action only for exact VID:PID 04A9:2676 after USB permission is granted. It issues only GetBasicStatus (0xE0A0), after requiring a unique BULK IN/OUT pair, alternate setting 0, and successful non-forced claim. The bounded reply reader accepts LE or BCD length only when it matches the received frame. The scanner still sends no commands automatically. No page data, job setup, printer reset, or print command is implemented. This query still requires physical validation; synthetic unit tests do not prove the printer responds.


## Verified basic response and extended status check — 2026-10-22
The owner tested signed build 20 on the physical Canon LBP2900. The report confirms VID:PID `04A9:2676`, USB permission, class 7/subclass 1/protocol 2, OUT BULK `0x0001`, IN BULK `0x0082`, successful interface claim, and a valid six-byte `GetBasicStatus` response with `statusFlags=0x88`, `secondaryFlags=0x00`. The public CAPT status reference defines `0x88` as `ERROR_BIT|IM_DATA_BUSY`; this is not sufficient to infer a unique hardware fault.

The feature branch adds a separate user-triggered read-only `GetExtendedStatus` (`0xA0A8`) action. It requires the same exact-device, permission, unique-BULK-pair and claim gates. The parser reports buffer occupancy, auxiliary status, engine communication flags, paper availability and engine flags. No clear-error, reset, job setup or page command is sent. Unit tests cover the decoder and response parser; PR CI must pass before merge. A physical extended-status reply and physical printing remain unverified.
