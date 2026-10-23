# LBP2900 startup contract correction after build 144

Baseline: main `34af4653324d2f7b925ffe45a65d2d8dff50fe26` (merged PR 29).
Hardware: owner reports from HONOR X9c / Android 16, Canon USB 04A9:2676.
No raw owner JSON, device identifiers or document content are committed.

## Engineering references and their limits

| Primary material | Relevant evidence | Limit |
| --- | --- | --- |
| Canon **LBP3000/2900 Series Service Manual**, [§2.7.1, mirrored page 45](https://www.manualslib.com/manual/954430/Canon-Lbp2900-Series.html?page=45) | USB/CAPT and decompression belong to the video controller; its reception RAM is 2 MB. | This is a Canon-authored manual hosted by a third party, not an official CAPT byte-level specification. |
| Same manual, [§2.7.2, page 46](https://www.manualslib.com/manual/954430/Canon-Lbp2900-Series.html?page=46) | The engine controller starts loads in response to the video controller's print command. Standby and printing are distinct phases. | A valid USB response alone does not prove a sheet can or did print. |
| Same manual, [§5.4, page 106](https://www.manualslib.com/manual/954430/Canon-Lbp2900-Series.html?page=106) | Service faults are reported through host status messages and Exxx codes, including E000/E100/E197/E747. | It does not map a short Basic response or SetPowerDown `80 00` to an Exxx hardware fault. |
| Canon [official LBP2900 User's Guide](https://gdlp01.c-wss.com/gds/5/0900006705/03/LBP2900_UsersGuide_en-uv_2.pdf) | User installation/status/troubleshooting reference. | It provides no verified contract for the two short replies at issue. |
| ValdikSS/captdriver **pinned 892f320c019974a5b737e000fcc2c8582264a131**, [prn_lbp2900.c](https://github.com/ValdikSS/captdriver/blob/892f320c019974a5b737e000fcc2c8582264a131/src/prn_lbp2900.c) | `lbp2900_ops.get_status = capt_get_xstatus`; `job_prologue = lbp3000_job_prologue`. That prologue settles InputStatus before Reserve, sends SetPowerDown, checks status/NOTREADY, then sends JobInfo START. It does not require SetPowerDown's reply payload to equal zero. | Following dispatch into capt-status.c matters: capt_get_xstatus is Basic-first with conditional Extended refresh, despite its name. Still reverse engineered, not Canon-certified. |
| Same pin, [protocol notes](https://github.com/ValdikSS/captdriver/blob/892f320c019974a5b737e000fcc2c8582264a131/info/lbp3000-protocol.md), [flow](https://github.com/ValdikSS/captdriver/blob/892f320c019974a5b737e000fcc2c8582264a131/info/lbp3000-printing-flow.md), [flags](https://github.com/ValdikSS/captdriver/blob/892f320c019974a5b737e000fcc2c8582264a131/src/capt-status.h) | LE framing, per-command contracts, Extended fields and job lifecycle. Notes describe capture-derived LBP3000 behavior and distinguish Windows/Linux flows. | They do not establish the semantics of this LBP2900's six-byte Basic response; SetPowerDown's nonzero response is not established as print refusal. |
| Original author's [captdriver issue 7](https://github.com/agalakhov/captdriver/issues/7), especially [captured short response](https://github.com/agalakhov/captdriver/issues/7#issuecomment-649788191) and [author's limits](https://github.com/agalakhov/captdriver/issues/7#issuecomment-662979629) | The exact short Basic `A0 E0 06 00 88 00` appeared in older LBP2900 trouble reports. The original author acknowledges incomplete restoration of state. | Similar bytes are corroboration, not proof of the same cause. Discussions include guesses and later retracted workarounds. |
| [OpenPrinting/cups issue 1461](https://github.com/OpenPrinting/cups/issues/1461) and [fix PR 1500](https://github.com/OpenPrinting/cups/pull/1500) | A concrete CUPS USB drain/backchannel race was investigated separately. | This Android app directly calls UsbDeviceConnection.bulkTransfer and has no CUPS sidechannel. Do not port CUPS quirks, flushes or resets as an Android fix. |

No public Canon byte-level command manual resolving these replies was found in
this search. Existing comments and reverse-engineering notes are not elevated
to such a specification.

## Fresh hardware evidence

Times below are UTC on 2026-10-23. Bundle export time is not execution time.
Historical queue records retain the evidence of their original builds.

| Actual attempt | Wire/result evidence | What is established |
| --- | --- | --- |
| Build 144 system document, 11:42:50 | 21 Basic `A0 E0 06 00 88 00` / Extended Bas=`31`, Bas1=`88` pairs; Pap=`80`, Cnt/Eng=`0`, all five counters `0`. PREFLIGHT, zero raster, no Reserve. | Software Basic veto prevents entry. No document data reached the printer. |
| Build 144 one-page test, 11:52:48 | Info valid; same Extended startup; Reserve `A0 A2 08 00 00 00 01 00` gives JobID **1**; SetPowerDown `BA E0 06 00 80 00`; PR-29 validator throws. All 26 events retained. | A distinct, newly introduced mandatory-ACK gate aborts **after successful reservation**, before JobInfo/descriptors/raster/StartPrint. No CAPT Release follows. |
| Build 144 two-page test, 11:53:31 | Extended Bas=`30`, then Reserve `A0 A2 08 00 87 00 00 00`. Zero raster. | Reservation refused with no valid JobID. This is neither Basic `88` nor historical Reserve `8C`. |
| Diagnostics 11:53:38 and 11:54:33 | Three samples per session: Basic `88`, Extended Bas=`31` / Bas1=`88`; UNKNOWN. | Responses are present and stable over each bounded session. UNKNOWN means unverified interpretation, not missing communication. |

The latest queue bundle adds the 11:54:33 diagnostic, not a new document attempt.
Earlier counter-confirmed tests and owner-confirmed one-page PDF output remain
historical physical evidence; no physical success is claimed for this correction.

## Framing and fields

The complete Extended frame is **56 bytes**, header `A8 A0 38 00`: four-byte
header plus 52-byte payload, LE length `0x0038 = 56`, not BCD 38. The production
reader obtained six bytes plus 50 bytes; no missing remainder is shown here.

| Absolute zero-based frame offset | Field |
| --- | --- |
| 0–1 / 2–3 | LE opcode / total frame length |
| 4 / 5 | Extended Bas / Bas1; these must not be exchanged |
| 8–9 | Buffer level, LE |
| 12 / 13 / 14 | Aux / Cnt / Pap |
| 16–17 | Eng, LE |
| 18–25 | Decoding, Printing, Out, Completed: four LE words |
| 38–39 | Received: LE word |

`A0 E0 06 00 88 00` is a transport-valid six-byte frame with two payload bytes.
That does not prove its first payload byte has the full Basic layout's flag
meaning. Extended Bas1 `88` is also not Extended Bas `88`. SetPowerDown `80 00`
and Reserve `87`/`8C` are responses to different commands and keep separate
contracts. No byte swapping, header slicing change or `88` whitelist is added.

## Implemented behavior

All four engines (single test, two-page test, one-page PDF pilot and document
queue, including Android PrintService jobs) now share LBP2900 startup admission:

1. GetPrinterInfo and validate transfer limits.
2. Poll ExtendedStatus; consume requested InputStatus and reread ExtendedStatus.
   Reject engine/communication/error or paper-unavailable evidence and incomplete
   page counters. Pending Input, busy/NOTREADY, delivery and unequal counters wait
   within the existing five-second polling budget / 21-sample limit. Transport
   latency is additional; cancellation is checked before each query.
3. Attempt ReserveUnit once, require its own zero Error and nonzero JobID.
4. Send SetPowerDown once, validate framing/two-byte length and preserve its
   response word without claiming print acceptance. Check Extended/Input state
   again, including bounded NOTREADY waiting, **before JobInfo START**.
5. Continue the existing conditional offline, initialization, online and page
   sequence. Required control ACKs, exact counters, END and Release remain checked.

This restores the Extended/Input admission of the historically working Android
paths and follows the pinned prologue's phase order, not its exact status-query
sequence. In the pin, `capt_get_xstatus` first reads Basic and refreshes Extended
only on a secondary change bit. The prologue does not apply our old `0x8e`
Basic-admission veto. Here fresh Extended data avoids relying on either the
short Basic layout or cached Extended fields. It replaces an unjustified extra Basic gate;
it does not turn `88` into ready. Basic remains raw and SHORT_UNVERIFIED in
read-only diagnostics. OFFLINE/unknown startup bits are not fabricated away.
A FREE bit is not made a new mandatory gate: the protocol notes also describe
online idle without it, and the authoritative ownership result is ReserveUnit.

One more difference is preserved deliberately: Android's historically working
`powerDownSettings()` sends `00 00`; the pinned C prologue sends `01 00` via
`lbp6000_job_init`. Whether this difference explains reply `80 00` is unproven.
Changing the setting and the acceptance policy together would obscure the
cause. This correction preserves the known Android request, records the reply
and checks the subsequent state. It does not declare the setting accepted or
claim exact wire equivalence with upstream. A later single-variable capture
with the official driver would be needed before changing that payload.

ReserveUnit's Error byte is also now required to be exactly zero, as documented
by the protocol table. The pinned C `capt_reserve_unit` actually checks only
bit 0x80; the earlier wording claiming it checked exact zero was incorrect.
The Android exact-zero policy is intentionally stricter: a high-bit-only mask
could accept a nonzero low error code together with a nonzero JobID. Its opaque
second byte remains uninterpreted. This is a source-backed contract correction,
not a claim that such a low error code occurred in the owner's reports.

Trace adds a distinct CONFIGURING_JOB phase and powerDownResponseWord, with
printAcceptance=false. Setup failures explain that page data was not sent while
preserving an uncertain CAPT lifecycle where reservation may already exist.
UNKNOWN diagnostics explain that the printer answered but readiness is not
confirmed. No user must interpret hex to complete another attempt.

No automatic Release of an uncertain reservation, reset, endpoint drain/flush,
retry, fabricated JobID or replay is introduced. Normal existing initialization
commands are not a newly added recovery path. USB ownership, exact reply reader,
source privacy, signing and manual-only export remain in force.

## Ranked hypotheses and minimal discriminating checks

| Hypothesis | Evidence/rank | Minimal check and expected result |
| --- | --- | --- |
| Extra Basic gate blocks documents before useful protocol entry | **Confirmed software stop**; short reply meaning remains open. | Replay captured Extended startup with no Basic query; require one valid Reserve and the normal sequence. Negative Extended fixtures must still stop before Reserve. On later hardware, trace must identify whether Reserve actually succeeds. |
| New generic SetPowerDown ACK requirement breaks tests | **Confirmed regression location**. Nonzero word's firmware meaning remains unknown. | Replay exact Reserve JobID=1 + PowerDown `80 00`. Require a subsequent Extended check before JobInfo, no duplicate Reserve/PowerDown. Fault that Extended response and require zero raster/no JobInfo. |
| Aborted owned JobID contributes to later Reserve `87` | **Plausible**, chronology and Bas `30` support it; later Bas `31` does not establish why it recovered. | After a successful fixed run, inspect same JobID through START/END/Release and one separately initiated subsequent job. If `87` persists despite acknowledged release, this lifecycle explanation is incomplete. Do not release guessed IDs. |
| PowerDown request `00 00` vs upstream `01 00` contributes to `80 00` | **Open**; request difference is source-proven, response semantics are not. Earlier Android printing used `00 00`. | Compare the official driver on the same firmware or a separately reviewed single-variable setting test, preserving all replies and post-setting Extended state. Do not combine it with reset, retry or another setting change. |
| Late/duplicated replies, wrong length, or concurrent USB owner | **Unproven** for these attempts. Observed opcode, lengths, query order and process lease agree; another app/identical-opcode stale reply is not excluded. | Preserve build/session/owner/exchange, requested/actual counts and complete frames. A wrong opcode or unread remainder must stop immediately. Printer-side USB capture is needed to prove identical-opcode freshness; no search-for-next-frame recovery. |
| A downstream dense-page/buffer or page lifecycle defect remains | **Not tested by zero-raster failures**. | Only if a later run reaches raster, inspect BufLevel per chunk, StartPrint/BlackEnd ordering and all page counters. Do not attribute preflight failures to compression or claim dense/multi-PDF acceptance from sparse test pages. |

## Verification scope

Captured fixtures establish byte preservation and replay the observed prefix.
Everything following the captured SetPowerDown response in the success test is
an explicitly **synthetic continuation**, including subsequent status, required
ACKs, page counters and physical output. Tests cover hard faults, incomplete
counters, Input resampling, bounded busy/NOTREADY, cancellation, separate Reserve
`87`/`8C` and low nonzero Error with valid JobID, rejected JobInfo/END/Release and no automatic replay.

Local manual-export guards and diff checks pass. Local Gradle download is blocked
by network access; final-HEAD PR CI must build, lint, run JVM tests and the existing
API-36 system/raster/diagnostic/recovery emulator checks. Emulator success still
cannot establish physical LBP2900 or HONOR Documents printing. Merge and release
remain owner-controlled.
